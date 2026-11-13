(ns pseudovision.scheduling.cursor
  "Playout cursor: the build engine's full resumption state.
   Serialises to/from the JSONB blob stored in playouts.cursor."
  (:require [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [clojure.string :as str]
            [pseudovision.scheduling.enumerators :as enum]
            [pseudovision.util.sql :as sql-util]
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

(declare strip-rewind)

;; Enumerator states are keyed by strings like "collection:7". Stored as JSON
;; object keys, they came back through the JSONB column reader (which turns
;; every key into a kebab-case keyword) as :collection:-7, so no saved state
;; was ever found again: every build restarted every series at its first
;; episode and every random pool at the same shuffle. They are now stored as
;; a list of {key, state} entries, whose keys are plain strings in values.

(defn- states->stored [m]
  (mapv (fn [[k v]] {:key k :state v}) m))

(defn- legacy-key
  "The original key of an enumerator state read back as a kebab-case keyword
   (`:collection:-7` -> \"collection:7\")."
  [k]
  (if (keyword? k)
    (str/replace (name k) #":-(?=\d)" ":")
    k))

(defn- stored->states [x]
  (cond
    (map? x)        (into {} (map (fn [[k v]] [(legacy-key k) v])) x)
    (sequential? x) (into {} (map (juxt :key :state)) x)
    :else           {}))

(defn ->json
  "Serializes cursor to a JSONB PGobject; Instant fields are converted to ISO strings."
  [cursor]
  (sql-util/->jsonb
   (-> (strip-rewind cursor)
       (update :enumerator-states states->stored)
       (update :next-start      #(some-> % .toString))
       (update :block-ends-at   #(some-> % .toString)))))

(defn <-json
  "Deserializes a cursor from its stored JSONB string, parsing Instant fields back from ISO strings."
  [s]
  (when s
    (let [parsed (if (string? s)
                   ;; the same key handling as the JSONB column reader
                   (json/parse-string s csk/->kebab-case-keyword)
                   s)]  ; Already deserialized (next.jdbc returns JSONB as maps)
      (-> parsed
          (update :enumerator-states stored->states)
          (update :next-start      #(some-> % Instant/parse))
          (update :block-ends-at   #(some-> % Instant/parse))))))

(defn advance-slot
  "Advances slot-index by 1, wrapping around modulo n-slots."
  [cursor n-slots]
  (update cursor :slot-index #(mod (inc %) n-slots)))

(defn bump-guide-group [cursor]
  (update cursor :next-guide-group inc))

;; ---------------------------------------------------------------------------
;; Rewinding (reset rebuilds)
;;
;; A reset rebuild regenerates time the saved cursor had already built past,
;; so its enumerators point at the old horizon. Rather than drop them (which
;; restarted every series at episode 1) the build marks them to rewind: the
;; first time a marked enumerator is restored against its pool, it resumes
;; after the pool's item that aired last before the rebuild point.
;; ---------------------------------------------------------------------------

(defn mark-rewind
  "Marks every saved enumerator state in `cursor` to rewind on its next
   restore (see `get-enumerator`)."
  [cursor]
  (update cursor :enumerator-states
          (fn [m] (into {} (map (fn [[k v]] [k (assoc v :rewind true)])) m))))

(defn with-rewind-airings
  "Attaches the playout's past content airings (media-item-id -> start
   Instants, as playout-db/recent-content-airings returns) that marked
   enumerators rewind against. Not serialised."
  [cursor airings]
  (assoc cursor ::rewind-airings airings))

(defn- strip-rewind [cursor]
  (-> cursor
      (dissoc ::rewind-airings)
      (update :enumerator-states
              (fn [m] (when m (into {} (map (fn [[k v]] [k (dissoc v :rewind)])) m))))))

(defn- last-aired-id
  "The id of the item in `items` with the latest airing in `airings`."
  [items airings]
  (some->> items
           (keep (fn [it]
                   (when-let [starts (seq (get airings (:media-items/id it)))]
                     [(:media-items/id it) (reduce (fn [^Instant a ^Instant b] (if (.isAfter b a) b a))
                                                   starts)])))
           seq
           (apply max-key (fn [[_ ^Instant at]] (.toEpochMilli at)))
           first))

(defn- rewound
  "`saved` set to resume after the last item of `items` that aired, for an
   order that walks a fixed sequence. Random orders keep their position (the
   repeat window keeps recent items from coming back). With nothing aired from
   the pool the saved state is kept as it is, so a reset never sends a series
   back to its first episode."
  [saved items airings]
  (let [order (keyword (:playback-order saved))
        id    (when-not (#{:random :shuffle} order) (last-aired-id items airings))]
    (cond-> (dissoc saved :rewind)
      id (assoc :last-id id))))

(defn get-enumerator
  "Returns the enumerator for collection-key, restoring from saved cursor state
   if present, or constructing a fresh one with the given playback-order.
   A state marked by `mark-rewind` is first rewound to the last item that
   aired (see `rewound`). opts may include :seed and :batch-size."
  [cursor collection-key items playback-order & [opts]]
  (let [saved (get-in cursor [:enumerator-states collection-key])
        saved (if (and saved (:rewind saved))
                (rewound saved items (::rewind-airings cursor))
                saved)]
    (if saved
      (enum/cursor->enumerator items saved)
      (enum/make-enumerator items playback-order (or opts {})))))

(defn save-enumerator
  "Persists the enumerator's resumable state back into the cursor under collection-key."
  [cursor collection-key enumerator]
  (assoc-in cursor [:enumerator-states collection-key]
            (enum/enumerator->cursor enumerator)))
