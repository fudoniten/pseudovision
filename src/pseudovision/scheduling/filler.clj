(ns pseudovision.scheduling.filler
  "Filler injection: selects filler items to fill gaps in the playout.

   Resolution order for each filler role:
     1. Slot-level override
     2. Channel-level default
     3. No filler (leave a gap / go offline)

   Filler item selection uses the same enumerator system as main content
   so shuffle seeds are preserved across rebuilds."
  (:require [pseudovision.db.collections :as col]
            [pseudovision.scheduling.enumerators :as enum]
            [pseudovision.util.time :as t])
  (:import [java.time Duration Instant]))

;; ---------------------------------------------------------------------------
;; Preset resolution
;; ---------------------------------------------------------------------------

(defn resolve-filler-preset
  "Returns the filler preset map for `role` by checking slot then channel."
  [role slot channel]
  (let [slot-fk    (keyword (str (name role) "-filler-id"))
        channel-fk :channels/fallback-filler-id]
    (cond
      (get slot    slot-fk)    {:id (get slot    slot-fk) :source :slot}
      (= role :fallback)       (when (get channel channel-fk)
                                 {:id (get channel channel-fk) :source :channel})
      :else                    nil)))

;; ---------------------------------------------------------------------------
;; Gap filling
;; ---------------------------------------------------------------------------

(defn fill-gap
  "Given a gap between `from` and `to`, returns a (possibly empty) seq of
   filler event maps to fill it.

   `preset`   — filler_presets row (already resolved)
   `items`    — seq of candidate media-item maps from the preset's collection
   `enumerator` — current enumerator state for this preset's collection
   `playout-id` — for stamping on event maps

   Returns {:events [event-map …] :enumerator updated-enumerator}"
  [from to preset items enumerator playout-id]
  (let [gap-duration (t/duration-between from to)]
    (cond
      ;; Nothing to fill with
      (empty? items)
      {:events [] :enumerator enumerator}

      ;; duration mode: pick items until we've filled the gap
      (= "duration" (:filler_presets/mode preset))
      (loop [cursor  from
             enum    enumerator
             events  []]
        (let [remaining (t/duration-between cursor to)]
          (if (or (.isNegative remaining)
                  (.isZero remaining)
                  (empty? (:items enum)))
            {:events events :enumerator enum}
            (let [[item enum'] (enum/next-item enum)
                  dur          (or (some-> item :media_versions/duration)
                                   (Duration/ofSeconds 0))
                  finish       (t/add-duration cursor dur)]
              (if (.isAfter finish to)
                ;; This item would overflow the gap; try shorter or stop
                {:events events :enumerator enum}
                (recur finish
                       enum'
                       (conj events {:playout-id    playout-id
                                     :media-item-id (:media_items/id item)
                                     :kind          (name (:filler_presets/role preset))
                                     :start-at      cursor
                                     :finish-at     finish
                                     :is-manual     false})))))))

      ;; count mode: pick exactly N items
      (= "count" (:filler_presets/mode preset))
      (let [n (:filler_presets/count preset 1)]
        (loop [i      0
               cursor from
               enum   enumerator
               events []]
          (if (>= i n)
            {:events events :enumerator enum}
            (let [[item enum'] (enum/next-item enum)
                  dur          (or (some-> item :media_versions/duration)
                                   (Duration/ofSeconds 0))
                  finish       (t/add-duration cursor dur)]
              (recur (inc i) finish enum'
                     (conj events {:playout-id    playout-id
                                   :media-item-id (:media_items/id item)
                                   :kind          (name (:filler_presets/role preset))
                                   :start-at      cursor
                                   :finish-at     finish
                                   :is-manual     false}))))))

      :else
      {:events [] :enumerator enumerator})))

(defn pad-to-boundary
  "Produces filler events from `from` up to the next multiple-of-n-minutes
   boundary, or up to `ceil` if that comes first."
  [from ceil n-minutes items enumerator playout-id role]
  (let [boundary (t/ceil-to-n-minutes from n-minutes)
        to       (if (.isAfter boundary ceil) ceil boundary)]
    (fill-gap from to {:filler_presets/mode  "duration"
                       :filler_presets/role  role}
              items enumerator playout-id)))
