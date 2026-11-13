(ns pseudovision.scheduling.random-block-test
  "Random-order blocks: content is drawn without hunting for something that
   fits, recently aired items are passed over, and the leftover filler is
   spread between programs. The regression: a 2-minute trailer in a pool of
   sitcom episodes filled every small gap, back to back."
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.scheduling.core :as core]
            [pseudovision.scheduling.cursor :as cursor]
            [pseudovision.scheduling.enumerators :as enum]
            [pseudovision.db.filler :as filler-db]
            [pseudovision.db.media :as media-db]
            [pseudovision.media.grout :as grout]
            [pseudovision.media.grout-source :as grout-source]
            [pseudovision.util.time :as t])
  (:import [java.time Duration Instant]))

(def ^:private t0 (Instant/parse "2026-11-10T00:00:00Z"))

(defn- item [id minutes]
  {:media-items/id id :media-versions/duration (Duration/ofMinutes minutes)})

(def ^:private trailer-id 99)

(def ^:private spectrum-pool
  "Fifty 22-minute episodes plus one 2-minute trailer."
  (conj (mapv #(item % 22) (range 1 51)) (item trailer-id 2)))

(def ^:private channel {:channels/id 1 :channels/slug "spectrum"})

(def ^:private tail-preset
  {:filler-presets/id 42 :filler-presets/mode "duration" :filler-presets/role "tail"})

(defn- filler-pool []
  (mapv #(item (+ 500 %) (inc (mod % 4))) (range 40)))

(defn- random-block [minutes]
  {:schedule-slots/id             7
   :schedule-slots/block-duration (Duration/ofMinutes minutes)
   :schedule-slots/tail-mode      "filler"
   :schedule-slots/tail-filler-id 42
   :schedule-slots/playback-order "random"})

(defn- content-ids [events]
  (map :media-item-id (filter #(< (:media-item-id %) 500) events)))

(defn- run-blocks
  "Emits `n` consecutive blocks of `slot`, threading the cursor and the
   content-airings atom as build! does. Returns every block's events."
  [slot n opts]
  (with-redefs [media-db/resolve-playable-by-channel-tag (fn [_ _] spectrum-pool)
                filler-db/get-filler-preset              (fn [_ _] tail-preset)
                filler-db/load-filler-items              (fn [_ _] (filler-pool))]
    (let [opts (merge {:pack-filler?         true
                       :content-airings-atom (atom {})}
                      opts)]
      (loop [i 0, cur (cursor/init t0), out []]
        (if (= i n)
          out
          (let [[evs cur'] (core/emit-block nil cur slot channel 1 opts)]
            (recur (inc i) cur' (conj out evs))))))))

(deftest trailer-no-longer-fills-every-gap
  (testing "30-minute random blocks of 22-minute episodes leave 8-minute gaps"
    (let [blocks   (run-blocks (random-block 30) 100 {})
          trailers (count (filter #{trailer-id} (mapcat content-ids blocks)))]
      ;; Before the fix every block was: episode, trailer, trailer, trailer,
      ;; trailer (400 airings). Now it gets a few tries per gap, out of 51
      ;; items, and the repeat window (capped at ~9h for this pool) spaces it.
      (is (<= trailers 6) (str "trailer aired " trailers " times in 100 blocks"))
      (is (every? #(<= (count (filter #{trailer-id} (content-ids %))) 1) blocks)
          "never twice in one block"))))

(deftest blocks-stay-contiguous-and-open-on-content
  (let [slot   (random-block 30)
        blocks (run-blocks slot 20 {})]
    (doseq [[i evs] (map-indexed vector blocks)]
      (let [start (t/add-duration t0 (Duration/ofMinutes (* 30 i)))
            end   (t/add-duration start (Duration/ofMinutes 30))]
        (is (= start (:start-at (first evs))))
        (is (< (:media-item-id (first evs)) 500) "the block opens on content")
        (is (every? (fn [[a b]] (= (:finish-at a) (:start-at b)))
                    (partition 2 1 evs))
            "events are back to back")
        (is (not (.isAfter ^Instant (:finish-at (last evs)) end)) "stays inside the block")))))

(deftest filler-is-scattered-between-programs
  (testing "a 3-hour block spreads its leftover filler over several breaks"
    (let [blocks (run-blocks (random-block 180) 10 {})
          ;; count filler runs that sit between two programs
          inner-breaks (fn [evs]
                         (->> (partition-by #(< (:media-item-id %) 500) evs)
                              (drop 1) (butlast)
                              (filter #(>= (:media-item-id (first %)) 500))
                              count))]
      (is (some #(pos? (inner-breaks %)) blocks)
          "some filler lands between programs, not only at the end"))))

(deftest repeat-window-blocks-recent-items-and-falls-back-to-filler
  (testing "every item aired an hour ago: the block is all filler"
    (let [aired (into {} (map (fn [it] [(:media-items/id it)
                                        [(.minusSeconds t0 3600)]])
                              spectrum-pool))
          [evs] (run-blocks (random-block 30) 1
                            {:content-airings-atom (atom aired)
                             :content-repeat-window (t/hours->duration 48)})]
      (is (seq evs))
      (is (empty? (content-ids evs)) "nothing on cooldown is picked")))
  (testing "picks are recorded so later blocks in the build keep their distance"
    (let [a      (atom {})
          blocks (run-blocks (random-block 30) 80 {:content-airings-atom a})
          ids    (mapcat content-ids blocks)
          ;; 51 items, 1102 minutes in all: the 48h default is capped at 551 min
          window (core/effective-repeat-window core/default-content-repeat-window
                                               spectrum-pool)]
      (is (= (set ids) (set (keys @a))))
      (is (seq ids))
      (doseq [[id starts] @a
              [x y] (partition 2 1 (sort starts))]
        (is (>= (.toMinutes (Duration/between ^Instant x ^Instant y)) (.toMinutes window))
            (str "item " id " repeated too soon"))))))

(deftest repeat-window-is-capped-for-small-pools
  (let [pool (mapv #(item % 22) (range 5))]          ; 110 minutes in all
    (is (= (Duration/ofMinutes 55)
           (core/effective-repeat-window (t/hours->duration 48) pool)))
    (is (= (t/hours->duration 1)
           (core/effective-repeat-window (t/hours->duration 1) spectrum-pool)))))

(deftest first-item-searches-the-whole-pool
  (testing "a block isn't left all filler just because the first draws are too long"
    (let [pool (conj (mapv #(item % 45) (range 1 30)) (item 100 22))
          e    (enum/make-enumerator pool :random {:seed 3})
          plan (core/plan-random-content e t0 (t/add-duration t0 (Duration/ofMinutes 30)) {})]
      (is (= [100] (map :media-items/id (:items plan)))))))

(deftest misses-stop-the-plan
  (testing "after the first pick, a few draws that don't fit end it"
    (let [;; one 22-min item that fits, then many 25-min items that don't
          pool (into [(item 1 22)] (map #(item % 25) (range 2 40)))
          e    (assoc (enum/make-enumerator pool :chronological {}) :index 0)
          plan (core/plan-random-content e t0 (t/add-duration t0 (Duration/ofMinutes 30))
                                         {:max-misses 3})]
      (is (= [1] (map :media-items/id (:items plan))))
      (is (= 4 (:index (:enumerator plan))) "one pick and three misses"))))

(deftest scatter-keeps-every-event-and-the-total-length
  (let [mk      (fn [id from mins] {:media-item-id id :start-at from
                                    :finish-at (t/add-duration from (Duration/ofMinutes mins))})
        content [(mk 1 t0 20) (mk 2 t0 20) (mk 3 t0 20)]
        filler  (mapv #(mk (+ 500 %) t0 2) (range 6))
        out     (core/scatter-filler t0 content [] filler 12345)]
    (is (= 9 (count out)))
    (is (= [1 2 3] (content-ids out)) "programs keep their order")
    (is (= 1 (:media-item-id (first out))))
    (is (= (t/add-duration t0 (Duration/ofMinutes 72)) (:finish-at (last out))))))

;; ---------------------------------------------------------------------------
;; Channels with no filler presets (every channel Tunarr Scheduler syncs)
;; ---------------------------------------------------------------------------

(defn- preset-less-block [minutes]
  {:schedule-slots/id             8
   :schedule-slots/block-duration (Duration/ofMinutes minutes)
   :schedule-slots/tail-mode      "filler"
   :schedule-slots/playback-order "random"})

(deftest filler-is-scattered-without-presets
  (testing "with no presets, Grout fills the leftover as tail filler, which is
            scattered; post-roll no longer swallows the whole remainder"
    (with-redefs [media-db/resolve-playable-by-channel-tag (fn [_ _] spectrum-pool)
                  filler-db/get-filler-preset              (fn [_ _] nil)
                  grout/enabled?                           (fn [_] true)
                  grout-source/grout-filler-items          (fn [& _] (filler-pool))]
      (let [opts   {:pack-filler? true :content-airings-atom (atom {}) :grout {}}
            blocks (loop [i 0, cur (cursor/init t0), out []]
                     (if (= i 10)
                       out
                       (let [[evs cur'] (core/emit-block nil cur (preset-less-block 180)
                                                         channel 1 opts)]
                         (recur (inc i) cur' (conj out evs)))))
            kinds  (set (map #(str (:kind %)) (mapcat identity blocks)))
            inner  (fn [evs]
                     (->> (partition-by #(< (:media-item-id %) 500) evs)
                          (drop 1) (butlast)
                          (filter #(>= (:media-item-id (first %)) 500))
                          count))]
        (is (not (contains? kinds "post")) "no post-roll without a post preset")
        (is (some #(pos? (inner %)) blocks)
            "some filler lands between programs, not only at the end")))))

(deftest misses-lead-to-a-search-not-filler
  (testing "a mixed pool: three movies drawn in a row after an episode no longer
            leave the rest of a two-hour block to filler"
    (let [;; drawn in this order: one episode, then movies, then more episodes
          pool (-> [(item 1 22)]
                   (into (map #(item % 100) (range 2 8)))
                   (into (map #(item % 22) (range 8 12))))
          e    (enum/make-enumerator pool :chronological {})
          plan (core/plan-random-content e t0 (t/add-duration t0 (Duration/ofMinutes 120))
                                         {:max-misses 3 :window (t/hours->duration 48)})]
      (is (= 5 (count (:items plan))) "five 22-minute episodes fill 110 of 120 minutes")
      (is (= (count (set (map :media-items/id (:items plan)))) (count (:items plan)))
          "the search never picks an item already placed (cooldown)"))))
