(ns pseudovision.db.filler-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.db.filler :as sut]
            [pseudovision.db.core :as db]
            [pseudovision.db.collections :as col-db]))

;; ---------------------------------------------------------------------------
;; get-filler-preset
;; ---------------------------------------------------------------------------

(deftest get-filler-preset-returns-nil-for-nil-id
  (testing "returns nil immediately for nil id, no DB call made"
    (with-redefs [db/query-one (fn [_ _] (throw (Exception. "DB should not be called")))]
      (is (nil? (sut/get-filler-preset nil nil))))))

(deftest get-filler-preset-queries-db-by-id
  (testing "fetches the filler_presets row for a given id"
    (let [row {:filler-presets/id 1 :filler-presets/name "Bumpers" :filler-presets/role "tail"}]
      (with-redefs [db/query-one (fn [_ _] row)]
        (is (= row (sut/get-filler-preset nil 1)))))))

(deftest get-filler-preset-returns-nil-when-not-found
  (testing "returns nil when the DB row does not exist"
    (with-redefs [db/query-one (fn [_ _] nil)]
      (is (nil? (sut/get-filler-preset nil 999))))))

;; ---------------------------------------------------------------------------
;; load-filler-items
;; ---------------------------------------------------------------------------

(deftest load-filler-items-empty-when-no-source
  (testing "returns empty seq when preset has no collection or media_item"
    (is (= [] (sut/load-filler-items nil {})))))

(deftest load-filler-items-collection-backed
  (testing "resolves the preset's collection and returns its items"
    (let [items  [{:media-items/id 1} {:media-items/id 2}]
          preset {:filler-presets/collection-id 5}
          coll   {:collections/id 5 :collections/kind "manual"}]
      (with-redefs [db/query-one              (fn [_ _] coll)
                    col-db/resolve-collection  (fn [_ _] items)]
        (is (= items (sut/load-filler-items nil preset)))))))

(deftest load-filler-items-collection-not-found-returns-empty
  (testing "returns empty seq when the collection row is missing"
    (let [preset {:filler-presets/collection-id 99}]
      (with-redefs [db/query-one (fn [_ _] nil)]
        (is (= [] (sut/load-filler-items nil preset)))))))

(deftest load-filler-items-media-item-backed
  (testing "wraps single media item in a vector"
    (let [item   {:media-items/id 42 :media-versions/duration 300}
          preset {:filler-presets/media-item-id 42}]
      (with-redefs [db/query-one (fn [_ _] item)]
        (is (= [item] (sut/load-filler-items nil preset)))))))

(deftest load-filler-items-media-item-not-found-returns-empty
  (testing "returns empty seq when the media item row is missing"
    (let [preset {:filler-presets/media-item-id 99}]
      (with-redefs [db/query-one (fn [_ _] nil)]
        (is (= [] (sut/load-filler-items nil preset)))))))

(deftest load-filler-items-collection-takes-precedence-over-media-item
  (testing "collection-id is checked before media-item-id"
    (let [items  [{:media-items/id 1}]
          preset {:filler-presets/collection-id  5
                  :filler-presets/media-item-id  99}
          coll   {:collections/id 5 :collections/kind "manual"}
          called (atom nil)]
      (with-redefs [db/query-one              (fn [_ q]
                                                (reset! called q)
                                                coll)
                    col-db/resolve-collection  (fn [_ _] items)]
        (sut/load-filler-items nil preset)
        ;; query should target collections table, not media_items
        (is (some #(= % :collections) (flatten @called))
            "should query collections, not media_items")))))
