(ns pseudovision.scheduling.packing
  "Variety-oriented bin packing for filler breaks.

   The scheduling problem: a channel leaves recurring gaps of (often nearly
   identical) length — e.g. a 21-minute sitcom in a 30-minute block leaves a
   ~9-minute break every time.  A deterministic packer (first-fit-decreasing,
   or an exact DP) would fill each of those identical gaps with the *same*
   playlist, so every break looks alike: the longest filler that fits followed
   by the same handful of shorts.

   `pack` instead treats variety as a first-class goal:

     - It draws candidate playlists with a *seeded random* fill, so the
       internal structure of a break varies (sometimes one medium + several
       shorts, sometimes all mediums).
     - It keeps the best of K candidates, scored first on how tightly the gap
       is filled (within a tolerance) and then on a caller-supplied *recency*
       penalty, so recently-aired items are avoided.  Recency is what stops
       consecutive same-sized breaks from repeating: as items air their recency
       rises and the packer rotates to fresher content.

   The function is pure and deterministic given its inputs (`:seed` +
   `:recency`), so playout rebuilds reproduce the same breaks.  Persisting and
   updating recency across breaks is the caller's job (see the scheduler)."
  (:import [java.time Duration]
           [java.util Random]))

(defn- secs ^long [^Duration d]
  (.getSeconds d))

(defn duration-of
  "Total wall-clock duration of a playlist (seq of items)."
  ([items] (duration-of items :media-versions/duration))
  ([items duration-fn]
   (reduce (fn [^Duration acc it]
             (let [d (duration-fn it)]
               (if d (.plus acc ^Duration d) acc)))
           Duration/ZERO
           items)))

(defn- weighted-index
  "Picks an index in [0, count weights) with probability proportional to its
   weight.  All weights must be positive."
  [^Random rng weights]
  (let [total (reduce + 0.0 weights)
        r     (* (.nextDouble rng) total)
        n     (count weights)]
    (loop [i 0, acc 0.0]
      (let [acc' (+ acc (double (nth weights i)))]
        (if (or (>= acc' r) (= i (dec n)))
          i
          (recur (inc i) acc'))))))

(defn- one-fill
  "Runs a single randomized greedy fill.  At each step it considers every item
   that still fits the remaining time and picks one at random, weighted to
   favour less-recently-played items.  Continues until nothing fits.

   Returns a vector of indices into the parallel `durs`/`ids` vectors."
  [^Random rng target-secs durs ids recency allow-repeats?]
  (let [n (count durs)]
    (loop [remaining (long target-secs)
           used      #{}
           picked    []]
      (let [elig (into [] (for [i (range n)
                                :when (and (<= (long (nth durs i)) remaining)
                                           (or allow-repeats? (not (contains? used i))))]
                            i))]
        (if (empty? elig)
          picked
          (let [ws   (mapv (fn [i] (/ 1.0 (+ 1.0 (double (get recency (nth ids i) 0)))))
                           elig)
                pick (nth elig (weighted-index rng ws))]
            (recur (- remaining (long (nth durs pick)))
                   (conj used pick)
                   (conj picked pick))))))))

(defn pack
  "Selects an ordered playlist of filler items that fills `target` (a Duration)
   as fully as possible while maximizing variety.  Never exceeds `target`.

   Options (all optional):
     :seed           long      RNG seed; same inputs + seed reproduce the result.
                               Use a per-gap seed so different breaks differ.
                               Default 0.
     :recency        map       item-id -> non-negative penalty; higher means
                               more recently aired and therefore less likely to
                               be chosen / preferred.  Default {}.
     :tolerance      Duration  leftover this small or smaller is treated as a
                               perfect fill, freeing the choice to optimise for
                               variety instead.  Default 0.
     :k              int       number of randomized candidates to try; the best
                               (tightest, then least-recently-played) wins.
                               Default 12.
     :allow-repeats? bool      may an item appear more than once in one playlist.
                               Default false.
     :duration-fn    fn        item -> Duration accessor. Default
                               :media-versions/duration.
     :id-fn          fn        item -> id accessor (for recency lookup). Default
                               :media-items/id.

   Returns a vector of items (a subset of `items`, in play order)."
  [target items & {:keys [seed recency tolerance k allow-repeats? duration-fn id-fn]
                   :or   {seed 0 recency {} k 12 allow-repeats? false
                          duration-fn :media-versions/duration
                          id-fn :media-items/id}}]
  (let [target-secs (secs target)
        tol-secs    (if tolerance (secs tolerance) 0)
        usable      (into [] (filter (fn [it]
                                       (let [d (duration-fn it)]
                                         (and d
                                              (pos? (secs d))
                                              (<= (secs d) target-secs))))
                                     items))
        durs        (mapv #(secs (duration-fn %)) usable)
        ids         (mapv id-fn usable)]
    (if (or (<= target-secs 0) (empty? usable))
      []
      (let [candidates
            (for [c (range (max 1 (long k)))]
              (let [rng      (Random. (bit-xor (long seed)
                                               (* 1099511628211 (inc (long c)))))
                    idxs     (one-fill rng target-secs durs ids recency allow-repeats?)
                    used-s   (reduce + 0 (map #(long (nth durs %)) idxs))
                    leftover (- target-secs used-s)
                    eff      (max 0 (- leftover tol-secs))
                    rec-cost (reduce + 0.0 (map #(double (get recency (nth ids %) 0)) idxs))]
                {:idxs idxs :rank [eff rec-cost]}))
            best (reduce (fn [a b]
                           (if (<= (compare (:rank a) (:rank b)) 0) a b))
                         candidates)]
        (mapv #(nth usable %) (:idxs best))))))
