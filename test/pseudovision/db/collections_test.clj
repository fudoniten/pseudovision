(ns pseudovision.db.collections-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.db.collections :as sut]
            [pseudovision.db.core :as db]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- coll
  "Minimal collection map."
  [kind config]
  {:collections/id 1 :collections/kind kind :collections/config config})

(defn- capture-sql!
  "Runs f with db/query stubbed to capture the SQL string and return []."
  [f]
  (let [captured (atom nil)]
    (with-redefs [db/query (fn [_ sql] (reset! captured sql) [])]
      (f))
    @captured))

;; ---------------------------------------------------------------------------
;; smart-tag-clause (tested indirectly via resolve-collection :smart)
;; ---------------------------------------------------------------------------

(deftest smart-no-filters-queries-all-items
  (testing "no tags → no extra WHERE clause beyond media-type"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {"query" {"media-type" "episode"}})))]
      (is (string? (first sql)))
      (is (.contains (first sql) "media_items"))
      (is (not (.contains (first sql) "metadata_tags"))))))

(deftest smart-include-tags-all-mode
  (testing "match=all emits one EXISTS per tag"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {"query" {"include-tags" ["comedy" "short"]
                                                                     "match"        "all"}})))]
      (let [s (first sql)]
        ;; Both tags should appear as separate EXISTS subqueries
        (is (.contains s "comedy"))
        (is (.contains s "short"))
        ;; With match=all, 'comedy' and 'short' each get their own EXISTS
        (is (= 2 (count (re-seq #"EXISTS" s))))))))

(deftest smart-include-tags-any-mode
  (testing "match=any emits one EXISTS with OR"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {"query" {"include-tags" ["comedy" "short"]
                                                                     "match"        "any"}})))]
      (let [s (first sql)]
        (is (.contains s "comedy"))
        (is (.contains s "short"))
        ;; Single EXISTS (one subquery for both tags via OR)
        (is (= 1 (count (re-seq #"EXISTS" s))))))))

(deftest smart-exclude-tags
  (testing "exclude-tags emits NOT EXISTS"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {"query" {"exclude-tags" ["explicit"]}})))]
      (let [s (first sql)]
        (is (.contains s "NOT"))
        (is (.contains s "EXISTS"))
        (is (.contains s "explicit"))))))

(deftest smart-include-and-exclude-tags
  (testing "include + exclude both appear"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {"query" {"include-tags" ["comedy"]
                                                                     "exclude-tags" ["explicit"]}})))]
      (let [s (first sql)]
        (is (.contains s "comedy"))
        (is (.contains s "explicit"))
        (is (.contains s "NOT"))))))

(deftest smart-match-all-default
  (testing "match defaults to 'all' when not specified"
    (let [sql-default (capture-sql!
                        #(sut/resolve-collection nil (coll "smart" {"query" {"include-tags" ["a" "b"]}})))
          sql-explicit (capture-sql!
                         #(sut/resolve-collection nil (coll "smart" {"query" {"include-tags" ["a" "b"]
                                                                              "match"        "all"}})))]
      (is (= (first sql-default) (first sql-explicit))))))

(deftest smart-order-by-title
  (testing "order-by=title uses title column"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {"query" {"order-by" "title"}})))]
      (is (.contains (first sql) "title")))))

(deftest smart-order-by-random
  (testing "order-by=random uses RANDOM()"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {"query" {"order-by" "random"}})))]
      (is (.contains (first sql) "random()")))))

;; ---------------------------------------------------------------------------
;; smart-tag-clause unit tests (via private var)
;; ---------------------------------------------------------------------------

(deftest tag-clause-nil-when-no-tags
  (testing "returns nil when both lists are empty"
    (is (nil? (#'sut/smart-tag-clause "all" [] [])))))

(deftest tag-clause-all-returns-one-exists-per-tag
  (testing "match=all: three tags → three EXISTS clauses"
    (let [clause (#'sut/smart-tag-clause "all" ["a" "b" "c"] [])]
      (is (= :and (first clause)))
      (is (= 3 (count (rest clause))))
      (is (every? #(= :exists (first %)) (rest clause))))))

(deftest tag-clause-any-returns-single-exists
  (testing "match=any: multiple tags → one EXISTS"
    (let [clause (#'sut/smart-tag-clause "any" ["a" "b"] [])]
      (is (= :and (first clause)))
      (is (= 1 (count (rest clause))))
      (is (= :exists (first (second clause)))))))

(deftest tag-clause-exclude-returns-not-exists
  (testing "exclude-tags → [:not [:exists ...]]"
    (let [clause (#'sut/smart-tag-clause "all" [] ["nsfw"])]
      (is (= :and (first clause)))
      (let [[not-form] (rest clause)]
        (is (= :not (first not-form)))
        (is (= :exists (first (second not-form))))))))

(deftest tag-clause-include-and-exclude
  (testing "both include and exclude → two clauses"
    (let [clause (#'sut/smart-tag-clause "all" ["comedy"] ["explicit"])]
      (is (= :and (first clause)))
      (is (= 2 (count (rest clause)))))))
