(ns pseudovision.util.time
  "Thin wrappers around tick for the time operations used by the scheduler."
  (:require [tick.core :as t])
  (:import [java.time Duration Instant ZonedDateTime ZoneId]))

(defn now [] (t/now))

(defn instant->zdt
  ([inst] (instant->zdt inst "UTC"))
  ([inst zone-id]
   (.atZone ^Instant inst (ZoneId/of zone-id))))

(defn zdt->instant [^ZonedDateTime zdt]
  (.toInstant zdt))

(defn add-duration [^Instant inst ^Duration d]
  (.plus inst d))

(defn sub-duration [^Instant inst ^Duration d]
  (.minus inst d))

(defn duration-between [^Instant a ^Instant b]
  (Duration/between a b))

(defn duration->seconds [^Duration d]
  (.getSeconds d))

(defn duration->ms [^Duration d]
  (.toMillis d))

(defn ms->duration [ms]
  (Duration/ofMillis ms))

(defn seconds->duration [s]
  (Duration/ofSeconds s))

(defn minutes->duration [m]
  (Duration/ofMinutes m))

(defn hours->duration [h]
  (Duration/ofHours h))

(defn truncate-to-minute [^Instant inst]
  (.truncatedTo inst java.time.temporal.ChronoUnit/MINUTES))

(defn ceil-to-minute
  "Rounds inst up to the next whole minute."
  [^Instant inst]
  (let [truncated (truncate-to-minute inst)]
    (if (.equals truncated inst)
      inst
      (add-duration truncated (minutes->duration 1)))))

(defn ceil-to-n-minutes
  "Rounds inst up to the next multiple of n minutes."
  [^Instant inst n]
  (let [epoch-seconds (.getEpochSecond inst)
        n-seconds     (* n 60)
        remainder     (mod epoch-seconds n-seconds)
        target-seconds (if (zero? remainder)
                         epoch-seconds
                         (+ epoch-seconds (- n-seconds remainder)))]
    (Instant/ofEpochSecond target-seconds)))

;; ---------------------------------------------------------------------------
;; XMLTV formatting  (used by the EPG handler)
;; ---------------------------------------------------------------------------

(defn ->xmltv-date
  "Formats an Instant as the XMLTV date string: 20240101200000 +0000"
  ([inst] (->xmltv-date inst "UTC"))
  ([^Instant inst zone-id]
   (let [zdt  (instant->zdt inst zone-id)
         fmt  (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss Z")]
     (.format zdt fmt))))
