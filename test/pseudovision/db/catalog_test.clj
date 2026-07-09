(ns pseudovision.db.catalog-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [pseudovision.db.catalog :as sut]
            [pseudovision.db.core :as db]))

;; ---------------------------------------------------------------------------
;; Bucket boundaries (pure, no DB)
;; ---------------------------------------------------------------------------

(deftest bucket->min-max-covers-15-minute-scheme
  (testing "the runtime histogram buckets in 15-minute widths up to a 210min ceiling"
    (is (= [0 15]     (#'sut/bucket->min-max "0-15min")))
    (is (= [15 30]    (#'sut/bucket->min-max "15-30min")))
    (is (= [90 105]   (#'sut/bucket->min-max "90-105min"))
        "a 91-105 minute film and a 3-hour epic no longer share a bucket")
    (is (= [195 210]  (#'sut/bucket->min-max "195-210min")))
    (is (= [210 nil]  (#'sut/bucket->min-max "210+min"))
        "the open-ended top bucket starts at the ceiling")))

(deftest bucket->min-max-unknown-label-falls-back
  (testing "an unrecognized label doesn't throw, just returns a permissive default"
    (is (= [0 nil] (#'sut/bucket->min-max "not-a-real-bucket")))))

;; ---------------------------------------------------------------------------
;; list-tag-runtime-histogram — SQL shape
;; ---------------------------------------------------------------------------

(deftest list-tag-runtime-histogram-joins-season-before-play-references-it
  (testing "the season LEFT JOIN is declared before the play join that references season.id
            (HoneySQL renders every inner join ahead of every left join regardless of
            thread order, so :play must stay a LEFT JOIN or this silently breaks)"
    (let [captured (atom nil)]
      (with-redefs [db/query-unqualified (fn [_ sqlvec] (reset! captured sqlvec) [])]
        (sut/list-tag-runtime-histogram nil nil)
        (let [sql (str/lower-case (first @captured))
              decl-idx (.indexOf sql "season.parent_id")
              ref-idx  (.indexOf sql "season.id")]
          (is (not (neg? decl-idx)) "season is joined")
          (is (not (neg? ref-idx))  "play's ON clause references season.id")
          (is (< decl-idx ref-idx)
              "season must be declared before play references it"))))))

(deftest list-tag-runtime-histogram-groups-by-tag-and-bucket
  (testing "queries group by the tag dimension AND the runtime bucket, and filter
            to positive-duration items"
    (let [captured (atom nil)]
      (with-redefs [db/query-unqualified (fn [_ sqlvec] (reset! captured sqlvec) [])]
        (sut/list-tag-runtime-histogram nil nil)
        (let [sql (str/lower-case (first @captured))]
          (is (re-find #"group by" sql))
          (is (re-find #"t\.name" sql) "groups by the tag name")
          (is (re-find #"interval '0'" sql) "only counts probed (positive-duration) items"))))))

(deftest list-tag-runtime-histogram-shapes-rows-into-per-tag-buckets
  (testing "flat (tag, bucket) rows from the DB are grouped into {:tag :buckets [...]}"
    (with-redefs [db/query-unqualified
                  (fn [_ _]
                    [{:tag "genre:movie" :label "90-105min" :item-count 12}
                     {:tag "genre:movie" :label "105-120min" :item-count 4}
                     {:tag "genre:sitcom" :label "15-30min" :item-count 200}])]
      (let [result (sut/list-tag-runtime-histogram nil nil)]
        (is (= 2 (count result)))
        (is (= "genre:movie" (:tag (first result)))
            "sorted by tag")
        (let [movie (first result)]
          (is (= 2 (count (:buckets movie))))
          (is (every? #(not (contains? % :tag)) (:buckets movie))
              "the per-row :tag is not duplicated inside each bucket")
          (is (= {:label "90-105min" :min_minutes 90 :max_minutes 105 :item_count 12}
                 (first (:buckets movie)))))
        (let [sitcom (second result)]
          (is (= "genre:sitcom" (:tag sitcom)))
          (is (= 1 (count (:buckets sitcom)))))))))

(deftest list-tag-runtime-histogram-applies-channel-scoping-tag-filter
  (testing "an optional tag-filter (e.g. channel scoping) is layered onto the query
            in addition to the per-row tag grouping"
    (let [captured (atom nil)]
      (with-redefs [db/query-unqualified (fn [_ sqlvec] (reset! captured sqlvec) [])]
        (sut/list-tag-runtime-histogram nil "channel:goldenreels")
        (is (some #{"channel:goldenreels"} (rest @captured))
            "the scoping tag is bound as a query parameter")))))

;; ---------------------------------------------------------------------------
;; build-catalog-profile — new field wiring
;; ---------------------------------------------------------------------------

(deftest build-catalog-profile-includes-tag-runtime-histograms
  (testing "the assembled CatalogProfile carries the new per-tag histogram field"
    (with-redefs [sut/count-playable-items      (fn [_ _] {:total_items 1 :total_episodes 1 :movie_count 0})
                  sut/list-show-profiles        (fn [_ _] [])
                  sut/list-genre-aggregates     (fn [_ _] [])
                  sut/list-tag-aggregates       (fn [_ _] [])
                  sut/list-runtime-histogram    (fn [_ _] [])
                  sut/list-tag-runtime-histogram (fn [_ _] [{:tag "genre:movie" :buckets []}])]
      (let [profile (sut/build-catalog-profile nil {})]
        (is (= [{:tag "genre:movie" :buckets []}]
               (:tag_runtime_histograms profile)))))))
