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
