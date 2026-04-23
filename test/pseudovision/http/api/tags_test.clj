(ns pseudovision.http.api.tags-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [cheshire.core     :as json]
            [pseudovision.http.core :as http]))

(defn- make-test-handler []
  (http/make-handler {:db nil :ffmpeg {} :media {} :scheduling {}}))

(defn- parse-json-body [resp]
  (let [parsed (some-> resp :body (json/parse-string true))]
    (if (sequential? parsed) (vec parsed) parsed)))

(defn- post-json [path body]
  (-> (mock/request :post path)
      (mock/content-type "application/json")
      (mock/body (json/generate-string body))))

(deftest list-tags-returns-name-count-pairs
  (testing "GET /api/tags shapes rows as {:name :count}"
    (with-redefs [pseudovision.db.core/query
                  (fn [_ _] [{:metadata-tags/name "comedy" :count 12}
                             {:metadata-tags/name "short"  :count 4}])]
      (let [resp ((make-test-handler) (mock/request :get "/api/tags"))
            body (parse-json-body resp)]
        (is (= 200 (:status resp)))
        (is (= 2 (count body)))
        (is (= "comedy" (get-in body [0 :name])))
        (is (= 12      (get-in body [0 :count])))))))

(deftest add-tags-rejects-missing-tags-key
  (testing "POST /api/media-items/1/tags with no :tags key returns 400"
    (let [resp ((make-test-handler)
                (post-json "/api/media-items/1/tags" {:source "manual"}))
          body (parse-json-body resp)]
      (is (= 400 (:status resp)))
      (is (= "Request coercion failed" (:error body))))))

(deftest delete-tag-returns-204
  (testing "DELETE /api/media-items/1/tags/comedy succeeds"
    (with-redefs [pseudovision.db.core/execute-one! (fn [_ _] nil)]
      (let [resp ((make-test-handler)
                  (mock/request :delete "/api/media-items/1/tags/comedy"))]
        (is (= 204 (:status resp)))))))

(deftest delete-tag-rejects-non-integer-item-id
  (testing "DELETE /api/media-items/abc/tags/comedy returns 400"
    (let [resp ((make-test-handler)
                (mock/request :delete "/api/media-items/abc/tags/comedy"))
          body (parse-json-body resp)]
      (is (= 400 (:status resp)))
      (is (= "Request coercion failed" (:error body))))))
