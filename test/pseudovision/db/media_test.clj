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
