(ns pseudovision.http.api.daily-slots-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ring.mock.request :as mock]
            [cheshire.core     :as json]
            [pseudovision.http.core :as http]
            [pseudovision.http.api.daily-slots :as ds]
            [pseudovision.db.playouts :as playout-db]
            [pseudovision.db.channels :as channels-db]
            [pseudovision.db.core :as db-core]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-test-handler []
  (let [stub {:db nil :ffmpeg {} :media {} :scheduling {}}]
    (http/make-handler stub)))

(defn- parse-json-body [resp]
  (let [parsed (some-> resp :body (json/parse-string true))]
    (if (sequential? parsed) (vec parsed) parsed)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest daily-slots-returns-404-when-no-playout
  (testing "POST /api/channels/1/daily-slots returns 404 without playout"
    (with-redefs [channels-db/get-channel (fn [_ _] {:channels/id 1})
                  playout-db/get-playout-for-channel (fn [_ _] nil)]
      (let [handler (make-test-handler)
            resp    (handler (-> (mock/request :post "/api/channels/1/daily-slots")
                                (mock/json-body [{:start-time "2026-01-10T10:00:00Z"
                                                  :end-time "2026-01-10T22:00:00Z"
                                                  :media-id "series:cheers"
                                                  :media-selection-strategy "sequential"}])))]
        (is (= 404 (:status resp)))))))

(deftest daily-slots-ingests-empty-list
  (testing "POST /api/channels/1/daily-slots with empty body returns 0 ingested"
    (with-redefs [channels-db/get-channel (fn [_ _] {:channels/id 1})
                  playout-db/get-playout-for-channel (fn [_ _] {:playouts/id 1})
                  playout-db/delete-events! (fn [& _] 0)
                  playout-db/bulk-insert-events! (fn [& _] nil)]
      (let [handler (make-test-handler)
            resp    (handler (-> (mock/request :post "/api/channels/1/daily-slots")
                                (mock/json-body [])))]
        (is (= 200 (:status resp)))
        (let [body (parse-json-body resp)]
          (is (= 0 (:ingested body)))
          (is (= 0 (:skipped body))))))))

(deftest daily-slots-ingests-valid-slots
  (testing "POST /api/channels/1/daily-slots resolves media and creates events"
    (with-redefs [channels-db/get-channel (fn [_ _] {:channels/id 1})
                  playout-db/get-playout-for-channel (fn [_ _] {:playouts/id 1})
                  playout-db/delete-events! (fn [& _] 0)
                  playout-db/bulk-insert-events! (fn [& _] nil)
                  db-core/query-one (fn [_ _] nil)
                  db-core/query (fn [_ _] [{:media-items/id 42
                                            :media-items/kind "episode"}])]
      (let [handler (make-test-handler)
            resp    (handler (-> (mock/request :post "/api/channels/1/daily-slots")
                                (mock/json-body [{:start-time "2026-01-10T10:00:00Z"
                                                  :end-time "2026-01-10T22:00:00Z"
                                                  :media-id "random:comedy"
                                                  :media-selection-strategy "random"}])))]
        (is (= 200 (:status resp)))
        (let [body (parse-json-body resp)]
          (is (= 1 (:ingested body)))
          (is (= 0 (:skipped body))))))))

(deftest daily-slots-accepts-naive-datetimes
  (testing "POST .../daily-slots ingests a batch of naive-ISO slots (no Z/offset)"
    ;; The Tunarr-Scheduler expander emits naive local wall-clock datetimes
    ;; ("YYYY-MM-DDTHH:MM:SS" with no timezone). These must be accepted and
    ;; resolved, not rejected as "Missing start_time".
    (with-redefs [channels-db/get-channel (fn [_ _] {:channels/id 1})
                  playout-db/get-playout-for-channel (fn [_ _] {:playouts/id 1})
                  playout-db/delete-events! (fn [& _] 0)
                  playout-db/bulk-insert-events! (fn [& _] nil)
                  ;; Resolve any show lookup to a stub show; episode/tag queries
                  ;; resolve to a single concrete episode.
                  db-core/query-one (fn [_ _] {:media-items/id 99})
                  db-core/query (fn [_ _] [{:media-items/id 42
                                            :media-items/kind "episode"}])]
      (let [batch [{:start-time "2026-06-29T00:00:00"
                    :end-time   "2026-06-29T01:00:00"
                    :media-id   "random:Mystery"
                    :media-selection-strategy "random"
                    :category-filters []
                    :notes []}
                   {:start-time "2026-06-29T01:00:00"
                    :end-time   "2026-06-29T02:00:00"
                    :media-id   "series:cheers"
                    :media-selection-strategy "sequential"
                    :category-filters []
                    :notes []}]
            handler (make-test-handler)
            resp    (handler (-> (mock/request :post "/api/channels/1/daily-slots")
                                (mock/json-body batch)))]
        (is (= 200 (:status resp)))
        (let [body (parse-json-body resp)]
          (is (= (count batch) (:ingested body)))
          (is (= 0 (:skipped body)))
          (is (empty? (:errors body))))))))

;; ---------------------------------------------------------------------------
;; Media resolution — SQL-shape regressions
;;
;; These lock in that the daily-slots resolver looks up the SAME id/dimension
;; space that GET /api/catalog/aggregate emits:
;;   - series:<id> episodes are nested show -> season -> episode, so the lookup
;;     must traverse seasons (not only direct children of the show);
;;   - random:<category> matches metadata_genres (the genre names the aggregate
;;     returns) and tags, expanding shows to playable episodes.
;; ---------------------------------------------------------------------------

(deftest resolve-show-episodes-traverses-seasons
  (testing "series episode lookup reaches season-nested episodes, not just direct children"
    (let [captured (atom nil)]
      (with-redefs [db-core/query-one (fn [_ _] {:media-items/id 7})
                    db-core/query     (fn [_ sqlvec] (reset! captured sqlvec) [])]
        (#'ds/resolve-show-episodes nil "f2c639e8be1a00ff83e176aa034ee765")
        (let [sql (first @captured)]
          (is (re-find #"(?i)season" sql)
              "joins the seasons table to reach episodes")
          (is (re-find #"(?i)parent_id" sql)
              "links episodes to the show via parent_id"))))))

(deftest resolve-by-category-matches-genres-and-expands-playables
  (testing "random:<category> reads the genre table the aggregate emits and resolves to playable items"
    (let [captured (atom nil)]
      (with-redefs [db-core/query (fn [_ sqlvec] (reset! captured sqlvec) [])]
        (#'ds/resolve-by-category nil "Mystery")
        (let [[sql & params] @captured]
          (is (re-find #"(?i)metadata_genres" sql)
              "matches against the genre dimension the aggregate emits")
          (is (re-find #"(?i)metadata_tags" sql)
              "also honours the tag dimension")
          (is (re-find #"(?i) play\b|AS play" sql)
              "selects a distinct playable row (episode/movie), not the show")
          (is (some #{"Mystery"} params)
              "binds the requested category verbatim")
          (is (some #{"genre:Mystery"} params)
              "honours the genre:<name> tag convention"))))))

(deftest resolve-by-category-declares-season-join-before-it-is-referenced
  (testing "the season join is emitted before the play join that references season.id"
    ;; HoneySQL renders every inner :join ahead of every :left-join regardless
    ;; of threading order. When the playable-row join was an inner :join it was
    ;; emitted before the :season LEFT JOIN it referenced, yielding Postgres
    ;; error 42P01: "missing FROM-clause entry for table season". Lock in that
    ;; the season alias is declared before its first use.
    (let [captured (atom nil)]
      (with-redefs [db-core/query (fn [_ sqlvec] (reset! captured sqlvec) [])]
        (#'ds/resolve-by-category nil "Mystery")
        (let [sql      (str/lower-case (first @captured))
              ;; season.parent_id appears only in the :season join's own ON
              ;; clause (its declaration); season.id appears only in the play
              ;; join that references it.
              decl-idx (.indexOf sql "season.parent_id")
              ref-idx  (.indexOf sql "season.id")]
          (is (not (neg? decl-idx)) "season table is joined in the query")
          (is (not (neg? ref-idx))  "play join references season.id")
          (is (< decl-idx ref-idx)
              "season must be in scope (declared) before it is referenced"))))))
