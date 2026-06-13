(ns pseudovision.scheduling.core-filler-test
  "Tests for filler wiring in the scheduling engine.
   Exercises the emit-* helpers directly without requiring a full build! stack."
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.scheduling.cursor :as cursor]
            [pseudovision.scheduling.core :as core]
            [pseudovision.db.filler :as filler-db]
            [pseudovision.db.media :as media-db]
            [pseudovision.db.collections :as col-db]
            [pseudovision.util.time :as t])
  (:import [java.time Instant Duration]))

;; ---------------------------------------------------------------------------
;; Shared fixtures
;; ---------------------------------------------------------------------------

(def ^:private t0 (Instant/parse "2026-04-27T20:00:00Z"))
(def ^:private t1 (Instant/parse "2026-04-27T21:00:00Z"))  ; t0 + 1h
(def ^:private t2 (Instant/parse "2026-04-27T22:00:00Z"))  ; t0 + 2h

(def ^:private dur-30m (Duration/ofMinutes 30))
(def ^:private dur-1h  (Duration/ofHours 1))
(def ^:private dur-2h  (Duration/ofHours 2))

(defn- make-cursor [start]
  (cursor/init start))

;; Content item: 30-minute show episode
(def ^:private content-item
  {:media-items/id 1 :media-versions/duration dur-30m})

;; Filler items: 5-minute bumpers, enough to fill any gap in tests
(defn- filler-items [n]
  (repeat n {:media-items/id 99 :media-versions/duration (Duration/ofMinutes 5)}))

(def ^:private tail-filler-preset
  {:filler-presets/id 42 :filler-presets/mode "duration" :filler-presets/role "tail"})

(def ^:private fallback-filler-preset
  {:filler-presets/id 43 :filler-presets/mode "duration" :filler-presets/role "fallback"})

;; Channel with fallback filler configured
(def ^:private channel
  {:channels/id 1 :channels/fallback-filler-id 43})

;; Slot helpers — no content source means load-items returns []
(defn- empty-block-slot [dur tail-mode tail-filler-id]
  {:schedule-slots/id             1
   :schedule-slots/block-duration dur
   :schedule-slots/tail-mode      tail-mode
   :schedule-slots/tail-filler-id tail-filler-id
   :schedule-slots/playback-order "chronological"})

(defn- block-slot-with-item [item-id dur tail-mode]
  {:schedule-slots/id             1
   :schedule-slots/media-item-id  item-id
   :schedule-slots/block-duration dur
   :schedule-slots/tail-mode      tail-mode
   :schedule-slots/tail-filler-id 42
   :schedule-slots/playback-order "chronological"})

;; ---------------------------------------------------------------------------
;; emit-once — empty collection guard
;; ---------------------------------------------------------------------------

