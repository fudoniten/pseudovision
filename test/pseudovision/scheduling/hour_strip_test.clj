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
