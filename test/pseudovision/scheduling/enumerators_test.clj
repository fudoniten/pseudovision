(ns pseudovision.scheduling.enumerators-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.scheduling.enumerators :as sut]))

(def ^:private items
  [{:media_items/id 1 :title "A"}
   {:media_items/id 2 :title "B"}
   {:media_items/id 3 :title "C"}])

(deftest chronological-enumerator
  (testing "returns items in order, wrapping around"
    (let [e  (sut/make-enumerator items :chronological {})
          [i0 e1] (sut/next-item e)
          [i1 e2] (sut/next-item e1)
          [i2 e3] (sut/next-item e2)
          [i3 _]  (sut/next-item e3)]   ; wraps to A
      (is (= 1 (:media_items/id i0)))
      (is (= 2 (:media_items/id i1)))
      (is (= 3 (:media_items/id i2)))
      (is (= 1 (:media_items/id i3)) "wraps around to first item"))))

(deftest shuffle-enumerator
  (testing "returns all items exactly once per pass"
    (let [e      (sut/make-enumerator items :shuffle {:seed 42})
          [a e1] (sut/next-item e)
          [b e2] (sut/next-item e1)
          [c _]  (sut/next-item e2)
          ids    (set (map :media_items/id [a b c]))]
      (is (= #{1 2 3} ids) "all 3 items appear exactly once")))

  (testing "same seed produces same order"
    (let [e1  (sut/make-enumerator items :shuffle {:seed 99})
          e2  (sut/make-enumerator items :shuffle {:seed 99})
          [i1 _] (sut/next-item e1)
          [i2 _] (sut/next-item e2)]
      (is (= (:media_items/id i1) (:media_items/id i2))
          "deterministic with same seed")))

  (testing "different seeds produce different first items (usually)"
    (let [seeds (range 10)
          firsts (for [s seeds]
                   (let [e (sut/make-enumerator items :shuffle {:seed s})
                         [item _] (sut/next-item e)]
                     (:media_items/id item)))]
      ;; Not all firsts should be identical
      (is (> (count (set firsts)) 1)
          "different seeds produce at least some variation"))))

(deftest cursor-roundtrip
  (testing "cursor serialisation preserves position"
    (let [e       (sut/make-enumerator items :chronological {})
          [_ e1]  (sut/next-item e)
          [_ e2]  (sut/next-item e1)
          saved   (sut/enumerator->cursor e2)
          restored (sut/cursor->enumerator items saved)
          [item _] (sut/next-item restored)]
      (is (= 3 (:media_items/id item))
          "restoring at index 2 yields the third item"))))

(deftest random-never-repeats-across-pass-boundaries
  (testing "every pass plays each item once, and no pass opens with the item
            the previous pass closed on (no back-to-back airing)"
    (doseq [n [2 3 4 7 20] seed (range 25)]
      (let [pool  (mapv (fn [i] {:media_items/id i}) (range n))
            plays (loop [e (sut/make-enumerator pool :random {:seed seed})
                         acc []]
                    (if (= (count acc) (* 6 n))
                      acc
                      (let [[item e'] (sut/next-item e)]
                        (recur e' (conj acc (:media_items/id item))))))]
        (doseq [pass (partition n plays)]
          (is (= (set (range n)) (set pass))
              (str "pass is a permutation (n=" n ", seed=" seed ")")))
        (is (not-any? (fn [[a b]] (= a b)) (partition 2 1 plays))
            (str "no item airs twice in a row (n=" n ", seed=" seed ")")))))

  (testing "a restored enumerator continues the same order mid-pass"
    (let [pool (mapv (fn [i] {:media_items/id i}) (range 9))
          step (fn [e k] (reduce (fn [[acc e] _]
                                   (let [[item e'] (sut/next-item e)]
                                     [(conj acc (:media_items/id item)) e']))
                                 [[] e] (range k)))
          [_ e]        (step (sut/make-enumerator pool :random {:seed 3}) 13)
          [straight _] (step e 10)
          [resumed _]  (step (sut/cursor->enumerator pool (sut/enumerator->cursor e)) 10)]
      (is (= straight resumed)))))

(defn- eps [ids] (mapv (fn [id] {:media-items/id id}) ids))

(defn- draw-ids [e n]
  (loop [e e, i 0, out []]
    (if (= i n) [out e] (let [[it e'] (sut/next-item e)] (recur e' (inc i) (conj out (:media-items/id it)))))))

(deftest chronological-resumes-after-last-item-when-pool-changes
  (let [pool        (eps (range 1 101))
        ;; play past a full pass so the raw draw count is far above the pool size
        [_ e]       (draw-ids (sut/make-enumerator pool :chronological {}) 130)
        saved       (sut/enumerator->cursor e)]
    (is (= 30 (:last-id saved)))
    (is (= 30 (:index saved)) "the index is stored within the pool")
    (testing "an episode added at the end of the series"
      (is (= [31 32] (first (draw-ids (sut/cursor->enumerator (eps (range 1 102)) saved) 2)))))
    (testing "an earlier episode removed"
      (is (= [31 32] (first (draw-ids (sut/cursor->enumerator (eps (remove #{5} (range 1 101))) saved) 2)))))
    (testing "the last-aired episode itself removed: falls back to the index"
      (is (= [32] (first (draw-ids (sut/cursor->enumerator (eps (remove #{30} (range 1 101))) saved) 1)))))
    (testing "an old cursor without :last-id still restores by index"
      (is (= [31] (first (draw-ids (sut/cursor->enumerator pool (dissoc saved :last-id)) 1)))))))

(deftest random-resumes-after-last-item
  (let [pool    (eps (range 1 21))
        [a e]   (draw-ids (sut/make-enumerator pool :random {:seed 7}) 25)
        [b _]   (draw-ids e 10)
        [c _]   (draw-ids (sut/cursor->enumerator pool (sut/enumerator->cursor e)) 10)]
    (is (= b c) "a same-size pool resumes exactly where it left off")))

(deftest semi-sequential-plays-consecutive-items-in-a-batch
  (let [[ids _] (draw-ids (sut/make-enumerator (eps (range 1 21)) :semi-sequential
                                               {:seed 1 :batch-size 4}) 4)]
    (is (= [1 2 3 4] ids))))
