(ns pseudovision.http.schemas-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [malli.core :as m]
            [malli.json-schema :as mjs]
            [pseudovision.http.schemas :as schemas]))

(deftest iso-date-validates
  (is (m/validate schemas/IsoDate "2026-11-09"))
  (is (not (m/validate schemas/IsoDate "2026-11-9")))
  (is (not (m/validate schemas/IsoDate "x2026-11-09"))))

(deftest iso-date-json-schema-is-encodable
  (testing "the OpenAPI pattern is a plain string, not a compiled regex"
    (let [js (mjs/transform schemas/IsoDate)]
      (is (string? (:pattern js)))
      (is (string? (json/generate-string js))))))
