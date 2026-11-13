(ns pseudovision.scheduling.hour-strip-test
  "A one-hour strip of one sitcom airs as many episodes as fit, not one
   episode and forty minutes of filler (how the retired DailySlots push laid
   out a slot: one item per slot)."
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.scheduling.core :as core]
            [pseudovision.scheduling.cursor :as cursor]
            [pseudovision.db.filler :as filler-db]
            [pseudovision.db.media :as media-db])
  (:import [java.time Duration Instant]))

(def ^:private t0 (Instant/parse "2026-11-13T20:00:00Z"))

(defn- episodes [minutes n]
  (mapv (fn [i] {:media-items/id (inc i) :media-versions/duration (Duration/ofMinutes minutes)})
        (range n)))

(def ^:private hour-strip
  {:schedule-slots/id             1
   :schedule-slots/block-duration (Duration/ofHours 1)
   :schedule-slots/tail-mode      "filler"
   :schedule-slots/playback-order "chronological"})

(defn- content-in-hour [minutes]
  (with-redefs [media-db/resolve-playable-by-channel-tag (fn [_ _] (episodes minutes 30))
                filler-db/get-filler-preset              (fn [_ _] nil)]
    (let [[evs cur] (core/emit-block nil (cursor/init t0) hour-strip
                                     {:channels/id 1 :channels/slug "spectrum"} 1 {})]
      [(mapv :media-item-id evs) (:next-start cur)])))

(deftest hour-strip-packs-every-episode-that-fits
  (testing "22-minute episodes: two per hour, in order"
    (let [[ids end] (content-in-hour 22)]
      (is (= [1 2] ids))
      (is (= (.plusSeconds t0 3600) end))))
  (testing "20-minute episodes: three per hour"
    (is (= [1 2 3] (first (content-in-hour 20))))))

(defn- show-episodes [minutes-list]
  (mapv (fn [i m] {:media-items/id (inc i) :_top-id 500
                   :media-versions/duration (Duration/ofMinutes m)})
        (range) minutes-list))

(defn- run-hours
  "Emits `n` one-hour blocks of `hour-strip` over `pool`, threading the cursor.
   Returns the content ids aired, block by block."
  [pool n]
  (with-redefs [media-db/resolve-playable-by-channel-tag (fn [_ _] pool)
                filler-db/get-filler-preset              (fn [_ _] nil)]
    (loop [i 0, cur (cursor/init t0), out []]
      (if (= i n)
        out
        (let [[evs cur'] (core/emit-block nil cur hour-strip
                                          {:channels/id 1 :channels/slug "spectrum"} 1 {})]
          (recur (inc i) cur' (conj out (mapv :media-item-id evs))))))))

(deftest series-strip-never-airs-out-of-order
  (testing "a double-length episode that doesn't fit what's left of the hour
            leads the next hour instead of being skipped"
    (let [pool (show-episodes [22 44 22 22])]
      (is (= [[1] [2] [3 4]] (run-hours pool 3))))))

(deftest series-strip-passes-over-an-episode-longer-than-the-strip
  (testing "an episode that can never fit the strip doesn't block it forever"
    (let [pool (show-episodes [22 75 22 22])]
      (is (= [[1 3] [4 1]] (run-hours pool 2))))))
