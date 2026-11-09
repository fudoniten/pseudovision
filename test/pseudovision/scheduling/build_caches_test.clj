(ns pseudovision.scheduling.build-caches-test
  "Per-build caches: a build resolves each collection and loads each Grout pool
   once, however many slot occurrences and gaps use them. Before this a 14-day
   build re-ran the collection query on every occurrence and asked Grout for
   candidates on every gap."
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.db.collections :as col-db]
            [pseudovision.db.core :as db-core]
            [pseudovision.db.filler :as filler-db]
            [pseudovision.db.media :as media-db]
            [pseudovision.media.grout :as grout]
            [pseudovision.media.grout-source :as grout-source]
            [pseudovision.scheduling.core :as core]
            [pseudovision.scheduling.cursor :as cursor])
  (:import [java.time Duration Instant]))

(def ^:private t0 (Instant/parse "2026-04-27T20:00:00Z"))

(defn- build-opts []
  {:seed 0 :pack-filler? true
   :grout {:enabled? true :base-url "http://grout"}
   :items-cache (atom {})
   :grout-pools (atom {})})

(def ^:private block-slot
  {:schedule-slots/id 1
   :schedule-slots/collection-id 7
   :schedule-slots/block-duration (Duration/ofMinutes 30)
   :schedule-slots/tail-mode "filler"
   :schedule-slots/playback-order "chronological"})

(def ^:private channel {:channels/id 1 :channels/slug "test"})

(defn- episode [id mins]
  {:media-items/id id :media-versions/duration (Duration/ofMinutes mins)})

(defn- clip [id secs]
  {:media-items/id id :media-versions/duration (Duration/ofSeconds secs)})

(deftest collection-resolved-once-per-build
  (let [resolves (atom 0)
        opts     (build-opts)]
    (with-redefs [media-db/get-collection         (fn [_ _] {:collections/id 7 :collections/kind "manual"})
                  col-db/resolve-collection       (fn [_ _] (swap! resolves inc) [(episode 1 22) (episode 2 22)])
                  filler-db/get-filler-preset     (fn [_ _] nil)
                  grout/enabled?                  (fn [_] true)
                  grout-source/grout-filler-pool  (fn [& _] [])]
      (loop [i 0 cur (cursor/init t0) seen []]
        (if (< i 5)
          (let [[events cur'] (core/emit-block nil cur block-slot channel 1 opts)]
            (recur (inc i) cur' (into seen (keep :media-item-id events))))
          (do
            (is (= 1 @resolves) "five occurrences of the slot, one collection query")
            (is (= [1 2 1 2 1] seen) "the rotation still advances across occurrences")))))))

(deftest no-cache-without-build-opts
  (testing "emitters called outside build! (opts without caches) resolve every time"
    (let [resolves (atom 0)]
      (with-redefs [media-db/get-collection     (fn [_ _] {:collections/id 7 :collections/kind "manual"})
                    col-db/resolve-collection   (fn [_ _] (swap! resolves inc) [(episode 1 22)])
                    filler-db/get-filler-preset (fn [_ _] nil)]
        (core/emit-block nil (cursor/init t0) block-slot {} 1 {})
        (core/emit-block nil (cursor/init t0) block-slot {} 1 {})
        (is (= 2 @resolves))))))

(deftest grout-pool-loaded-once-and-cut-to-the-gap
  (let [loads (atom 0)
        opts  (build-opts)
        pool  [(clip 90 60) (clip 91 120) (clip 92 600)]]
    (with-redefs [media-db/get-collection        (fn [_ _] {:collections/id 7 :collections/kind "manual"})
                  ;; one 22-minute episode in a 30-minute block: an 8-minute tail
                  col-db/resolve-collection      (fn [_ _] [(episode 1 22)])
                  filler-db/get-filler-preset    (fn [_ _] nil)
                  grout/enabled?                 (fn [_] true)
                  grout-source/grout-filler-items (fn [& _] (throw (Exception. "per-gap Grout query")))
                  grout-source/grout-filler-pool (fn [& _] (swap! loads inc) pool)]
      (let [events (loop [i 0 cur (cursor/init t0) acc []]
                     (if (< i 4)
                       (let [[evs cur'] (core/emit-block nil cur block-slot channel 1 opts)]
                         (recur (inc i) cur' (into acc evs)))
                       acc))
            filler (filter #(#{90 91 92} (:media-item-id %)) events)]
        (is (= 1 @loads) "four tails, one Grout pool load")
        (is (seq filler) "tails are filled from the pool")
        (is (not-any? #(= 92 (:media-item-id %)) filler)
            "the 10-minute clip never lands in an 8-minute gap")))))

(deftest bumper-pool-loaded-once
  (let [loads (atom 0)
        opts  (build-opts)
        ;; a 20m episode in a 20m10s block leaves a 10s gap: a bumper gap
        slot  (assoc block-slot :schedule-slots/block-duration (Duration/ofSeconds 1210))]
    (with-redefs [media-db/get-collection        (fn [_ _] {:collections/id 7 :collections/kind "manual"})
                  col-db/resolve-collection      (fn [_ _] [(episode 1 20)])
                  filler-db/get-filler-preset    (fn [_ _] nil)
                  grout/enabled?                 (fn [_] true)
                  grout-source/grout-bumper-items (fn [& _] (throw (Exception. "per-gap Grout query")))
                  grout-source/grout-bumper-pool (fn [& _] (swap! loads inc) [(clip 80 10) (clip 81 30)])
                  grout-source/grout-filler-pool (fn [& _] [])]
      (let [events (loop [i 0 cur (cursor/init t0) acc []]
                     (if (< i 3)
                       (let [[evs cur'] (core/emit-block nil cur slot channel 1 opts)]
                         (recur (inc i) cur' (into acc evs)))
                       acc))]
        (is (= 1 @loads))
        (is (= 3 (count (filter #(= 80 (:media-item-id %)) events)))
            "each 10s gap gets the 10s bumper; the 30s one never fits")))))

(deftest tag-filters-use-one-query
  (let [queries (atom 0)
        slot    (assoc block-slot :schedule-slots/required-tags ["comedy"]
                                  :schedule-slots/excluded-tags ["kids"])]
    (with-redefs [media-db/get-collection   (fn [_ _] {:collections/id 7 :collections/kind "manual"})
                  col-db/resolve-collection (fn [_ _] [(episode 1 22) (episode 2 22) (episode 3 22)])
                  db-core/query             (fn [_ _]
                                              (swap! queries inc)
                                              [{:metadata/media-item-id 1 :metadata-tags/name "comedy"}
                                               {:metadata/media-item-id 2 :metadata-tags/name "comedy"}
                                               {:metadata/media-item-id 2 :metadata-tags/name "kids"}])]
      (is (= [1] (map :media-items/id (#'core/load-items nil slot channel))))
      (is (= 1 @queries) "three candidates, one tag query"))))

(deftest builds-of-one-playout-do-not-overlap
  (let [running (atom 0)
        overlap (atom false)]
    (with-redefs [pseudovision.db.playouts/get-playout (fn [_ id] {:playouts/id id})
                  core/build! (fn [_ _ _]
                                (when (> (swap! running inc) 1) (reset! overlap true))
                                (Thread/sleep 50)
                                (swap! running dec)
                                1)]
      (let [a (future (core/rebuild-horizon! nil 5 0 14))
            b (future (core/rebuild-from-now! nil (int 5) 14))]
        @a @b
        (is (false? @overlap) "the second build waits for the first")))))
