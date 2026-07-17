(ns pseudovision.scheduling.load-items-test
  "Tests for the channel-catalog fallback in `pseudovision.scheduling.core`'s
  private `load-items`.  The function is private, so the tests are written
  against its public callers (`emit-once`, `emit-block`) and the private
  resolution path is exercised via `with-redefs` on
  `media-db/resolve-playable-by-channel-tag`.

  The pre-PR-#142 code had a `:else []` branch in `load-items` that silently
  returned an empty vector for any slot with neither `collection-id` nor
  `media-item-id`.  The 50 active \"(auto)\" schedule slots in the live
  cluster all hit this branch and every build returned `events-generated: 0`
  for them.  PR #142's fix added the channel-catalog fallback that resolves
  the slot through `media-db/resolve-playable-by-channel-tag`.

  After PR #142 shipped, the live `(auto)` rebuilds still emitted zero
  events on the four (auto) channels.  Tracked live 2026-07-17 from the PV
  pod logs: the tag the resolver received was `channel:Sitcom Spectrum`
  (built from the channel's display name), but the production tag is stored
  as the curated lowercase slug `channel:spectrum`.  The pre-fix-PR-#145
  test fixture used `:channels/name \"testchannel\"` and asserted
  `channel:testchannel`, which conflated display name and slug and so
  masked the production bug from CI.  The fix in PR #145 introduces
  `channels.slug` and makes `load-items` read `:channels/slug` and throw
  loudly if it's nil.  These tests now exercise:
    (a) the slug-based lookup with display name and slug deliberately
        distinct (regression for the original bug — display name \"Sitcom
        Spectrum\" with slug \"spectrum\" must produce tag \"channel:spectrum\");
    (b) a loud throw when the channel has no slug (catches new code that
        silently falls back to display name again);
    (c) regression guards on the collection-id and media-item-id paths."
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
;; it's set up to "see" the channel tag.
(def ^:private catalog-item
  {:media-items/id          7
   :media-versions/duration dur-30m
   :_top-id                 7})

;; A channel with a display name AND a slug that are deliberately different.
;; This is the canonical shape of the live channel Sitcom Spectrum:
;;   display name = "Sitcom Spectrum" (with spaces and capitals)
;;   slug         = "spectrum"       (curated lowercase)
;; Pre-fix-PR-#145 the lookup built `channel:Sitcom Spectrum` from the
;; display name and matched zero production tags.  Post-fix-PR-#145 it
;; builds `channel:spectrum` from the slug and matches the 100 items in
;; the live cluster.
(def ^:private test-channel
  {:channels/id 1
   :channels/name "Sitcom Spectrum"
   :channels/slug "spectrum"})

;; ---------------------------------------------------------------------------
;; emit-once: channel-catalog fallback (the new path)
;; ---------------------------------------------------------------------------

(deftest emit-once-resolves-via-channel-catalog-when-slot-has-no-source
  (testing "a slot with neither collection-id nor media-item-id falls back
            to the channel's catalog via `channel:<slug>`, emitting one event
            from the resolved items.  Note the tag is built from the slug,
            NOT the display name — that's the bug PR #145 fixed."
    (let [slot {:schedule-slots/id             100
                ;; no collection-id, no media-item-id
                :schedule-slots/playback-order "chronological"
                :schedule-slots/anchor         "sequential"
                :schedule-slots/fill-mode      "once"}
          cur  (cursor/init t0)]
      (with-redefs [media-db/resolve-playable-by-channel-tag
                    (fn [_ds channel-tag]
                      ;; The smoking-gun assertion: tag is built from the
                      ;; slug "spectrum", NOT the display name "Sitcom
                      ;; Spectrum".  If the implementation regresses to
                      ;; using :channels/name, this test fires.
                      (is (= "channel:spectrum" channel-tag)
                          "channel-tag derived from channels/slug, NOT channels/name")
                      [catalog-item])]
        (let [[events cursor'] (core/emit-once nil cur slot test-channel 1 {})]
          (is (= 1 (count events)) "one event emitted from channel-catalog items")
          (is (= 7 (:media-item-id (first events)))
              "event carries the resolved media-item id")
          (is (= t0 (:start-at (first events)))
              "event starts at cursor")
          (is (= t1 (:next-start cursor'))
              "cursor advanced by the item's duration"))))))

(deftest emit-once-throws-loud-when-channel-has-no-slug
  (testing "the channel-catalog branch is gated on :channels/slug being
            non-nil.  When the channel has a display name but no slug
            (the post-PR-#142-without-PR-#145 land — the exact live-cluster
            condition before the migration ran), load-items MUST throw an
            ex-info rather than silently fall back to the display name
            and produce zero events.  Silence was the bug we just fixed;
            the loud-error contract is the regression guard."
    (let [;; A channel with a display name but nil slug — identical to the
          ;; pre-backfill state of every (auto) channel on 2026-07-17.
          no-slug-channel   {:channels/id   44
                             :channels/name "Sitcom Spectrum"
                             ;; :channels/slug is nil
                             }
          slot              {:schedule-slots/id             101
                              :schedule-slots/playback-order "chronological"
                              :schedule-slots/anchor         "sequential"
                              :schedule-slots/fill-mode      "once"}
          cur               (cursor/init t0)
          channel-tag-called? (atom false)]
      (with-redefs [media-db/resolve-playable-by-channel-tag
                    (fn [_ _] (reset! channel-tag-called? true) [])]
        (let [thrown (try
                       (core/emit-once nil cur slot no-slug-channel 1 {})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "throw fired — the code refuses to silently emit zero events")
          (is (false? @channel-tag-called?)
              "channel-catalog resolver NOT invoked (would have produced the silent bug)")
          (is (string? (.getMessage thrown))
              "exception has a message")
          (is (some? (:channel-id (ex-data thrown)))
              "exception carries diagnostic :channel-id in ex-data")
          (is (= 44 (:channel-id (ex-data thrown)))
              ":channel-id in ex-data matches the input channel"))))))

;; ---------------------------------------------------------------------------
;; emit-block: channel-catalog fallback (the live-cluster repro)
;; ---------------------------------------------------------------------------

(deftest emit-block-emits-content-via-channel-catalog
  (testing "reproduces the live-cluster repro: a fixed-anchor block slot
            with neither collection-id nor media-item-id used to emit zero
            events.  With the slug-based fix it resolves the slot through
            the channel catalog and emits content events that fit the block
            duration."
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
                      (is (= "channel:spectrum" channel-tag)
                          "block lookup also uses slug, not display name")
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
