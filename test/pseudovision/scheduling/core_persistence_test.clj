(ns pseudovision.scheduling.core-persistence-test
  "Guards the build->persist contract: every event the engine hands to
   bulk-insert-events! must share one column set so a single multi-row INSERT
   never NULLs a NOT NULL column (guide_group) for a subset of rows."
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.scheduling.cursor :as cursor]
            [pseudovision.scheduling.core   :as core]
            [pseudovision.db.playouts        :as playout-db]
            [pseudovision.db.filler          :as filler-db]
            [pseudovision.db.media           :as media-db])
  (:import [java.time Instant Duration]))

(def ^:private t0 (Instant/parse "2026-04-27T20:00:00Z"))

;; 30-minute content item, played inside a 1-hour block.
(def ^:private content-item
  {:media-items/id 1 :media-versions/duration (Duration/ofMinutes 30)})

;; 5-minute filler bumpers — enough to pad the remaining half hour.
(defn- filler-items [n]
  (repeat n {:media-items/id 99 :media-versions/duration (Duration/ofMinutes 5)}))

(def ^:private tail-preset
  {:filler-presets/id 42 :filler-presets/mode "duration" :filler-presets/role "tail"})

(def ^:private block-slot
  {:schedule-slots/id             7
   :schedule-slots/media-item-id  1
   :schedule-slots/block-duration (Duration/ofHours 1)
   :schedule-slots/tail-mode      "filler"
   :schedule-slots/tail-filler-id 42
   :schedule-slots/playback-order "chronological"})

(deftest content-and-filler-events-share-one-column-set
  (testing "a block that emits content then tail filler yields rows that are
            uniform and insert-safe (non-nil guide_group on every row)"
    (with-redefs [media-db/get-media-item     (fn [_ _] content-item)
                  filler-db/get-filler-preset (fn [_ _] tail-preset)
                  filler-db/load-filler-items (fn [_ _] (filler-items 20))]
      (let [cur            (cursor/init t0)
            [events _]     (core/emit-block nil cur block-slot {} 5 {})
            content        (filter #(= 1  (:media-item-id %)) events)
            filler         (filter #(= 99 (:media-item-id %)) events)]
        (is (seq content) "block emits the content item")
        (is (seq filler)  "remaining block time is padded with filler")
        (is (every? #(some? (:guide-group %)) events)
            "every event — content AND filler — carries a non-nil guide-group")
        (is (every? #(contains? % :slot-id) events)
            "every event carries :slot-id (matching the content column set)")
        (is (apply = (map (comp set keys) events))
            "all events share an identical key set")))))

(deftest uniform-rows-normalises-heterogeneous-batches
  (testing "uniform-rows fills absent keys with nil and stabilises ordering"
    (let [content {:playout-id 1 :media-item-id 10 :guide-group 3
                   :slot-id 7 :is-manual false}
          filler  {:playout-id 1 :media-item-id 99 :is-manual false}
          [r0 r1] (playout-db/uniform-rows [content filler])]
      (is (= (keys r0) (keys r1))
          "both rows expose the same columns in the same order")
      (is (= 3 (:guide-group r0))      "present values are preserved")
      (is (contains? r1 :guide-group)  "absent column is materialised")
      (is (nil? (:guide-group r1))     "absent column is nil-filled"))))
