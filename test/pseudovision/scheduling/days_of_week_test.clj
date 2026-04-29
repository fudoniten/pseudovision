(ns pseudovision.scheduling.days-of-week-test
  "Tests for day-of-week bitmask scheduling support.

   Covers:
     - pseudovision.util.time/fires-on-day?
     - pseudovision.util.time/next-dow-occurrence
     - process-slot skip behaviour (via the exported helpers)

   All instants are constructed as specific known days so tests are
   deterministic and independent of wall-clock time."
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.util.time :as t])
  (:import [java.time Instant Duration]))

;; ---------------------------------------------------------------------------
;; Known instants (all UTC for simplicity)
;;   2026-04-27 = Monday
;;   2026-04-28 = Tuesday
;;   2026-04-29 = Wednesday
;;   2026-04-30 = Thursday
;;   2026-05-01 = Friday
;;   2026-05-02 = Saturday
;;   2026-05-03 = Sunday
;; ---------------------------------------------------------------------------

(def ^:private monday    (Instant/parse "2026-04-27T12:00:00Z"))
(def ^:private tuesday   (Instant/parse "2026-04-28T12:00:00Z"))
(def ^:private wednesday (Instant/parse "2026-04-29T12:00:00Z"))
(def ^:private thursday  (Instant/parse "2026-04-30T12:00:00Z"))
(def ^:private friday    (Instant/parse "2026-05-01T12:00:00Z"))
(def ^:private saturday  (Instant/parse "2026-05-02T12:00:00Z"))
(def ^:private sunday    (Instant/parse "2026-05-03T12:00:00Z"))

(def ^:private zone "UTC")

;; Bitmask constants
(def ^:private every-day  127)  ; 0b1111111
(def ^:private mwf        21)   ; Mon=1 + Wed=4 + Fri=16
(def ^:private weekdays   31)   ; Mon=1 + Tue=2 + Wed=4 + Thu=8 + Fri=16
(def ^:private weekends   96)   ; Sat=32 + Sun=64
(def ^:private monday-only 1)

;; ---------------------------------------------------------------------------
;; fires-on-day?
;; ---------------------------------------------------------------------------

(deftest fires-on-day-every-day
  (testing "nil mask fires every day"
    (is (t/fires-on-day? nil monday zone))
    (is (t/fires-on-day? nil saturday zone))
    (is (t/fires-on-day? nil sunday zone)))
  (testing "127 mask fires every day"
    (is (t/fires-on-day? every-day monday zone))
    (is (t/fires-on-day? every-day friday zone))
    (is (t/fires-on-day? every-day sunday zone))))

(deftest fires-on-day-mwf
  (testing "MWF mask (21) fires on Monday, Wednesday, Friday"
    (is (t/fires-on-day? mwf monday zone))
    (is (t/fires-on-day? mwf wednesday zone))
    (is (t/fires-on-day? mwf friday zone)))
  (testing "MWF mask does not fire on Tuesday, Thursday, Saturday, Sunday"
    (is (not (t/fires-on-day? mwf tuesday zone)))
    (is (not (t/fires-on-day? mwf thursday zone)))
    (is (not (t/fires-on-day? mwf saturday zone)))
    (is (not (t/fires-on-day? mwf sunday zone)))))

(deftest fires-on-day-weekends
  (testing "Weekend mask fires on Saturday and Sunday"
    (is (t/fires-on-day? weekends saturday zone))
    (is (t/fires-on-day? weekends sunday zone)))
  (testing "Weekend mask does not fire on weekdays"
    (is (not (t/fires-on-day? weekends monday zone)))
    (is (not (t/fires-on-day? weekends friday zone)))))

;; ---------------------------------------------------------------------------
;; next-dow-occurrence
;; ---------------------------------------------------------------------------

(defn- tod-at [hour]
  ;; Duration offset from midnight for a given hour
  (Duration/ofHours hour))

(deftest next-dow-occurrence-same-day
  (testing "fires today if time-of-day is still ahead"
    ;; monday at 12:00; TOD = 20:00 → should fire monday at 20:00
    (let [result (t/next-dow-occurrence (tod-at 20) monday-only monday zone)
          expected (Instant/parse "2026-04-27T20:00:00Z")]
      (is (= expected result)))))

(deftest next-dow-occurrence-skip-to-next-week
  (testing "skips to next Monday when current day is past the TOD"
    ;; monday at 12:00; TOD = 08:00 (already passed) → next Monday
    (let [result   (t/next-dow-occurrence (tod-at 8) monday-only monday zone)
          expected (Instant/parse "2026-05-04T08:00:00Z")]  ; next Monday
      (is (= expected result)))))

(deftest next-dow-occurrence-mwf-from-tuesday
  (testing "MWF from Tuesday noon finds Wednesday morning"
    (let [result   (t/next-dow-occurrence (tod-at 7) mwf tuesday zone)
          expected (Instant/parse "2026-04-29T07:00:00Z")]  ; Wednesday
      (is (= expected result)))))

(deftest next-dow-occurrence-mwf-wraparound
  (testing "MWF from Friday evening wraps to next Monday"
    ;; Friday at 12:00; TOD = 07:00 (passed); next MWF day = Monday
    (let [result   (t/next-dow-occurrence (tod-at 7) mwf friday zone)
          expected (Instant/parse "2026-05-04T07:00:00Z")]  ; Monday
      (is (= expected result)))))

(deftest next-dow-occurrence-every-day
  (testing "every-day mask behaves like original next-fixed-start"
    ;; Should return today's TOD if still ahead, else tomorrow
    (let [result   (t/next-dow-occurrence (tod-at 20) every-day monday zone)
          expected (Instant/parse "2026-04-27T20:00:00Z")]
      (is (= expected result)))
    (let [result   (t/next-dow-occurrence (tod-at 8) every-day monday zone)
          expected (Instant/parse "2026-04-28T08:00:00Z")]  ; Tuesday
      (is (= expected result)))))

(deftest next-dow-occurrence-nil-mask
  (testing "nil mask treated as every day"
    (let [result   (t/next-dow-occurrence (tod-at 20) nil monday zone)
          expected (Instant/parse "2026-04-27T20:00:00Z")]
      (is (= expected result)))))

(deftest next-dow-occurrence-weekend-from-friday
  (testing "weekend-only from Friday finds Saturday"
    (let [result   (t/next-dow-occurrence (tod-at 10) weekends friday zone)
          expected (Instant/parse "2026-05-02T10:00:00Z")]  ; Saturday
      (is (= expected result)))))
