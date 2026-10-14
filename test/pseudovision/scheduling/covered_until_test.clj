(ns pseudovision.scheduling.covered-until-test
  "covered-until decides where a resume build starts when the saved cursor is
   ahead of the events actually on the timeline (health report 2026-10-14:
   cursors saved at Oct 31 over a timeline with an Oct 19-24 hole made every
   nightly ensure build nothing)."
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.scheduling.core :as core])
  (:import [java.time Duration Instant]))

(def ^:private t0 (Instant/parse "2026-10-14T09:00:00Z"))
(defn- at [hours] (.plus t0 (Duration/ofMinutes (long (* 60 hours)))))
(def ^:private day (Duration/ofHours 24))

(deftest covered-until-test
  (testing "nothing scheduled: coverage ends at from"
    (is (= t0 (core/covered-until [] t0 day))))
  (testing "continuous events: coverage reaches the last finish"
    (is (= (at 3) (core/covered-until [[(at -0.5) (at 1)] [(at 1) (at 2)] [(at 2) (at 3)]]
                                      t0 day))))
  (testing "short gaps (days-of-week skips, filler shortfalls) are tolerated"
    (is (= (at 30) (core/covered-until [[(at 0) (at 1)] [(at 20) (at 30)]] t0 day))))
  (testing "a gap longer than max-hole stops coverage at its start"
    (is (= (at 130) (core/covered-until [[(at 0) (at 130)] [(at 243) (at 330)]] t0 day))))
  (testing "an event nested inside a longer one doesn't pull the edge back"
    (is (= (at 10) (core/covered-until [[(at 0) (at 10)] [(at 2) (at 3)]] t0 day)))))
