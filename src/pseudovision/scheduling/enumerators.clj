(ns pseudovision.scheduling.enumerators
  "Collection enumerators: stateful iterators over ordered item sequences.

   An enumerator is a map with at minimum:
     :items   — the full item vector
     :index   — current position (0-based)
     :seed    — RNG seed (for reproducible shuffle)

   The enumerator protocol is implemented as plain functions that return
   [item updated-enumerator], or nil when the sequence is exhausted
   (only relevant for non-looping modes — currently all enumerators loop).

   The `key` of an enumerator (used in the cursor) is a namespaced string
   like \"collection:42\" or \"collection:42:child:3\"."
  (:require [pseudovision.util.time :as t])
  (:import [java.util Random Collections ArrayList]))

;; ---------------------------------------------------------------------------
;; Shuffle helpers
;; ---------------------------------------------------------------------------

(defn- shuffled-indices
  "Returns a vector of 0..n-1 shuffled using the given seed."
  [n seed]
  (let [arr (ArrayList. ^java.util.Collection (range n))
        rng (Random. seed)]
    (Collections/shuffle arr rng)
    (vec arr)))

(defn- pass-order
  "The play order for one pass of a :random enumerator over `n` items:
   a seeded shuffle, adjusted so the pass doesn't open with the item the
   previous pass (seed - 1) closed on. Each pass already plays every item once
   before any repeats, but two independent shuffles could put the same item
   last and then first, airing it twice in a row.

   Only positions 0 and n/2 are ever swapped, so a pass's last item is always
   its shuffle's last item, and the previous pass's can be recomputed from its
   seed alone. With two items the order is fixed, which simply alternates."
  [n seed]
  (cond
    (<= n 1) (vec (range n))
    (= n 2)  [0 1]
    :else    (let [order     (shuffled-indices n seed)
                   prev-last (peek (shuffled-indices n (dec seed)))
                   mid       (quot n 2)]
               (if (= (first order) prev-last)
                 (assoc order 0 (nth order mid) mid (first order))
                 order))))

(defn- rotate-vec
  "Rotates vector v so that index start becomes the first element."
  [v start]
  (if (zero? start)
    v
    (into (subvec v start) (subvec v 0 start))))

;; ---------------------------------------------------------------------------
;; Enumerator constructors
;; ---------------------------------------------------------------------------

(defmulti make-enumerator
  "Builds an initial enumerator for a collection and playback order.
   Dispatch on playback-order keyword."
  (fn [_items playback-order _opts] playback-order))

(defmethod make-enumerator :chronological [items _ _opts]
  {:items (vec items) :index 0 :playback-order :chronological})

(defmethod make-enumerator :random [items _ {:keys [seed] :or {seed 0}}]
  ;; Re-shuffles on each full pass; seed advances by 1 each time so successive
  ;; passes produce different orderings.
  {:items   (vec items)
   :index   0
   :seed    seed
   :order   (pass-order (count items) seed)
   :playback-order :random})

(defmethod make-enumerator :shuffle [items _ {:keys [seed] :or {seed 0}}]
  ;; Like :random but the shuffle order is fixed for the lifetime of the seed
  ;; (i.e. the same seed always yields the same permutation across rebuilds).
  {:items   (vec items)
   :index   0
   :seed    seed
   :order   (shuffled-indices (count items) seed)
   :playback-order :shuffle})

(defmethod make-enumerator :semi-sequential [items _ {:keys [seed batch-size] :or {seed 0 batch-size 5}}]
  ;; Play N items sequentially, then jump to a random position and repeat.
  ;; batch-offset tracks which batch we're on (for deterministic random jumps).
  {:items            (vec items)
   :index            0
   :seed             seed
   :batch-size       batch-size
   :batch-offset     0
   :within-batch-idx 0
   :playback-order   :semi-sequential})

(defmethod make-enumerator :season-episode [items _ _opts]
  ;; Items are pre-sorted by (season-id, episode-position) so that next-item
  ;; can delegate to the plain chronological walk.
  {:items          (vec (sort-by (juxt :media-items/parent_id
                                       :media-items/position) items))
   :index          0
   :playback-order :season-episode})

(defmethod make-enumerator :default [items order _opts]
  ;; Unknown orders fall through to a plain chronological walk.
  {:items (vec items) :index 0 :playback-order order})

;; ---------------------------------------------------------------------------
;; next-item: advance the enumerator one step, returning [item enumerator]
;; ---------------------------------------------------------------------------

(defmulti next-item*
  "One step of an enumerator: [item updated-enumerator]. Wraps around on
   exhaustion. Call `next-item`, which also records the item drawn."
  (fn [enumerator] (:playback-order enumerator)))

(defmethod next-item* :chronological [{:keys [items index] :as e}]
  (let [n     (count items)
        idx   (mod index n)
        item  (nth items idx)]
    [item (assoc e :index (inc index))]))

