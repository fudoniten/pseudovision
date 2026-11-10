(ns pseudovision.media.prune-test
  "Pruning items deleted from Jellyfin: the pure plan, and the listing
   completeness check that gates it."
  (:require [clojure.test                :refer [deftest is testing]]
            [pseudovision.media.jellyfin :as jellyfin]
            [pseudovision.media.prune    :as sut])
  (:import [java.time Duration Instant]))

(def now (Instant/parse "2026-11-10T04:00:00Z"))

(defn- hours-ago [h] (.minus now (Duration/ofHours h)))

(defn- items
  "n known items with remote keys k0..k(n-1), none marked missing."
  [n]
  (mapv (fn [i] {:id i :remote-key (str "k" i) :missing-since nil}) (range n)))

(defn- keys-of [known] (into #{} (map :remote-key) known))

;; ---------------------------------------------------------------------------
;; plan
;; ---------------------------------------------------------------------------

(deftest nothing-missing-is-a-no-op
  (let [known (items 50)
        p     (sut/plan known (keys-of known) now {})]
    (is (= {:restore [] :mark [] :delete [] :waiting [] :missing 0 :refused nil} p))))

(deftest a-deleted-item-is-marked-first-not-deleted
  (testing "the first scan that misses an item only hides it"
    (let [known (items 50)
          p     (sut/plan known (disj (keys-of known) "k7") now {})]
      (is (= [7] (:mark p)))
      (is (= [] (:delete p)))
      (is (nil? (:refused p))))))

(deftest a-marked-item-is-deleted-after-the-grace-period
  (let [known (assoc-in (items 50) [7 :missing-since] (hours-ago 21))
        p     (sut/plan known (disj (keys-of known) "k7") now {})]
    (is (= [7] (:delete p)))
    (is (= [] (:mark p)))))

(deftest a-marked-item-waits-inside-the-grace-period
  (let [known (assoc-in (items 50) [7 :missing-since] (hours-ago 2))
        p     (sut/plan known (disj (keys-of known) "k7") now {})]
    (is (= [] (:delete p)))
    (is (= [7] (:waiting p)))
    (is (= 1 (:missing p)))))

(deftest a-returning-item-is-restored
  (let [known (assoc-in (items 50) [7 :missing-since] (hours-ago 30))
        p     (sut/plan known (keys-of known) now {})]
    (is (= [7] (:restore p)))
    (is (= [] (:delete p)))))

(deftest an-empty-listing-is-refused
  (testing "Jellyfin answering 200 with no items never prunes, even a small library"
    (let [known (assoc-in (items 3) [0 :missing-since] (hours-ago 48))
          p     (sut/plan known #{} now {})]
      (is (re-find #"listed no items" (:refused p)))
      (is (= [] (:mark p)))
      (is (= [] (:delete p))))))

(deftest too-large-a-fraction-is-refused
  (let [known (items 100)
        seen  (into #{} (map #(str "k" %)) (range 70))   ; 30 missing = 30%
        p     (sut/plan known seen now {})]
    (is (re-find #"30 of 100 items \(30%\)" (:refused p)))
    (is (= [] (:mark p))))
  (testing "force? lifts the guard"
    (let [known (items 100)
          seen  (into #{} (map #(str "k" %)) (range 70))
          p     (sut/plan known seen now {:force? true})]
      (is (nil? (:refused p)))
      (is (= 30 (count (:mark p)))))))

(deftest small-deletions-pass-the-fraction-guard
  (testing "up to guard-floor items can go even from a small library"
    (let [known (items 20)
          seen  (into #{} (map #(str "k" %)) (range 12))  ; 8 missing = 40%
          p     (sut/plan known seen now {})]
      (is (nil? (:refused p)))
      (is (= 8 (count (:mark p)))))))

(deftest too-many-items-is-refused
  (let [known (items 10000)
        seen  (into #{} (map #(str "k" %)) (range 600 10000)) ; 600 missing = 6%
        p     (sut/plan known seen now {})]
    (is (re-find #"600 items are missing" (:refused p)))))

(deftest refused-plan-still-restores
  (let [known (-> (items 100)
                  (assoc-in [0 :missing-since] (hours-ago 30)))
        seen  (into #{} (map #(str "k" %)) (range 50))
        p     (sut/plan known seen now {})]
    (is (some? (:refused p)))
    (is (= [0] (:restore p)))))

;; ---------------------------------------------------------------------------
;; fetch-all-items completeness
;; ---------------------------------------------------------------------------

(defn- paged
  "Stub for fetch-items-page serving `ids` with `total`, or `overrides`
   keyed by start index."
  [ids total overrides]
  (fn [_ _ _ start limit]
    (or (get overrides start)
        {:status 200
         :body   {:TotalRecordCount total
                  :Items (mapv (fn [id] {:Id id})
                               (take limit (drop start ids)))}})))

(def ids (mapv #(str "jf" %) (range 250)))

(deftest a-full-listing-is-complete
  (with-redefs [jellyfin/fetch-items-page (paged ids 250 {})]
    (let [r (jellyfin/fetch-all-items "http://jf" "key" "lib")]
      (is (:complete? r))
      (is (= 250 (count (:items r)))))))

(deftest a-failed-page-is-incomplete
  (testing "a connection error mid-listing"
    (with-redefs [jellyfin/fetch-items-page
                  (paged ids 250 {100 {:error (java.net.SocketTimeoutException. "timed out")}})]
      (let [r (jellyfin/fetch-all-items "http://jf" "key" "lib")]
        (is (not (:complete? r)))
        (is (= 100 (count (:items r))) "items fetched so far are still returned")
        (is (re-find #"request failed at item 100" (:reason r))))))
  (testing "an error status"
    (with-redefs [jellyfin/fetch-items-page (paged ids 250 {200 {:status 503 :body nil}})]
      (is (re-find #"HTTP 503" (:reason (jellyfin/fetch-all-items "http://jf" "key" "lib"))))))
  (testing "Jellyfin down from the start"
    (with-redefs [jellyfin/fetch-items-page (paged ids 250 {0 {:status 500 :body nil}})]
      (let [r (jellyfin/fetch-all-items "http://jf" "key" "lib")]
        (is (not (:complete? r)))
        (is (empty? (:items r)))))))

(deftest a-short-listing-is-incomplete
  (testing "pages run out before TotalRecordCount"
    (with-redefs [jellyfin/fetch-items-page (paged (take 180 ids) 250 {})]
      (is (re-find #"stopped returning items at 180 of 250"
                   (:reason (jellyfin/fetch-all-items "http://jf" "key" "lib"))))))
  (testing "a page without TotalRecordCount"
    (with-redefs [jellyfin/fetch-items-page (paged ids 250 {0 {:status 200 :body {:Items []}}})]
      (is (re-find #"malformed" (:reason (jellyfin/fetch-all-items "http://jf" "key" "lib")))))))

(deftest a-library-that-changes-mid-scan-is-incomplete
  (with-redefs [jellyfin/fetch-items-page
                (paged ids 250 {100 {:status 200
                                     :body {:TotalRecordCount 249
                                            :Items (mapv (fn [id] {:Id id}) (take 100 (drop 101 ids)))}}})]
    (is (re-find #"changed during the scan"
                 (:reason (jellyfin/fetch-all-items "http://jf" "key" "lib"))))))

(deftest duplicate-ids-are-incomplete
  (testing "paging shifted and served an item twice, so another was skipped"
    (let [dup-ids (assoc ids 120 "jf119")]
      (with-redefs [jellyfin/fetch-items-page (paged dup-ids 250 {})]
        (is (re-find #"249 distinct items"
                     (:reason (jellyfin/fetch-all-items "http://jf" "key" "lib"))))))))
