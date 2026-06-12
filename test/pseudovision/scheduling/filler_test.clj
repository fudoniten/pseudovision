(ns pseudovision.scheduling.filler-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.scheduling.filler :as sut]
            [pseudovision.scheduling.enumerators :as enum])
  (:import [java.time Instant Duration]))

(def ^:private t0 (Instant/parse "2026-04-27T20:00:00Z"))
(def ^:private t1 (Instant/parse "2026-04-27T21:00:00Z"))

(def ^:private item-5m  {:media-items/id 1 :media-versions/duration (Duration/ofMinutes 5)})
(def ^:private item-10m {:media-items/id 2 :media-versions/duration (Duration/ofMinutes 10)})
(def ^:private item-30m {:media-items/id 3 :media-versions/duration (Duration/ofMinutes 30)})

(defn- make-enum [items]
  (enum/make-enumerator items :chronological {}))

;; ---------------------------------------------------------------------------
;; resolve-filler-preset
;; ---------------------------------------------------------------------------

(deftest resolve-filler-preset-slot-level-takes-precedence
  (testing "slot FK wins over channel fallback"
    (let [slot    {:schedule-slots/tail-filler-id 42}
          channel {:channels/fallback-filler-id 99}]
      (is (= {:id 42 :source :slot}
             (sut/resolve-filler-preset :tail slot channel)))))
  (testing "each role maps to its own slot FK"
    (doseq [[role fk] [[:pre  :schedule-slots/pre-filler-id]
                       [:mid  :schedule-slots/mid-filler-id]
                       [:post :schedule-slots/post-filler-id]
                       [:tail :schedule-slots/tail-filler-id]]]
      (let [slot {fk 7}]
        (is (= {:id 7 :source :slot}
               (sut/resolve-filler-preset role slot {}))
            (str "role " role " should read " fk))))))

(deftest resolve-filler-preset-channel-fallback
  (testing ":fallback role falls back to channel when slot has no FK"
    (let [channel {:channels/fallback-filler-id 7}]
      (is (= {:id 7 :source :channel}
             (sut/resolve-filler-preset :fallback {} channel)))))
  (testing "non-fallback roles do NOT inherit from channel"
    (let [channel {:channels/fallback-filler-id 7}]
      (is (nil? (sut/resolve-filler-preset :tail    {} channel)))
      (is (nil? (sut/resolve-filler-preset :pre     {} channel)))
      (is (nil? (sut/resolve-filler-preset :mid     {} channel)))
      (is (nil? (sut/resolve-filler-preset :post    {} channel))))))

(deftest resolve-filler-preset-nil-when-unconfigured
  (testing "returns nil when neither slot nor channel has a preset"
    (is (nil? (sut/resolve-filler-preset :fallback {} {})))
    (is (nil? (sut/resolve-filler-preset :tail     {} {})))))

;; ---------------------------------------------------------------------------
;; fill-gap — duration mode
;; ---------------------------------------------------------------------------

(deftest fill-gap-duration-packs-items-to-boundary
  (testing "fills gap with back-to-back items up to the boundary"
    ;; Gap = 20 min; two 10m items fill it exactly
    (let [gap-end (Instant/parse "2026-04-27T20:20:00Z")
          items   [item-10m item-10m]
          preset  {:filler-presets/mode "duration" :filler-presets/role "tail"}
          result  (sut/fill-gap t0 gap-end preset items (make-enum items) 5)]
      (is (= 2 (count (:events result))))
      (is (= t0      (:start-at  (first (:events result)))))
      (is (= gap-end (:finish-at (last  (:events result))))))))

(deftest fill-gap-duration-stops-before-overflow
  (testing "skips an item that would run past the gap boundary"
    ;; Gap = 7 min; 10m item would overflow → 0 events emitted
    (let [gap-end (Instant/parse "2026-04-27T20:07:00Z")
          items   [item-10m]
          preset  {:filler-presets/mode "duration" :filler-presets/role "tail"}
          result  (sut/fill-gap t0 gap-end preset items (make-enum items) 5)]
      (is (= 0 (count (:events result)))))))

