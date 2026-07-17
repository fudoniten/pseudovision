(ns pseudovision.db.core-test
  "Tests for the DB-layer column coercions.

   Specifically: `boolean-coerce-column-reader` must convert PG's text-protocol
   't'/'f' strings (returned via `.getObject` on `BOOLEAN` columns) into real
   `java.lang.Boolean` values, and other column types must pass through
   unchanged. Without this, `/api/channels` returns HTTP 500 with
   'Request coercion failed' for every channel row because `:is-enabled` /
   `:show-in-epg` come back as the strings 't'/'f' which fail Malli response
   schema coercion against `:boolean` (verified 2026-07-17)."
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.db.core :as db])
  (:import [java.sql Types]))

;; ---------------------------------------------------------------------------
;; ResultSet / ResultSetMetaData stubs.
;;
;; We don't need a real JDBC connection here — the column-reader is the only
;; thing under test. The stubs hand back canned values.
;;
;; Each stub closes over `last-read-col` (an atom) so `wasNull()` reports
;; correctly for the most recent `getBoolean()` call.
;; ---------------------------------------------------------------------------

(defn- stub-rsmeta
  "Returns a `ResultSetMetaData` stub reporting `column-types` (vector of
   `java.sql.Types` ints) as the type per 1-based column index."
  [column-types]
  (proxy [java.sql.ResultSetMetaData] []
    (getColumnCount [] (count column-types))
    (getColumnType  [i] (nth column-types (dec i)))))

(defn- stub-rs
  "Returns a `ResultSet` stub.
   - `objects` is `.getObject`'s return per 1-based column index
   - `bools`  is `.getBoolean`'s return per 1-based column index
   - `was-nulls` is a set of 1-based indices for which `wasNull()` returns
     true (i.e. SQL NULL on those columns)."
  [column-types objects bools was-nulls]
  (let [last-read-col (atom 0)
        update!       (fn [i] (reset! last-read-col i))
        rsmeta        (stub-rsmeta column-types)]
    (proxy [java.sql.ResultSet] []
      (getMetaData [] rsmeta)
      (getObject   [i] (update! i) (nth objects (dec i)))
      (getBoolean  [i] (update! i) (nth bools (dec i)))
      (wasNull     []  (contains? was-nulls @last-read-col))
      (close       []  nil))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest boolean-coerce-column-reader-handles-boolean-columns
  (testing "BOOLEAN columns come back as real `Boolean`, not 't'/'f' strings"
    (let [rs    (stub-rs
                  [Types/BOOLEAN Types/BOOLEAN Types/VARCHAR]
                  ["t" "f" "hello"]
                  [true false nil]
                  #{})
          rsmeta (.getMetaData ^java.sql.ResultSet rs)]
      (is (instance? Boolean (db/boolean-coerce-column-reader rs rsmeta 1))
          "boolean true column → real Boolean instance (not String)")
      (is (true?  (db/boolean-coerce-column-reader rs rsmeta 1))
          "boolean true column → Clojure/Boot true")
      (is (instance? Boolean (db/boolean-coerce-column-reader rs rsmeta 2))
          "boolean false column → real Boolean instance")
      (is (false? (db/boolean-coerce-column-reader rs rsmeta 2))
          "boolean false column → Clojure/Boot false")
      (is (= "hello" (db/boolean-coerce-column-reader rs rsmeta 3))
          "non-boolean columns pass through unchanged"))))

(deftest boolean-coerce-column-reader-preserves-null
  (testing "NULL boolean columns stay `nil` (via `.wasNull`)"
    (let [rs    (stub-rs
                  [Types/BOOLEAN]
                  [nil]
                  [false]            ;; JDBC's getBoolean on SQL NULL → false
                  #{1})              ;; wasNull says column 1 was NULL
          rsmeta (.getMetaData ^java.sql.ResultSet rs)]
      (is (nil? (db/boolean-coerce-column-reader rs rsmeta 1))
          "NULL boolean column → Clojure/Boot nil, NOT false"))))

(deftest regression-live-symptom-2026-07-17
  (testing "the live /api/channels 500 symptom is fixed by this column-reader"
    ;; Mirrors what /api/channels/44 was returning on 2026-07-17:
    ;;   - is_enabled = "t"   (string) → was breaking Malli :boolean coercion
    ;;   - show_in_epg = "f"   (string) → was breaking Malli :boolean coercion
    ;;   - name = "Sitcom Spectrum"    → unaffected
    ;;   - id = 44                      → unaffected
    (let [rs    (stub-rs
                  [Types/BOOLEAN Types/BOOLEAN Types/VARCHAR Types/INTEGER]
                  ["t" "f" "Sitcom Spectrum" 44]
                  [true false nil 44]
                  #{})
          rsmeta      (.getMetaData ^java.sql.ResultSet rs)
          is-enabled  (db/boolean-coerce-column-reader rs rsmeta 1)
          show-in-epg (db/boolean-coerce-column-reader rs rsmeta 2)
          name        (db/boolean-coerce-column-reader rs rsmeta 3)
          id          (db/boolean-coerce-column-reader rs rsmeta 4)]
      (is (instance? Boolean is-enabled)
          (str "is-enabled must be a Boolean instance, got: "
               (class is-enabled) " value=" is-enabled))
      (is (true? is-enabled)
          "is-enabled should serialize as Clojure/Boot true, not \"t\"")
      (is (instance? Boolean show-in-epg)
          (str "show-in-epg must be a Boolean instance, got: "
               (class show-in-epg) " value=" show-in-epg))
      (is (false? show-in-epg)
          "show-in-epg false should serialize as Clojure/Boot false")
      (is (= "Sitcom Spectrum" name)
          "string columns pass through")
      (is (= 44 id)
          "integer columns pass through"))))
