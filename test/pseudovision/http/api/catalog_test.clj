(ns pseudovision.http.api.catalog-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [cheshire.core     :as json]
            [pseudovision.http.core :as http]
            [pseudovision.db.catalog :as catalog-db]
            [pseudovision.db.channels :as channels-db]))

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

(deftest catalog-aggregate-returns-200
  (testing "GET /api/catalog/aggregate returns a CatalogProfile"
    (with-redefs [catalog-db/build-catalog-profile (fn [_ _]
                                                     {:channel_scope nil
                                                      :total_items 100
                                                      :total_episodes 80
                                                      :movie_count 20
                                                      :shows [{:media_id "series:test"
                                                               :title "Test Show"
                                                               :episode_count 10
                                                               :available_episode_count 10}]
                                                      :genres [{:genre "comedy" :show_count 1 :episode_count 10}]
                                                      :runtime_histogram [{:label "20-30min"
                                                                           :min_minutes 20
                                                                           :max_minutes 30
                                                                           :item_count 10}]
                                                      :generated_at "2026-06-24T12:00:00Z"})]
      (let [handler (make-test-handler)
            resp    (handler (mock/request :get "/api/catalog/aggregate"))]
        (is (= 200 (:status resp)))
        (let [body (parse-json-body resp)]
          (is (= 100 (:total-items body)))
          (is (= 80 (:total-episodes body)))
          (is (= 20 (:movie-count body)))
          (is (= 1 (count (:shows body))))
          (is (= "series:test" (get-in body [:shows 0 :media-id]))))))))

(deftest catalog-aggregate-accepts-channel-param
  (testing "GET /api/catalog/aggregate?channel=Test returns scoped profile"
    (let [captured (atom nil)]
      (with-redefs [catalog-db/build-catalog-profile (fn [_ channel-name]
                                                        (reset! captured channel-name)
                                                        {:channel_scope channel-name
                                                         :total_items 50
                                                         :total_episodes 50
                                                         :movie_count 0
                                                         :shows []
                                                         :genres []
                                                         :runtime_histogram []
                                                         :generated_at "2026-06-24T12:00:00Z"})
                    channels-db/get-channel-by-number (fn [_ _] {:channels/name "Test"})]
        (let [handler (make-test-handler)
              resp    (handler (mock/request :get "/api/catalog/aggregate?channel=Test"))]
          (is (= 200 (:status resp)))
          (is (= "Test" @captured)))))))

(deftest catalog-aggregate-accepts-tag-param
  (testing "GET /api/catalog/aggregate?tag=channel:comedy returns tagged profile"
    (let [captured (atom nil)]
      (with-redefs [catalog-db/build-catalog-profile (fn [_ channel-name]
                                                        (reset! captured channel-name)
                                                        {:channel_scope nil
                                                         :total_items 10
                                                         :total_episodes 10
                                                         :movie_count 0
                                                         :shows []
                                                         :genres []
                                                         :runtime_histogram []
                                                         :generated_at "2026-06-24T12:00:00Z"})]
        (let [handler (make-test-handler)
              resp    (handler (mock/request :get "/api/catalog/aggregate?tag=channel:comedy"))]
          (is (= 200 (:status resp))))))))

(deftest catalog-count-returns-200
  (testing "POST /api/catalog/count returns a count stub"
    (with-redefs [catalog-db/count-playable-items (fn [_ _] {:total_items 42})]
      (let [handler (make-test-handler)
            resp    (handler (-> (mock/request :post "/api/catalog/count")
                                (mock/json-body {:filters {}})))]
        (is (= 200 (:status resp)))
        (is (= 42 (:count (parse-json-body resp))))))))