(deftest emit-once-skips-slot-when-no-items
  (testing "returns empty events and unchanged cursor when collection is empty"
    (let [slot {:schedule-slots/id 1}   ; no media-item-id or collection-id
          cur  (make-cursor t0)
          [events cursor'] (core/emit-once nil cur slot 1 {})]
      (is (= [] events) "no events emitted")
      (is (= t0 (:next-start cursor')) "cursor time must not advance"))))

;; ---------------------------------------------------------------------------
;; emit-count — empty collection guard
;; ---------------------------------------------------------------------------

(deftest emit-count-skips-slot-when-no-items
  (testing "returns empty events and unchanged cursor when collection is empty"
    (let [slot {:schedule-slots/id 1 :schedule-slots/item-count 5}
          cur  (make-cursor t0)
          [events cursor'] (core/emit-count nil cur slot {} 1 {})]
      (is (= [] events) "no events emitted")
      (is (= t0 (:next-start cursor')) "cursor time must not advance"))))

;; ---------------------------------------------------------------------------
;; emit-block — tail filler when content is absent
;; ---------------------------------------------------------------------------

(deftest emit-block-fills-entire-block-with-tail-filler-when-no-content
  (testing "when content is empty, fills the whole block with tail filler"
    ;; Slot: no content, 1h block, tail_mode=filler
    (let [slot (empty-block-slot dur-1h "filler" 42)
          cur  (make-cursor t0)]
      (with-redefs [filler-db/get-filler-preset (fn [_ _] tail-filler-preset)
                    filler-db/load-filler-items  (fn [_ _] (filler-items 20))]
        (let [[events cursor'] (core/emit-block nil cur slot channel 1 {})]
          (is (pos? (count events))          "filler events should be emitted")
          (is (= t0 (:start-at (first events))) "filler starts at block start")
          (is (every? #(= 99 (:media-item-id %)) events) "all events are filler")
          (is (= t1 (:next-start cursor'))   "cursor advances to block-end"))))))

(deftest emit-block-skips-tail-filler-when-no-preset-configured
  (testing "when no tail filler is configured, block ends at boundary with a gap"
    (let [slot (empty-block-slot dur-1h "filler" nil)   ; tail-filler-id = nil
          cur  (make-cursor t0)
          [events cursor'] (core/emit-block nil cur slot {} 1 {})]
      (is (= [] events) "no events when filler not configured")
      (is (= t1 (:next-start cursor')) "cursor still advances to block-end"))))

(deftest emit-block-tail-none-leaves-no-filler
  (testing "tail_mode=none: overflow item is trimmed, no filler preset called"
    ;; Single 2h item in a 1h block with tail_mode=none → trimmed event, no filler
    (let [long-item {:media-items/id 1 :media-versions/duration dur-2h}
          slot (assoc (block-slot-with-item 1 dur-1h "none")
                      :schedule-slots/tail-filler-id nil)
          cur  (make-cursor t0)
          filler-called? (atom false)]
      (with-redefs [media-db/get-media-item     (fn [_ _] long-item)
                    filler-db/get-filler-preset  (fn [_ _]
                                                   (reset! filler-called? true)
                                                   tail-filler-preset)]
        (let [[events cursor'] (core/emit-block nil cur slot {} 1 {})]
          (is (false? @filler-called?) "filler preset should not be consulted")
          (is (= 1 (count events))     "exactly one (trimmed) content event")
          (is (= t0 (:start-at  (first events))))
          (is (= t1 (:finish-at (first events))) "trimmed to block-end")
          (is (= t1 (:next-start cursor'))))))))

;; ---------------------------------------------------------------------------
;; emit-block — tail filler on overflow
;; ---------------------------------------------------------------------------

(deftest emit-block-fills-tail-on-overflow-when-filler-configured
  (testing "tail_mode=filler: fills from block start to block-end when first item overflows"
    ;; 2h item in a 1h block with tail_mode=filler
    ;; First item overflows immediately → apply-filler fills t0..t1
    (let [long-item {:media-items/id 1 :media-versions/duration dur-2h}
          slot      (block-slot-with-item 1 dur-1h "filler")
          cur       (make-cursor t0)]
      (with-redefs [media-db/get-media-item     (fn [_ _] long-item)
                    filler-db/get-filler-preset  (fn [_ _] tail-filler-preset)
                    filler-db/load-filler-items  (fn [_ _] (filler-items 20))]
        (let [[events cursor'] (core/emit-block nil cur slot channel 1 {})]
          (is (pos? (count events))           "filler events emitted")
          (is (every? #(= 99 (:media-item-id %)) events) "all events are filler")
          (is (= t0 (:start-at (first events))))
          (is (= t1 (:next-start cursor'))    "cursor at block-end"))))))

;; ---------------------------------------------------------------------------
;; emit-flood — fallback filler when content is absent
;; ---------------------------------------------------------------------------

(deftest emit-flood-fills-entire-window-with-fallback-when-no-content
  (testing "when no content, fills flood window entirely with fallback filler"
    ;; Slot with no content, flood-end = t0+2h, fallback filler = 5m items
    (let [slot {:schedule-slots/id 1 :schedule-slots/playback-order "chronological"}
          cur  (make-cursor t0)
          opts {:flood-end t2 :seed 0}]
      (with-redefs [filler-db/get-filler-preset (fn [_ _] fallback-filler-preset)
                    filler-db/load-filler-items  (fn [_ _] (filler-items 30))]
        (let [[events cursor'] (core/emit-flood nil cur slot channel 1 opts)]
          (is (pos? (count events))           "filler events emitted")
          (is (every? #(= 99 (:media-item-id %)) events) "all events are filler")
          (is (= t0 (:start-at  (first events))))
          (is (= t2 (:next-start cursor'))    "cursor at flood-end"))))))

(deftest emit-flood-uses-no-filler-when-fallback-unconfigured
  (testing "when no fallback filler configured and no content, returns empty events"
    (let [slot {:schedule-slots/id 1}
          cur  (make-cursor t0)
          opts {:flood-end t2}]
      (let [[events cursor'] (core/emit-flood nil cur slot {} 1 opts)]
        (is (= [] events) "no events when neither content nor filler")
        (is (= t2 (:next-start cursor')) "cursor still advances to flood-end")))))

;; ---------------------------------------------------------------------------
;; emit-block — filler enumerator state persisted in cursor
;; ---------------------------------------------------------------------------

(deftest emit-block-persists-filler-enumerator-state
  (testing "filler enumerator position is saved back to the cursor after fill"
    (let [slot (empty-block-slot (Duration/ofMinutes 10) "filler" 42)
          cur  (make-cursor t0)]
      (with-redefs [filler-db/get-filler-preset (fn [_ _] tail-filler-preset)
                    filler-db/load-filler-items  (fn [_ _] (filler-items 20))]
        (let [[_ cur1] (core/emit-block nil cur slot channel 1 {})]
          (is (contains? (:enumerator-states cur1) "filler:tail:42")
              "cursor should have saved enumerator state under the filler preset key"))))))

;; ---------------------------------------------------------------------------
;; time-of-day-on-same-day
;; ---------------------------------------------------------------------------

(deftest time-of-day-on-same-day-returns-future-tod
  (testing "returns the TOD fire-time when it is still ahead today"
    ;; t0 = 20:00; TOD = 21:00 → 21:00 same day
    (let [result (t/time-of-day-on-same-day (Duration/ofHours 21) t0 "UTC")]
      (is (= t1 result)))))

(deftest time-of-day-on-same-day-returns-nil-when-past
  (testing "returns nil when the TOD has already passed today"
    ;; t0 = 20:00; TOD = 19:00 → nil
    (let [result (t/time-of-day-on-same-day (Duration/ofHours 19) t0 "UTC")]
      (is (nil? result)))))

(deftest time-of-day-on-same-day-returns-nil-at-exact-time
  (testing "returns nil when inst is exactly at the TOD (not strictly after)"
    ;; t0 = 20:00; TOD = 20:00 → nil (uses isAfter, not >=)
    (let [result (t/time-of-day-on-same-day (Duration/ofHours 20) t0 "UTC")]
      (is (nil? result)))))

(deftest time-of-day-on-same-day-works-near-midnight
  (testing "correctly handles TOD at 23:30 with inst at 23:00"
    (let [inst   (Instant/parse "2026-04-27T23:00:00Z")
          ;; 23h30m = 1410 minutes from midnight
          tod    (Duration/ofMinutes (+ (* 23 60) 30))
          result (t/time-of-day-on-same-day tod inst "UTC")]
      (is (= (Instant/parse "2026-04-27T23:30:00Z") result)))))

;; ---------------------------------------------------------------------------
;; emit-block / emit-count — pre / mid / post-roll filler injection
;; ---------------------------------------------------------------------------

(def ^:private pre-count-preset
  {:filler-presets/id 50 :filler-presets/mode "count"
   :filler-presets/count 2 :filler-presets/role "pre"})

(def ^:private mid-count-preset
  {:filler-presets/id 51 :filler-presets/mode "count"
   :filler-presets/count 1 :filler-presets/role "mid"})

(def ^:private post-count-preset
  {:filler-presets/id 52 :filler-presets/mode "count"
   :filler-presets/count 2 :filler-presets/role "post"})

(defn- pre-slot [extra]
  (merge {:schedule-slots/id             1
          :schedule-slots/media-item-id  1
          :schedule-slots/block-duration dur-1h
          :schedule-slots/tail-mode      "none"
          :schedule-slots/playback-order "chronological"}
         extra))

(deftest emit-block-injects-pre-roll-before-first-item
  (testing "count-mode pre filler is emitted before the first content item"
    (let [slot (pre-slot {:schedule-slots/pre-filler-id 50})
          cur  (make-cursor t0)]
      (with-redefs [media-db/get-media-item     (fn [_ _] content-item)
                    filler-db/get-filler-preset (fn [_ _] pre-count-preset)
                    filler-db/load-filler-items (fn [_ _] (filler-items 10))]
        (let [[events _] (core/emit-block nil cur slot {} 1 {})
              content    (filter #(= 1  (:media-item-id %)) events)
              filler     (filter #(= 99 (:media-item-id %)) events)]
          (is (= 2 (count filler))  "two count-mode pre-roll items")
          (is (= 1 (count content)) "one content item")
          (is (every? #(.isBefore ^Instant (:start-at %)
                                  ^Instant (:start-at (first content))) filler)
              "every filler starts before the content item")
          (is (= (:finish-at (last filler)) (:start-at (first content)))
              "content begins exactly when pre-roll ends"))))))

(deftest emit-block-pre-roll-duration-mode-respects-preset-duration
  (testing "duration-mode pre filler fills only its preset duration, not the block"
    (let [preset {:filler-presets/id 50 :filler-presets/mode "duration"
                  :filler-presets/duration (Duration/ofMinutes 10)
                  :filler-presets/role "pre"}
          slot   (pre-slot {:schedule-slots/pre-filler-id 50})
          cur    (make-cursor t0)]
      (with-redefs [media-db/get-media-item     (fn [_ _] content-item)
                    filler-db/get-filler-preset (fn [_ _] preset)
                    filler-db/load-filler-items (fn [_ _] (filler-items 20))]
        (let [[events _] (core/emit-block nil cur slot {} 1 {})
              content    (filter #(= 1  (:media-item-id %)) events)
              filler     (filter #(= 99 (:media-item-id %)) events)]
          ;; 10 minutes of 5-minute filler = exactly two items.
          (is (= 2 (count filler)) "duration-mode pre fills 10m with two 5-min items")
          (is (= (Instant/parse "2026-04-27T20:10:00Z") (:start-at (first content)))
              "content starts after exactly 10 minutes of pre-roll"))))))

(deftest emit-block-injects-mid-roll-between-items
  (testing "mid filler appears between consecutive content items, not after the last"
    (let [items [{:media-items/id 1 :media-versions/duration (Duration/ofMinutes 10)}
                 {:media-items/id 2 :media-versions/duration (Duration/ofMinutes 10)}]
          slot  {:schedule-slots/id             1
                 :schedule-slots/collection-id  7
                 :schedule-slots/block-duration dur-1h
                 :schedule-slots/tail-mode      "none"
                 :schedule-slots/mid-filler-id  51
                 :schedule-slots/playback-order "chronological"}
          cur   (make-cursor t0)]
      (with-redefs [media-db/get-collection     (fn [_ _] {:collections/id 7
                                                           :collections/kind "manual"})
                    col-db/resolve-collection   (fn [_ _] items)
                    filler-db/get-filler-preset (fn [_ _] mid-count-preset)
                    filler-db/load-filler-items (fn [_ _] (filler-items 10))]
        (let [[events _] (core/emit-block nil cur slot {} 1 {})
              content    (filter #(#{1 2} (:media-item-id %)) events)
              filler     (filter #(= 99  (:media-item-id %)) events)]
          (is (= 2 (count content)) "both content items emitted")
          (is (= 1 (count filler))  "exactly one mid-roll insert (between the two items)")
          (is (= (:finish-at (first content)) (:start-at (first filler)))
              "mid filler starts when the first item ends")
          (is (= (:finish-at (first filler)) (:start-at (second content)))
              "second item starts when mid filler ends"))))))

(deftest emit-block-injects-post-roll-after-last-item
  (testing "post filler follows the last content item"
    (let [slot (pre-slot {:schedule-slots/post-filler-id 52})
          cur  (make-cursor t0)]
      (with-redefs [media-db/get-media-item     (fn [_ _] content-item)
                    filler-db/get-filler-preset (fn [_ _] post-count-preset)
                    filler-db/load-filler-items (fn [_ _] (filler-items 10))]
        (let [[events _] (core/emit-block nil cur slot {} 1 {})
              content    (filter #(= 1  (:media-item-id %)) events)
              filler     (filter #(= 99 (:media-item-id %)) events)]
          (is (= 1 (count content)) "one content item")
          (is (= 2 (count filler))  "two count-mode post-roll items")
          (is (every? #(not (.isBefore ^Instant (:start-at %)
                                       ^Instant (:finish-at (first content)))) filler)
              "every post-roll item starts at or after the content finishes"))))))

(deftest emit-count-injects-pre-and-post-roll
  (testing "emit-count wraps its N items with pre- and post-roll filler"
    (let [slot {:schedule-slots/id             1
                :schedule-slots/media-item-id  1
                :schedule-slots/item-count     1
                :schedule-slots/pre-filler-id  50
                :schedule-slots/post-filler-id 52
                :schedule-slots/playback-order "chronological"}
          cur  (make-cursor t0)]
      (with-redefs [media-db/get-media-item     (fn [_ _] content-item)
                    ;; both roles resolve to a count preset (count = 2)
                    filler-db/get-filler-preset (fn [_ _] pre-count-preset)
                    filler-db/load-filler-items (fn [_ _] (filler-items 10))]
        (let [[events _] (core/emit-count nil cur slot {} 1 {})
              content    (filter #(= 1  (:media-item-id %)) events)
              filler     (filter #(= 99 (:media-item-id %)) events)]
          (is (= 1 (count content)) "one content item")
          (is (= 4 (count filler))  "two pre + two post filler items")
          (is (.isBefore ^Instant (:start-at (first events))
                         ^Instant (:start-at (first content)))
              "timeline opens with pre-roll filler")
          (is (= 99 (:media-item-id (last events)))
              "timeline closes with post-roll filler"))))))