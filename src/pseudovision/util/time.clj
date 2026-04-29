(ns pseudovision.util.time
  "Thin wrappers around tick for the time operations used by the scheduler."
  (:require [tick.core :as t])
  (:import [java.time Duration DayOfWeek Instant ZonedDateTime ZoneId]))

(defn now [] (t/now))

(defn instant->zdt
  "Converts an Instant to a ZonedDateTime, defaulting to UTC when no zone is given."
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

(defn add-days
  "Add N days to an instant."
  [^Instant inst days]
  (.plus inst (Duration/ofDays days)))

(defn add-seconds
  "Add N seconds to an instant."
  [^Instant inst seconds]
  (.plusSeconds inst seconds))

(defn after?
  "Returns true if instant a is after instant b."
  [^Instant a ^Instant b]
  (.isAfter a b))

(defn instant->str
  "Convert instant to ISO string for logging."
  [^Instant inst]
  (.toString inst))

;; ---------------------------------------------------------------------------
;; Day-of-week bitmask helpers  (used by the scheduler for days_of_week slots)
;; ---------------------------------------------------------------------------

(def ^:private dow->bit
  "Maps java.time.DayOfWeek to its bitmask value.
   Monday=1, Tuesday=2, Wednesday=4, Thursday=8,
   Friday=16, Saturday=32, Sunday=64."
  {DayOfWeek/MONDAY    1
   DayOfWeek/TUESDAY   2
   DayOfWeek/WEDNESDAY 4
   DayOfWeek/THURSDAY  8
   DayOfWeek/FRIDAY    16
   DayOfWeek/SATURDAY  32
   DayOfWeek/SUNDAY    64})

(defn day-of-week-bit
  "Returns the bitmask bit for the day-of-week of `inst` in `zone-id`."
  [^Instant inst zone-id]
  (let [zdt (.atZone inst (ZoneId/of zone-id))]
    (get dow->bit (.getDayOfWeek zdt) 0)))

(defn fires-on-day?
  "Returns true if `days-of-week` bitmask includes the day of `inst` in `zone-id`.
   A nil or zero mask is treated as 127 (every day)."
  [days-of-week ^Instant inst zone-id]
  (let [mask (if (or (nil? days-of-week) (zero? days-of-week)) 127 days-of-week)
        bit  (day-of-week-bit inst zone-id)]
    (not (zero? (bit-and mask bit)))))

(defn next-dow-occurrence
  "Returns the next Instant at or after `after` that satisfies `days-of-week`
   and falls at time-of-day `tod` (a java.time.Duration offset from midnight).
   Searches forward day by day; at most 7 iterations.
   A nil or zero mask is treated as 127 (every day)."
  [^Duration tod days-of-week ^Instant after zone-id]
  (let [mask    (if (or (nil? days-of-week) (zero? days-of-week)) 127 days-of-week)
        zone    (ZoneId/of zone-id)]
    (loop [^ZonedDateTime day (.atZone after zone)]
      (let [midnight  (-> day .toLocalDate (.atStartOfDay zone) .toInstant)
            fire-time (.plus midnight tod)
            bit       (get dow->bit (.getDayOfWeek day) 0)]
        (if (and (not (zero? (bit-and mask bit)))
                 (.isAfter fire-time after))
          fire-time
          (recur (.plusDays day 1)))))))

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
