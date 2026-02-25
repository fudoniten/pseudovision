(ns pseudovision.scheduling.cursor
  "Playout cursor: the build engine's full resumption state.
   Serialises to/from the JSONB blob stored in playouts.cursor."
  (:require [cheshire.core :as json]
            [pseudovision.scheduling.enumerators :as enum]
            [pseudovision.util.time :as t])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Cursor shape
;; {:next-start        Instant       ; wall-clock time of the next event to schedule
;;  :slot-index        int           ; index into the schedule's slot list
;;  :count-remaining   int|nil       ; items left in a 'count' slot
;;  :block-ends-at     Instant|nil   ; deadline for a 'block' slot
;;  :in-flood          bool          ; currently inside a 'flood' slot
;;  :in-duration-filler bool         ; currently filling dead-air with filler
;;  :next-guide-group  int           ; incrementing counter for guide groups
;;  :enumerator-states map}          ; collection-key → enumerator cursor
;; ---------------------------------------------------------------------------

(def ^:private initial-state
  {:next-start         nil
   :slot-index         0
   :count-remaining    nil
   :block-ends-at      nil
   :in-flood           false
   :in-duration-filler false
   :next-guide-group   1
   :enumerator-states  {}})

(defn init
  "Returns a fresh cursor starting at `start-time`."
  [start-time]
  (assoc initial-state :next-start start-time))

(defn ->json [cursor]
  (json/generate-string
   (-> cursor
       (update :next-start      #(some-> % .toString))
       (update :block-ends-at   #(some-> % .toString)))))

(defn <-json [s]
  (when s
    (-> (json/parse-string s true)
        (update :next-start      #(some-> % Instant/parse))
        (update :block-ends-at   #(some-> % Instant/parse)))))

(defn advance-slot [cursor n-slots]
  (update cursor :slot-index #(mod (inc %) n-slots)))

(defn bump-guide-group [cursor]
  (update cursor :next-guide-group inc))

(defn get-enumerator [cursor collection-key items playback-order]
  (let [saved (get-in cursor [:enumerator-states collection-key])]
    (if saved
      (enum/cursor->enumerator items saved)
      (enum/make-enumerator items playback-order {}))))

(defn save-enumerator [cursor collection-key enumerator]
  (assoc-in cursor [:enumerator-states collection-key]
            (enum/enumerator->cursor enumerator)))
