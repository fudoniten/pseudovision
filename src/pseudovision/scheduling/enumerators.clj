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
  ;; Re-shuffle each pass; index just counts within the current shuffle.
  {:items   (vec items)
   :index   0
   :seed    seed
   :order   (shuffled-indices (count items) seed)
   :playback-order :random})

(defmethod make-enumerator :shuffle [items _ {:keys [seed] :or {seed 0}}]
  ;; Like :random but uses a consistent shuffle order across rebuilds.
  {:items   (vec items)
   :index   0
   :seed    seed
   :order   (shuffled-indices (count items) seed)
   :playback-order :shuffle})

(defmethod make-enumerator :season-episode [items _ _opts]
  ;; Sort by parent (season) position then episode position.
  {:items          (vec (sort-by (juxt :media_items/parent_id
                                       :media_items/position) items))
   :index          0
   :playback-order :season-episode})

(defmethod make-enumerator :default [items order _opts]
  ;; Fall through to chronological for unimplemented orders.
  {:items (vec items) :index 0 :playback-order order})

;; ---------------------------------------------------------------------------
;; next-item: advance the enumerator one step, returning [item enumerator]
;; ---------------------------------------------------------------------------

(defmulti next-item
  "Returns [item updated-enumerator].  Wraps around on exhaustion."
  (fn [enumerator] (:playback-order enumerator)))

(defmethod next-item :chronological [{:keys [items index] :as e}]
  (let [n     (count items)
        idx   (mod index n)
        item  (nth items idx)]
    [item (assoc e :index (inc index))]))

(defmethod next-item :random [{:keys [items index order seed] :as e}]
  (let [n   (count items)
        pos (mod index n)
        ;; Re-shuffle when we wrap around to the start of a new pass
        [order' seed'] (if (zero? pos)
                         (let [new-seed (+ seed 1)]
                           [(shuffled-indices n new-seed) new-seed])
                         [order seed])
        item (nth items (nth order' pos))]
    [item (assoc e :index (inc index) :order order' :seed seed')]))

(defmethod next-item :shuffle [{:keys [items index order] :as e}]
  (let [n    (count items)
        idx  (mod index n)
        item (nth items (nth order idx))]
    [item (assoc e :index (inc index))]))

(defmethod next-item :season-episode [e]
  ((get-method next-item :chronological) e))

(defmethod next-item :default [e]
  ((get-method next-item :chronological) e))

;; ---------------------------------------------------------------------------
;; Cursor serialisation
;; ---------------------------------------------------------------------------

(defn enumerator->cursor [{:keys [index seed playback-order]}]
  {:index          index
   :seed           (or seed 0)
   :playback-order (name playback-order)})

(defn cursor->enumerator [items {:keys [index seed playback-order]}]
  (let [order (keyword playback-order)
        e     (make-enumerator items order {:seed seed})]
    (assoc e :index index)))
