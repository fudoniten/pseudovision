(ns pseudovision.db.schedules-test
  "Unit tests for pseudovision.db.schedules/create-slot!'s implicit fill-mode
   default: a slot left at anchor='sequential' (explicitly or via the DB's own
   default) with no fill-mode specified should default to 'flood' rather than
   the DB column's blanket 'once' default, which plays exactly one item and
   then stops."
  (:require [clojure.test :refer [deftest is testing]]
            [honey.sql :as sql]
            [pseudovision.db.core :as db]
            [pseudovision.db.schedules :as schedules-db]))

(defn- capture-insert-values!
  "Redefs honey.sql/format and db/execute-one! so create-slot! runs to
   completion, and returns the single values-map that would have been
   inserted (post-coercion, pre-SQL-string-formatting)."
  [attrs]
  (let [captured (atom nil)]
    (with-redefs [sql/format         (fn [sql-map & _]
                                        (reset! captured (first (:values sql-map)))
                                        ["SELECT 1"])
                  db/execute-one!    (fn [_ _] {})]
      (schedules-db/create-slot! nil attrs))
    @captured))

(defn- pg-value [v]
  (if (instance? org.postgresql.util.PGobject v) (.getValue ^org.postgresql.util.PGobject v) v))

(deftest sequential-anchor-defaults-fill-mode-to-flood
  (testing "explicit anchor='sequential', no fill-mode given -> defaults to flood"
    (let [values (capture-insert-values! {:slot-index 0 :anchor "sequential"})]
      (is (= "flood" (pg-value (:fill-mode values)))))))

(deftest omitted-anchor-defaults-fill-mode-to-flood
  (testing "anchor omitted entirely (DB default is 'sequential') -> flood default still applies"
    (let [values (capture-insert-values! {:slot-index 0})]
      (is (= "flood" (pg-value (:fill-mode values)))))))

(deftest fixed-anchor-is-unaffected
  (testing "anchor='fixed' with no fill-mode given -> left to the DB's own default (not overridden)"
    (let [values (capture-insert-values! {:slot-index 0 :anchor "fixed" :start-time "20:00:00"})]
      (is (not (contains? values :fill-mode))
          "fill-mode key is absent, letting the column default ('once') apply"))))

(deftest explicit-fill-mode-is-respected
  (testing "an explicit fill-mode is never overridden, even for a sequential slot"
    (let [values (capture-insert-values! {:slot-index 0 :anchor "sequential" :fill-mode "block"
                                          :block-duration "PT1H"})]
      (is (= "block" (pg-value (:fill-mode values)))))))
