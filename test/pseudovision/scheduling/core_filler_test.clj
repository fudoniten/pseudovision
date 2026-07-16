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
          ;; Pass nil for channel: with no content source and no channel the
          ;; fallback branch in load-items returns [] (and logs a warn), so
          ;; the test still validates the empty guard.
          [events cursor'] (core/emit-once nil cur slot nil 1 {})]
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

(deftest emit-flood-pads-overflow-remainder-with-fallback-filler
  (testing "when the next item would overflow flood-end, the leftover gap is
            padded with fallback filler instead of being silently dropped"
    ;; 30m episodes, flood window is only 45m long: one episode fits, a second
    ;; would overflow by 15m — that 15m must be filled, not left as a hole.
    (let [flood-end (t/add-duration t0 (Duration/ofMinutes 45))
          slot      {:schedule-slots/id 1
                     :schedule-slots/media-item-id 1
                     :schedule-slots/playback-order "chronological"}
          cur       (make-cursor t0)
          opts      {:flood-end flood-end :seed 0}]
      (with-redefs [media-db/get-media-item     (fn [_ _] content-item)
                    filler-db/get-filler-preset  (fn [_ _] fallback-filler-preset)
                    filler-db/load-filler-items  (fn [_ _] (filler-items 30))]
        (let [[events cursor'] (core/emit-flood nil cur slot channel 1 opts)]
          (is (= 1 (count (filter #(= 1 (:media-item-id %)) events)))
              "the one episode that fit is emitted")
          (is (pos? (count (filter #(= 99 (:media-item-id %)) events)))
              "filler events pad the remainder")
          (is (= flood-end (:finish-at (last events)))
              "events cover the gap all the way to flood-end")
          (is (= flood-end (:next-start cursor')) "cursor advances to flood-end"))))))

(deftest emit-flood-overflow-remainder-empty-without-fallback-configured
  (testing "overflow with no fallback filler configured: no crash, cursor still advances"
    (let [flood-end (t/add-duration t0 (Duration/ofMinutes 45))
          slot      {:schedule-slots/id 1
                     :schedule-slots/media-item-id 1
                     :schedule-slots/playback-order "chronological"}
          cur       (make-cursor t0)
          opts      {:flood-end flood-end :seed 0}]
      (with-redefs [media-db/get-media-item (fn [_ _] content-item)]
        (let [[events cursor'] (core/emit-flood nil cur slot {} 1 opts)]
          (is (= 1 (count events)) "only the one episode that fit is emitted")
          (is (= flood-end (:next-start cursor')) "cursor still advances to flood-end"))))))

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
;; Unplayable (zero / unknown duration) content — must not stall the build
;;
;; Media that has not been probed yet has a zero duration, and a slot whose
;; item has no media_versions row resolves to a nil duration. The collection
;; enumerators loop forever, so before these items were filtered out the
;; block/flood fill loops would spin without ever advancing the wall clock —
;; a silent infinite loop that hung the rebuild request.
;; ---------------------------------------------------------------------------

(defn- run-with-timeout
  "Runs thunk on a separate thread so that a regression which reintroduces the
   infinite fill loop fails the test (via timeout) instead of hanging the whole
   suite. Returns the thunk's value, or ::timeout."
  [ms thunk]
  (let [f (future (thunk))
        v (deref f ms ::timeout)]
    (when (= v ::timeout) (future-cancel f))
    v))

(deftest emit-block-drops-zero-duration-content
  (testing "a zero-duration (unprobed) content item is unplayable: the block fills with tail filler instead of looping forever"
    (let [zero-item {:media-items/id 1 :media-versions/duration (Duration/ofSeconds 0)}
          slot      (block-slot-with-item 1 dur-1h "filler")
          cur       (make-cursor t0)]
      (with-redefs [media-db/get-media-item     (fn [_ _] zero-item)
                    filler-db/get-filler-preset  (fn [_ _] tail-filler-preset)
                    filler-db/load-filler-items  (fn [_ _] (filler-items 20))]
        (let [result (run-with-timeout 5000 #(core/emit-block nil cur slot channel 1 {}))]
          (is (not= ::timeout result) "emit-block must terminate, not hang")
          (when (vector? result)
            (let [[events cursor'] result]
              (is (every? #(= 99 (:media-item-id %)) events)
                  "zero-duration content is dropped; only filler is emitted")
              (is (= t1 (:next-start cursor')) "cursor advances to block-end"))))))))

(deftest emit-flood-drops-nil-duration-content
  (testing "a content item with no duration is unplayable: the flood window fills with fallback filler instead of looping forever"
    (let [nil-item {:media-items/id 1}            ; no :media-versions/duration at all
          slot     {:schedule-slots/id 1
                    :schedule-slots/media-item-id 1
                    :schedule-slots/playback-order "chronological"}
          cur      (make-cursor t0)
          opts     {:flood-end t2 :seed 0}]
      (with-redefs [media-db/get-media-item     (fn [_ _] nil-item)
                    filler-db/get-filler-preset  (fn [_ _] fallback-filler-preset)
                    filler-db/load-filler-items  (fn [_ _] (filler-items 30))]
        (let [result (run-with-timeout 5000 #(core/emit-flood nil cur slot channel 1 opts))]
          (is (not= ::timeout result) "emit-flood must terminate, not hang")
          (when (vector? result)
            (let [[events cursor'] result]
              (is (every? #(= 99 (:media-item-id %)) events)
                  "nil-duration content is dropped; only filler is emitted")
              (is (= t2 (:next-start cursor')) "cursor advances to flood-end"))))))))

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
  (testing "count-mode pre filler is emitted before the first content item;
            the content loop then packs as many items as fit, with the
            overflowing one trimmed to the block-end (tail-mode none)"
    ;; Setup: 10m pre-roll + 30m content + 30m content (trimmed to 20m) in 1h block
    (let [slot (pre-slot {:schedule-slots/pre-filler-id 50})
          cur  (make-cursor t0)
          block-end t1]   ; t0 + 1h
      (with-redefs [media-db/get-media-item     (fn [_ _] content-item)
                    filler-db/get-filler-preset (fn [_ _] pre-count-preset)
                    filler-db/load-filler-items (fn [_ _] (filler-items 10))]
        (let [[events _] (core/emit-block nil cur slot {} 1 {})
              content    (filter #(= 1  (:media-item-id %)) events)
              filler     (filter #(= 99 (:media-item-id %)) events)]
          (is (= 2 (count filler))  "two count-mode pre-roll items")
          (is (= 2 (count content)) "two content items: one full, one trimmed to fit")
          (is (every? #(.isBefore ^Instant (:start-at %)
                                  ^Instant (:start-at (first content))) filler)
              "every filler starts before the content item")
          (is (= (:finish-at (last filler)) (:start-at (first content)))
              "content begins exactly when pre-roll ends")
          (is (= block-end (:finish-at (last content)))
              "the trimmed content event ends exactly at block-end")
          (is (< (.getSeconds ^Duration
                              (t/duration-between (:start-at (last content))
                                                  (:finish-at (last content))))
                 (.getSeconds ^Duration dur-30m))
              "the second content event is trimmed (shorter than a full episode)"))))))

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
  (testing "mid filler appears between every pair of consecutive content items;
            with looping playback, the content loop fills the block as long
            as items fit, so multiple mid-roll inserts are emitted"
    ;; Setup: 2 content items of 10m + mid-filler of 5m (count=1).
    ;; 1h block accommodates (10m + 5m mid) × 4 = 60m.
    (let [items [{:media-items/id 1 :media-versions/duration (Duration/ofMinutes 10)}
                 {:media-items/id 2 :media-versions/duration (Duration/ofMinutes 10)}]
          slot  {:schedule-slots/id             1
                 :schedule-slots/collection-id  7
                 :schedule-slots/block-duration dur-1h
                 :schedule-slots/tail-mode      "none"
                 :schedule-slots/mid-filler-id  51
                 :schedule-slots/playback-order "chronological"}
          cur   (make-cursor t0)
          block-end t1]   ; t0 + 1h
      (with-redefs [media-db/get-collection     (fn [_ _] {:collections/id 7
                                                           :collections/kind "manual"})
                    col-db/resolve-collection   (fn [_ _] items)
                    filler-db/get-filler-preset (fn [_ _] mid-count-preset)
                    filler-db/load-filler-items (fn [_ _] (filler-items 10))]
        (let [[events _] (core/emit-block nil cur slot {} 1 {})
              content    (filter #(#{1 2} (:media-item-id %)) events)
              filler     (filter #(= 99  (:media-item-id %)) events)]
          (is (= 4 (count content)) "the 1h block holds 4 content items (10m each)")
          (is (= 4 (count filler))  "one mid-roll insert between each content pair")
          (is (= 8 (count events))  "4 content + 4 mid = 8 total")
          ;; Every filler is immediately preceded by a content event (mid-roll)
          (is (every? (fn [f]
                        (some #(= (:finish-at %) (:start-at f)) content))
                      filler)
              "every mid-roll filler is immediately preceded by a content event")
          (is (every? (fn [c]
                        (some #(= (:finish-at c) (:start-at %)) filler))
                      (butlast content))
              "every content event except the last is immediately followed by a mid-roll filler")
          (is (= block-end (:finish-at (last events)))
              "the block is filled exactly to block-end"))))))

(deftest emit-block-packs-two-content-items-into-1h-block-without-filler
  (testing "REGRESSION (live symptom 2026-07-16): a 1h block slot with
            30-min content and no tail/post filler configured packs TWO
            content items.  The pre-fix live build emitted only ONE because
            the older emit-block did not loop the content enumerator after
            the first fit.  The local code packs 30+30=60m exactly; tail
            overflow is empty because no filler is configured."
    (let [slot (pre-slot {})   ; no pre/mid/post/tail filler
          cur  (make-cursor t0)
          block-end t1]        ; t0 + 1h
      (with-redefs [media-db/get-media-item     (fn [_ _] content-item)
                    filler-db/get-filler-preset (fn [_ _] nil)   ; no presets anywhere
                    filler-db/load-filler-items (fn [_ _] [])]
        (let [[events _] (core/emit-block nil cur slot {} 1 {})
              content    (filter #(= 1 (:media-item-id %)) events)]
          (is (= 2 (count content))      "two content events emitted in 1h block")
          (is (= t0 (:start-at (first content))))
          (is (= block-end (:finish-at (last content))) "block fully covered")
          (is (= (.getSeconds ^Duration dur-30m)
                 (.getSeconds ^Duration
                              (t/duration-between (:start-at (first content))
                                                  (:finish-at (first content)))))
              "first content is a full 30m episode")
          (is (= (.getSeconds ^Duration dur-30m)
                 (.getSeconds ^Duration
                              (t/duration-between (:start-at (second content))
                                                  (:finish-at (second content)))))
              "second content is a full 30m episode"))))))

(deftest emit-block-injects-post-roll-after-last-item
  (testing "with looping playback, post-roll never fires because the content
            loop is bounded by the block-end (the empty-items branch is
            unreachable for non-exhausting enumerators).  Here we confirm
            the post-roll preset is NOT consulted when the content loop
            fills the block exactly.  To exercise the empty-items branch,
            see the 'pad-to-end-with-tail-filler' test below."
    (let [slot (pre-slot {:schedule-slots/post-filler-id 52})
          post-called? (atom false)
          cur  (make-cursor t0)]
      (with-redefs [media-db/get-media-item     (fn [_ _] content-item)
                    filler-db/get-filler-preset (fn [_ _]
                                                   (reset! post-called? true)
                                                   post-count-preset)
                    filler-db/load-filler-items (fn [_ _] (filler-items 10))]
        (let [[events _] (core/emit-block nil cur slot {} 1 {})
              content    (filter #(= 1  (:media-item-id %)) events)]
          (is (= 2 (count content))   "two content items packed in 1h block")
          (is (false? @post-called?)  "post-roll preset is NOT consulted")
          (is (empty? (filter #(= 99 (:media-item-id %)) events))
              "no post-roll filler events emitted"))))))

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