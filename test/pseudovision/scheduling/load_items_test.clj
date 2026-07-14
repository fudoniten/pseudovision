(ns pseudovision.scheduling.load-items-test
  "Tests for the channel-catalog fallback in `pseudovision.scheduling.core`'s
  private `load-items`.  The function is private, so the tests are written
  against its public callers (`emit-once`, `emit-block`) and the private
  resolution path is exercised via `with-redefs` on
  `media-db/resolve-playable-by-channel-tag`.

  The pre-fix code had a `:else []` branch in `load-items` that silently
  returned an empty vector for any slot with neither `collection-id` nor
  `media-item-id`.  The 50 active \"(auto)\" schedule slots in the live
  cluster all hit this branch and every build returned `events-generated:
  0` for them.  The fix is the new `else` arm that resolves the slot
  through `media-db/resolve-playable-by-channel-tag` using the channel's
  `channel:<name>` dimension tag — see pitfall-51 in
  `pseudovision-ecosystem-development`."
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.scheduling.cursor :as cursor]
            [pseudovision.scheduling.core   :as core]
            [pseudovision.db.media          :as media-db])
  (:import [java.time Instant Duration]))

;; ---------------------------------------------------------------------------
;; Shared fixtures
;; ---------------------------------------------------------------------------

(def ^:private t0 (Instant/parse "2026-04-27T20:00:00Z"))
(def ^:private t1 (Instant/parse "2026-04-27T20:30:00Z"))  ; t0 + 30m

(def ^:private dur-30m (Duration/ofMinutes 30))

;; A 30-minute episode that the channel-catalog resolver will return when
;; it's set up to \"see\" the channel tag.
(def ^:private catalog-item
  {:media-items/id          7
   :media-versions/duration dur-30m
   :_top-id                 7})

;; A channel with a name — the slot will derive `channel:<name>` from this.
(def ^:private test-channel
  {:channels/id 1 :channels/name "testchannel"})

;; ---------------------------------------------------------------------------
;; emit-once: channel-catalog fallback (the new path)
;; ---------------------------------------------------------------------------

(deftest emit-once-resolves-via-channel-catalog-when-slot-has-no-source
  (testing "a slot with neither collection-id nor media-item-id falls back
            to the channel's catalog via `channel:<name>`, emitting one event
            from the resolved items"
    (let [slot {:schedule-slots/id             100
                ;; no collection-id, no media-item-id
                :schedule-slots/playback-order "chronological"
                :schedule-slots/anchor         "sequential"
                :schedule-slots/fill-mode      "once"}
          cur  (cursor/init t0)]
      (with-redefs [media-db/resolve-playable-by-channel-tag
                    (fn [_ds channel-tag]
                      ;; Verify the resolver is called with the right tag
                      ;; (the channel name from the channel map).
                      (is (= "channel:testchannel" channel-tag)
                          "channel-tag derived from channel name")
                      [catalog-item])]
        (let [[events cursor'] (core/emit-once nil cur slot test-channel 1 {})]
          (is (= 1 (count events)) "one event emitted from channel-catalog items")
          (is (= 7 (:media-item-id (first events)))
              "event carries the resolved media-item id")
          (is (= t0 (:start-at (first events)))
              "event starts at cursor")
          (is (= t1 (:next-start cursor'))
              "cursor advanced by the item's duration"))))))

(deftest emit-once-falls-back-to-empty-when-no-channel-given
  (testing "the channel-catalog branch is gated on a non-nil channel.  When
            process-slot calls emit-once with a nil channel, load-items warns
            and returns [], preserving the pre-fix behaviour for callers that
            don't have a channel in scope (e.g. tests, dev scripts)."
    (let [slot {:schedule-slots/id             101
                :schedule-slots/playback-order "chronological"
                :schedule-slots/anchor         "sequential"
                :schedule-slots/fill-mode      "once"}
          cur  (cursor/init t0)
          channel-tag-called? (atom false)]
      (with-redefs [media-db/resolve-playable-by-channel-tag
                    (fn [_ _] (reset! channel-tag-called? true) [])]
        (let [[events cursor'] (core/emit-once nil cur slot nil 1 {})]
          (is (= [] events) "no events when channel is nil")
          (is (false? @channel-tag-called?)
              "channel-catalog resolver not invoked without a channel")
          (is (= t0 (:next-start cursor')) "cursor time must not advance"))))))

;; ---------------------------------------------------------------------------
;; emit-block: channel-catalog fallback (the live-cluster repro)
;; ---------------------------------------------------------------------------

(deftest emit-block-emits-content-via-channel-catalog
  (testing "reproduces the live-cluster repro: a fixed-anchor block slot
            with neither collection-id nor media-item-id used to emit zero
            events.  With the fix it resolves the slot through the channel
            catalog and emits content events that fit the block duration."
    (let [slot {:schedule-slots/id             200
                ;; no collection-id, no media-item-id
                :schedule-slots/anchor         "fixed"
                :schedule-slots/fill-mode      "block"
                :schedule-slots/block-duration dur-30m
                :schedule-slots/playback-order "chronological"
                :schedule-slots/tail-mode      "none"}
          cur  (cursor/init t0)]
      (with-redefs [media-db/resolve-playable-by-channel-tag
                    (fn [_ds channel-tag]
                      (is (= "channel:testchannel" channel-tag))
                      [catalog-item])]
        (let [[events cursor'] (core/emit-block nil cur slot test-channel 1 {})]
          (is (pos? (count events))
              "block emits content from the channel-catalog resolution")
          (is (= 7 (:media-item-id (first events)))
              "event carries the catalog-resolved media-item id")
          (is (= t0 (:start-at (first events)))
              "event starts at block start")
          (is (= t1 (:next-start cursor'))
              "cursor advances to block-end"))))))

;; ---------------------------------------------------------------------------
;; emit-once: media-item branch still works (regression guard)
;; ---------------------------------------------------------------------------

(deftest emit-once-uses-media-item-when-media-item-id-is-set
  (testing "regression guard: the media-item-id branch is unchanged.  When
            a slot has a media-item-id, load-items must still use
            `get-media-item` and the channel-catalog branch must NOT fire."
    (let [slot {:schedule-slots/id             300
                :schedule-slots/media-item-id  1
                :schedule-slots/playback-order "chronological"}
          cur  (cursor/init t0)
          channel-tag-called? (atom false)]
      (with-redefs [media-db/resolve-playable-by-channel-tag
                    (fn [_ _] (reset! channel-tag-called? true) [])
                    media-db/get-media-item
                    (fn [_ _] catalog-item)]
        (let [[events _] (core/emit-once nil cur slot test-channel 1 {})]
          (is (= 1 (count events)) "media-item path emits the item")
          (is (false? @channel-tag-called?)
              "channel-catalog path is NOT taken when media-item-id is set")
          (is (= 7 (:media-item-id (first events)))
              "event carries the resolved media-item id"))))))
