(ns pseudovision.scheduling.validate-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.scheduling.validate :as v]
            [pseudovision.util.sql :as sql-util])
  (:import [java.time Duration Instant]))

(def ^:private t0 (Instant/parse "2026-11-09T11:00:00Z"))

(defn- at [secs] (.plusSeconds t0 secs))

(defn- ev [kind from to]
  {:kind (sql-util/->pg-enum "event_kind" kind) :start-at (at from) :finish-at (at to)})

(deftest kind-name-reads-pg-enums-and-strings
  (is (= "post" (v/kind-name (ev "post" 0 1))))
  (is (= "post" (v/kind-name {:kind "post"})))
  (is (v/filler? (ev "fallback" 0 1)))
  (is (not (v/filler? (ev "content" 0 1)))))

(deftest clamp-to-cuts-events-at-the-deadline
  (let [evs [(ev "tail" 0 60) (ev "tail" 60 125) (ev "tail" 125 200)]]
    (testing "the event crossing the deadline is trimmed; later ones are dropped"
      (let [out (v/clamp-to evs (at 120))]
        (is (= 2 (count out)))
        (is (= (at 120) (:finish-at (last out))))))
    (testing "an event that would be left with no length is dropped"
      (is (= 1 (count (v/clamp-to evs (at 60))))))
    (testing "nil deadline leaves events alone"
      (is (= evs (v/clamp-to evs nil))))))

(deftest trim-filler-overruns-trims-filler-but-not-content
  (testing "a post break ending 3s past the hour is cut to the hour"
    (let [out (v/trim-filler-overruns [(ev "content" 0 1800)
                                       (ev "post" 1800 3603)
                                       (ev "content" 3600 5400)])]
      (is (= (at 3600) (:finish-at (second out))))
      (is (empty? (v/overlaps out)))))
  (testing "a fallback break running 45 minutes into a program is cut"
    (let [out (v/trim-filler-overruns [(ev "fallback" 0 3600) (ev "content" 900 2700)])]
      (is (= (at 900) (:finish-at (first out))))))
  (testing "content overlaps are left for overlaps to report"
    (let [out (v/trim-filler-overruns [(ev "content" 0 100) (ev "content" 50 150)])]
      (is (= 1 (count (v/overlaps out))))))
  (testing "zero-length events are dropped, as is filler with nothing left"
    (let [out (v/trim-filler-overruns [(ev "bumper" 0 0)
                                       (ev "tail" 10 20)
                                       (ev "content" 10 30)])]
      (is (= ["content"] (map v/kind-name out))))))

(deftest overlaps-finds-doubled-timelines
  (let [one  [(ev "content" 0 1800) (ev "post" 1800 1900) (ev "content" 1900 3600)]
        both (concat one one)
        found (v/overlaps both)]
    (is (empty? (v/overlaps one)))
    (is (= 3 (count found)) "each copy's event collides with its twin")
    (is (= (Duration/ofSeconds 1800) (:overlap (first found))))
    (is (= "content" (:first-kind (v/describe (first found)))))))