(deftest fill-gap-duration-partial-fit
  (testing "packs as many items as fit, leaving a small remainder unfilled"
    ;; Gap = 22 min; 10m + 10m = 20m fits; third 10m would overflow
    (let [gap-end (Instant/parse "2026-04-27T20:22:00Z")
          items   [item-10m item-10m item-10m]
          preset  {:filler-presets/mode "duration" :filler-presets/role "tail"}
          result  (sut/fill-gap t0 gap-end preset items (make-enum items) 5)]
      (is (= 2 (count (:events result)))))))

(deftest fill-gap-duration-empty-items
  (testing "returns empty events immediately when no items"
    (let [preset {:filler-presets/mode "duration" :filler-presets/role "tail"}
          result (sut/fill-gap t0 t1 preset [] (make-enum []) 5)]
      (is (= [] (:events result))))))

(deftest fill-gap-duration-stamps-playout-id
  (testing "each event carries the provided playout-id"
    (let [gap-end (Instant/parse "2026-04-27T20:05:00Z")
          items   [item-5m]
          preset  {:filler-presets/mode "duration" :filler-presets/role "tail"}
          result  (sut/fill-gap t0 gap-end preset items (make-enum items) 42)]
      (is (= 1 (count (:events result))))
      (is (= 42 (:playout-id (first (:events result))))))))

;; ---------------------------------------------------------------------------
;; fill-gap — count mode
;; ---------------------------------------------------------------------------

(deftest fill-gap-count-emits-exactly-n
  (testing "emits exactly preset count items regardless of gap duration"
    (let [items  [item-5m item-5m item-5m item-5m]
          preset {:filler-presets/mode "count" :filler-presets/count 2 :filler-presets/role "pre"}
          result (sut/fill-gap t0 t1 preset items (make-enum items) 5)]
      (is (= 2 (count (:events result)))))))

(deftest fill-gap-count-one-item
  (testing "count=1 emits exactly one item"
    (let [items  [item-5m item-10m item-30m]
          preset {:filler-presets/mode "count" :filler-presets/count 1 :filler-presets/role "post"}
          result (sut/fill-gap t0 t1 preset items (make-enum items) 5)]
      (is (= 1 (count (:events result))))
      (is (= 1 (:media-item-id (first (:events result))))))))

;; ---------------------------------------------------------------------------
;; fill-gap — enumerator state threading
;; ---------------------------------------------------------------------------

(deftest fill-gap-threads-enumerator-forward
  (testing "returned enumerator is positioned after last emitted item"
    (let [items  [item-5m item-10m item-30m]
          preset {:filler-presets/mode "count" :filler-presets/count 1 :filler-presets/role "post"}
          e0     (make-enum items)
          result (sut/fill-gap t0 t1 preset items e0 5)
          e1     (:enumerator result)
          [next-item _] (enum/next-item e1)]
      ;; First call consumed index 0 (item-5m); next should be index 1 (item-10m)
      (is (= 2 (:media-items/id next-item))))))

(deftest fill-gap-returns-enumerator-unchanged-when-no-items
  (testing "enumerator is returned unchanged when items list is empty"
    (let [preset {:filler-presets/mode "duration" :filler-presets/role "tail"}
          e0     (make-enum [])
          result (sut/fill-gap t0 t1 preset [] e0 5)]
      (is (identical? e0 (:enumerator result))))))

;; ---------------------------------------------------------------------------
;; fill-gap — event shape
;; ---------------------------------------------------------------------------

(deftest fill-gap-events-have-correct-shape
  (testing "emitted events have all required fields"
    (let [gap-end (Instant/parse "2026-04-27T20:05:00Z")
          items   [item-5m]
          preset  {:filler-presets/mode "duration" :filler-presets/role "tail"}
          events  (:events (sut/fill-gap t0 gap-end preset items (make-enum items) 7))]
      (is (= 1 (count events)))
      (let [ev (first events)]
        (is (= 7        (:playout-id    ev)))
        (is (= 1        (:media-item-id ev)))
        (is (= "tail"   (:kind          ev)))
        (is (= t0       (:start-at      ev)))
        (is (= gap-end  (:finish-at     ev)))
        (is (false?     (:is-manual     ev)))))))
