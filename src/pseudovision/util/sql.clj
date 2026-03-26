(ns pseudovision.util.sql
  "HoneySQL / next.jdbc helpers for type coercion and common patterns."
  (:require [honey.sql :as sql]
            [cheshire.core :as json]
            [cheshire.generate :as json-gen]
            [next.jdbc.prepare :as jdbc-prep])
  (:import [org.postgresql.util PGobject]
           [java.util UUID]
           [java.time Instant]
           [java.sql Timestamp PreparedStatement]))

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

(defn ->uuid
  "Coerces a string or java.util.UUID to UUID; throws ex-info on unrecognized input."
  [s]
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
    (.setValue (json/generate-string v))))

(defn <-jsonb
  "Parses a JSONB PGobject back to a Clojure value."
  [v]
  (if (instance? PGobject v)
    (json/parse-string (.getValue ^PGobject v) true)
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

(defn ->pg-interval
  "Wraps a string (e.g. '02:00:00' or '1 hour 30 minutes') as a PostgreSQL interval PGobject."
  [s]
  (doto (PGobject.)
    (.setType "interval")
    (.setValue (str s))))

;; ---------------------------------------------------------------------------
;; Timestamp coercion (java.time.Instant <-> java.sql.Timestamp)
;; ---------------------------------------------------------------------------

(defn instant->timestamp
  "Converts a java.time.Instant to java.sql.Timestamp for PostgreSQL TIMESTAMPTZ columns."
  [^Instant inst]
  (when inst
    (Timestamp/from inst)))

;; Extend next.jdbc to automatically convert java.time.Instant to java.sql.Timestamp
(extend-protocol jdbc-prep/SettableParameter
  Instant
  (set-parameter [^Instant v ^PreparedStatement ps ^long i]
    (.setTimestamp ps i (Timestamp/from v))))

;; ---------------------------------------------------------------------------
;; JSON encoding for PostgreSQL types
;; ---------------------------------------------------------------------------

(json-gen/add-encoder
 PGobject
 (fn [^PGobject obj ^com.fasterxml.jackson.core.JsonGenerator gen]
   (let [type  (.getType obj)
         value (.getValue obj)]
     (case type
       ;; JSONB fields should be embedded as-is (already JSON)
       "jsonb" (if value
                 (.writeRawValue gen value)
                 (.writeNull gen))
       ;; Everything else (enums, etc.) should be written as strings
       (.writeString gen value)))))

(json-gen/add-encoder
 java.time.Duration
 (fn [^java.time.Duration d ^com.fasterxml.jackson.core.JsonGenerator gen]
   (let [seconds (.getSeconds d)
         hours   (quot seconds 3600)
         minutes (quot (rem seconds 3600) 60)
         secs    (rem  seconds 60)]
     (.writeString gen (format "%02d:%02d:%02d" hours minutes secs)))))

