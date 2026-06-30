(ns pseudovision.util.time-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.util.time :as t])
  (:import [java.time Instant ZoneId]))

(deftest default-zone-defaults-to-utc
  (testing "with PSEUDOVISION_TZ unset, the application zone is UTC"
    ;; The test environment does not set PSEUDOVISION_TZ.
    (is (= "UTC" (t/default-zone-id)))
    (is (= (ZoneId/of "UTC") (t/default-zone)))))

(deftest instant->zdt-uses-default-zone
  (testing "instant->zdt with no explicit zone uses the application default"
    (let [inst (Instant/parse "2026-06-29T12:00:00Z")
          zdt  (t/instant->zdt inst)]
      (is (= (t/default-zone) (.getZone zdt))))))
