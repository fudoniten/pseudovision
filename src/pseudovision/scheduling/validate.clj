(ns pseudovision.scheduling.validate
  "Overlap checks for a playout timeline.

   The build engine lays events end to end, so in a correct timeline every
   event finishes at or before the next one starts. Three things have broken
   that in production: two builds writing the same playout (every event
   doubled), filler that ran past the end of its gap (a fallback break running
   tens of minutes into the next program), and filler whose total length was
   rounded down to whole seconds while each clip kept its fractional length (a
   post break ending a second or so after the hour).

   These functions work on the event maps the builder produces (unqualified
   keys :start-at, :finish-at, :kind) and are pure, so the build can run them
   before anything is written."
  (:import [java.time Duration Instant]))

(def filler-kinds
  "event_kind values that are filler rather than primary content. A filler
   event that runs into the next event can be cut short without harm; content
   can't."
  #{"pre" "mid" "post" "pad" "tail" "fallback" "bumper"})

(defn kind-name
  "An event's kind as a string. Build events carry a PGobject, which prints as
   its value."
  [ev]
  (some-> (:kind ev) str))

(defn filler? [ev]
  (contains? filler-kinds (kind-name ev)))

(defn- start  ^Instant [ev] (:start-at ev))
(defn- finish ^Instant [ev] (:finish-at ev))

(defn- positive? [ev]
  (.isAfter (finish ev) (start ev)))

(defn sort-events
  "Events in play order: by start, then by finish."
  [events]
  (sort-by (juxt start finish) events))

(defn clamp-to
  "Cuts `events` (laid out in order from some start) so none runs past
   `until`: events starting at or after `until` are dropped and the last one is
   trimmed to end there. A nil `until` returns `events` unchanged."
  [events ^Instant until]
  (if (nil? until)
    events
    (into []
          (comp (filter #(.isBefore (start %) until))
                (map #(if (.isAfter (finish %) until) (assoc % :finish-at until) %))
                (filter positive?))
          events)))

(defn trim-filler-overruns
  "Sorts `events` and trims every filler event that runs into the event after
   it, so it finishes where the next one starts. Events left with no length
   (including any zero-length event the builder produced) are dropped.
   Content is never trimmed: a content overlap is a real bug and is left for
   `overlaps` to report."
  [events]
  (let [v (vec (filter positive? (sort-events events)))
        n (count v)]
    (into []
          (comp (map-indexed
                 (fn [i ev]
                   (let [nxt (when (< (inc i) n) (nth v (inc i)))]
                     (if (and nxt (filler? ev) (.isAfter (finish ev) (start nxt)))
                       (assoc ev :finish-at (start nxt))
                       ev))))
                (filter positive?))
          v)))

(defn overlaps
  "Every pair of consecutive events (in play order) where the first finishes
   after the second starts. Returns maps of
   {:first ev :second ev :overlap Duration}."
  [events]
  (let [v (vec (sort-events events))]
    (into []
          (keep (fn [[a b]]
                  (when (.isAfter (finish a) (start b))
                    {:first   a
                     :second  b
                     :overlap (Duration/between (start b) (finish a))})))
          (partition 2 1 v))))

(defn describe
  "A short, loggable description of an overlap (as returned by `overlaps` or
   the database check)."
  [{:keys [first second overlap]}]
  {:first-kind    (kind-name first)
   :first-start   (str (start first))
   :first-finish  (str (finish first))
   :second-kind   (kind-name second)
   :second-start  (str (start second))
   :overlap-secs  (/ (.toMillis ^Duration overlap) 1000.0)})