(defmethod next-item* :random [{:keys [items index order seed] :as e}]
  ;; On each new pass (pos wraps to 0) generate a fresh shuffle with an
  ;; incremented seed so successive passes differ.
  (let [n   (count items)
        pos (mod index n)
        [order' seed'] (if (zero? pos)
                         (let [new-seed (+ seed 1)]
                           [(pass-order n new-seed) new-seed])
                         [order seed])
        item (nth items (nth order' pos))]
    [item (assoc e :index (inc index) :order order' :seed seed')]))

(defmethod next-item* :shuffle [{:keys [items index order] :as e}]
  (let [n    (count items)
        idx  (mod index n)
        item (nth items (nth order idx))]
    [item (assoc e :index (inc index))]))

(defmethod next-item* :semi-sequential [{:keys [items index seed batch-size batch-offset within-batch-idx batch-start] :as e}]
  ;; Play batch-size items sequentially from batch-start, then jump to a random
  ;; start and repeat. (The position used to be index + within-batch-idx, and
  ;; both advanced every draw, so a batch played every other item.)
  (let [n     (count items)
        start (or batch-start 0)]
    (if (>= within-batch-idx batch-size)
      ;; Batch complete - pick new random start position
      (let [new-offset  (inc batch-offset)
            rng         (Random. (+ seed new-offset))
            new-start   (.nextInt rng n)
            item        (nth items new-start)]
        [item (assoc e :index (inc index)
                       :batch-offset new-offset
                       :batch-start new-start
                       :within-batch-idx 1)])
      ;; Continue in current batch
      (let [item (nth items (mod (+ start within-batch-idx) n))]
        [item (assoc e :index (inc index)
                       :within-batch-idx (inc within-batch-idx))]))))

(defmethod next-item* :season-episode [e]
  ((get-method next-item* :chronological) e))

(defmethod next-item* :default [e]
  ((get-method next-item* :chronological) e))

(defn next-item
  "Returns [item updated-enumerator], wrapping around on exhaustion. The
   enumerator remembers the id of the item drawn (:last-id), so a restored
   enumerator can resume after that item even when the pool has changed size
   since (see `cursor->enumerator`)."
  [e]
  (let [[item e'] (next-item* e)]
    [item (assoc e' :last-id (:media-items/id item))]))

;; ---------------------------------------------------------------------------
;; Cursor serialisation
;; ---------------------------------------------------------------------------

(defn- play-position
  "Where `id` sits in the enumerator's play order: the n such that drawing at
   position n returns that item, or nil when it isn't in the pool. Only the
   orders that walk a fixed sequence have one."
  [{:keys [items order playback-order]} id]
  (when (some? id)
    (let [order-kw (keyword playback-order)
          at       (fn [pos] (case order-kw
                               (:random :shuffle) (nth items (nth order pos))
                               (nth items pos)))]
      (when-not (= :semi-sequential order-kw)
        (first (filter #(= id (:media-items/id (at %))) (range (count items))))))))

(defn enumerator->cursor
  "Extracts the serializable resumption state from an enumerator: position,
   seed, order name, pool size and the id of the last item drawn. The index
   is stored within the pool (mod its size); it used to grow with every draw,
   so any change in the pool's size moved the position by index / size."
  [{:keys [items index seed playback-order batch-size batch-offset within-batch-idx
           batch-start last-id]}]
  (let [n (count items)]
    (cond-> {:index          (if (pos? n) (mod index n) index)
             :seed           (or seed 0)
             :playback-order (name playback-order)
             :n              n}
      (some? last-id)  (assoc :last-id last-id)
      batch-size       (assoc :batch-size batch-size)
      batch-offset     (assoc :batch-offset batch-offset)
      within-batch-idx (assoc :within-batch-idx within-batch-idx)
      batch-start      (assoc :batch-start batch-start))))

(defn cursor->enumerator
  "Rebuilds an enumerator from its cursor snapshot.

   When the snapshot names the last item drawn and that item is still in the
   pool, the enumerator resumes right after it, so a series stays on the next
   episode when episodes are added, go missing or are filtered out. Otherwise
   the saved index is used, as before. A random pass that resumes after its
   last item in a pool of a new size keeps going in the new pass order; the
   repeat window covers the items it may meet twice."
  [items {:keys [index seed playback-order batch-size batch-offset within-batch-idx
                 batch-start last-id]}]
  (let [order (keyword playback-order)
        opts  (cond-> {:seed seed}
                batch-size (assoc :batch-size batch-size))
        e     (make-enumerator items order opts)
        e     (cond-> (assoc e :index index)
                (some? last-id)  (assoc :last-id last-id)
                batch-offset     (assoc :batch-offset batch-offset)
                within-batch-idx (assoc :within-batch-idx within-batch-idx)
                batch-start      (assoc :batch-start batch-start))]
    (if-let [pos (play-position e last-id)]
      (assoc e :index (inc pos))
      e)))
