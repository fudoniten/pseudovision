(ns pseudovision.util.sql
  "HoneySQL / next.jdbc helpers for type coercion and common patterns."
  (:require [honey.sql :as sql])
  (:import [org.postgresql.util PGobject]
           [java.util UUID]))

;; ---------------------------------------------------------------------------
;; PostgreSQL enum coercion
;; ---------------------------------------------------------------------------

(defn ->pg-enum
  "Wraps a keyword or string value in a PGobject so PostgreSQL accepts it as
   the correct enum type.
   Usage: (->pg-enum \"streaming_mode\" :ts)"
  [type-name value]
  (doto (PGobject.)
    (.setType  (name type-name))
    (.setValue (if (keyword? value) (name value) (str value)))))

;; ---------------------------------------------------------------------------
;; UUID coercion
;; ---------------------------------------------------------------------------

(defn ->uuid [s]
  (cond (instance? UUID s) s
        (string? s)        (UUID/fromString s)
        :else              (throw (ex-info "Cannot coerce to UUID" {:value s}))))

;; ---------------------------------------------------------------------------
;; JSONB coercion (cheshire)
;; ---------------------------------------------------------------------------

(defn ->jsonb
  "Converts a Clojure map/vec to a PGobject with type 'jsonb'."
  [v]
  (doto (PGobject.)
    (.setType  "jsonb")
    (.setValue (cheshire.core/generate-string v))))

(defn <-jsonb
  "Parses a JSONB PGobject back to a Clojure value."
  [v]
  (if (instance? PGobject v)
    (cheshire.core/parse-string (.getValue ^PGobject v) true)
    v))

;; ---------------------------------------------------------------------------
;; Interval coercion (PostgreSQL INTERVAL <-> java.time.Duration)
;; ---------------------------------------------------------------------------

(defn duration->pg-interval
  "Converts a java.time.Duration to a PGInterval string that PostgreSQL accepts."
  [^java.time.Duration d]
  (let [seconds (.getSeconds d)
        hours   (quot seconds 3600)
        minutes (quot (rem seconds 3600) 60)
        secs    (rem  seconds 60)]
    (format "%d hours %d minutes %d seconds" hours minutes secs)))
