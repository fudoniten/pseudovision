(ns pseudovision.db.media-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.db.media :as sut]
            [pseudovision.db.core :as db]))

;; ---------------------------------------------------------------------------
;; list-media-items SQL generation
;; ---------------------------------------------------------------------------

(defn- capture-sql
  "Calls list-media-items with the given opts, returning the formatted SQL
   vector that would be sent to the database."
  [opts]
  (let [captured (atom nil)]
    (with-redefs [db/query (fn [_ sql] (reset! captured sql) [])]
      (sut/list-media-items nil 26 opts))
    @captured))

(deftest list-media-items-default-attrs-select
  (testing "default attrs produce a well-formed SELECT with each column spread"
    (let [[sql] (capture-sql {:limit 50 :offset 0})]
      ;; Each column must appear as its own select expression; the title is
      ;; aliased to `name`. Regression for a syntax error caused by passing the
      ;; column vector as a single arg to h/select instead of spreading it.
      (is (re-find #"(?i)SELECT\s+mi\.id,\s*m\.title\s+AS\s+name" sql)))))

(deftest list-media-items-joins-metadata-for-name
  (testing "requesting a metadata-backed attr adds the metadata LEFT JOIN"
    (let [[sql] (capture-sql {:attrs [:id :name] :limit 50 :offset 0})]
      (is (re-find #"(?i)LEFT JOIN metadata" sql)))))

;; ---------------------------------------------------------------------------
;; get-media-item / resolve-media-item-id reference matching
;; ---------------------------------------------------------------------------

(defn- capture-query-one
  "Invokes `f` with a stubbed db/query-one that records the formatted SQL
   vector, returning that vector."
  [f]
  (let [captured (atom nil)]
    (with-redefs [db/query-one (fn [_ sql] (reset! captured sql) nil)]
      (f))
    @captured))

(deftest get-media-item-by-integer-id
  (testing "an integer ref matches either the internal id or remote_key"
    (let [[sql & params] (capture-query-one #(sut/get-media-item nil 42))]
      (is (re-find #"(?i)mi\.id = \?\)?\s*OR\s*\(?mi\.remote_key = \?" sql))
      (is (= [42 "42"] params)))))

(deftest get-media-item-by-numeric-string-id
  (testing "a numeric string ref also matches both columns"
    (let [[sql & params] (capture-query-one #(sut/get-media-item nil "42"))]
      (is (re-find #"(?i)mi\.id = \?\)?\s*OR\s*\(?mi\.remote_key = \?" sql))
      (is (= [42 "42"] params)))))

(deftest get-media-item-by-remote-key
  (testing "a non-numeric ref (e.g. a Jellyfin id) matches remote_key only"
    (let [jf-id "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
          [sql & params] (capture-query-one #(sut/get-media-item nil jf-id))]
      (is (re-find #"(?i)mi\.remote_key = \?" sql))
      (is (not (re-find #"(?i)mi\.id = \?" sql)))
      (is (= [jf-id] params)))))

(deftest get-media-item-out-of-range-numeric-ref
  (testing "an all-numeric ref too large for the SERIAL id matches remote_key only"
    (let [big "12345678901234567890123456789012"
          [sql & params] (capture-query-one #(sut/get-media-item nil big))]
      (is (not (re-find #"(?i)mi\.id = \?" sql)))
      (is (= [big] params)))))

(deftest resolve-media-item-id-uses-ref-match
  (testing "resolve-media-item-id selects mi.id matching id or remote_key"
    (let [[sql & params] (capture-query-one #(sut/resolve-media-item-id nil "abc"))]
      (is (re-find #"(?i)SELECT mi\.id" sql))
      (is (= ["abc"] params)))))
