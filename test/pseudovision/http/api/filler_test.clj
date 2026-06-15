(ns pseudovision.http.api.filler-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [cheshire.core     :as json]
            [pseudovision.http.core :as http]))

(defn- make-test-handler []
  (http/make-handler {:db nil :ffmpeg {} :media {} :scheduling {}}))

(defn- parse-json-body [resp]
  (some-> resp :body (json/parse-string true)))

(defn- post-json [path body]
  (-> (mock/request :post path)
      (mock/content-type "application/json")
      (mock/body (json/generate-string body))))

(defn- put-json [path body]
  (-> (mock/request :put path)
      (mock/content-type "application/json")
      (mock/body (json/generate-string body))))

(deftest create-preset-resolves-media-item-ref
  (testing "POST resolves a Jellyfin/remote media-item-id to the internal id"
    (let [captured (atom nil)]
      (with-redefs [pseudovision.db.media/resolve-media-item-id
                    (fn [_ ref] (is (= "jf-abc123" ref)) 77)
                    pseudovision.db.filler/create-filler-preset!
                    (fn [_ attrs] (reset! captured attrs) (assoc attrs :id 1))]
        (let [resp ((make-test-handler)
                    (post-json "/api/filler-presets"
                               {:name "Promos"
                                :role "pre"
                                :mode "count"
                                :media-item-id "jf-abc123"}))]
          (is (= 201 (:status resp)))
          (is (= 77 (:media-item-id @captured))))))))

(deftest create-preset-unknown-media-item-ref-404
  (testing "POST with a media-item-id that resolves to nothing returns 404"
    (with-redefs [pseudovision.db.media/resolve-media-item-id (fn [_ _] nil)]
      (let [resp ((make-test-handler)
                  (post-json "/api/filler-presets"
                             {:name "Promos"
                              :role "pre"
                              :mode "count"
                              :media-item-id "does-not-exist"}))
            body (parse-json-body resp)]
        (is (= 404 (:status resp)))
        (is (= "Media item not found: does-not-exist" (:error body)))))))

(deftest update-preset-resolves-media-item-ref
  (testing "PUT resolves a Jellyfin/remote media-item-id to the internal id"
    (let [captured (atom nil)]
      (with-redefs [pseudovision.db.media/resolve-media-item-id (fn [_ _] 88)
                    pseudovision.db.filler/update-filler-preset!
                    (fn [_ _id attrs] (reset! captured attrs) (assoc attrs :id 5 :name "P" :role "pre" :mode "count"))]
        (let [resp ((make-test-handler)
                    (put-json "/api/filler-presets/5"
                              {:media-item-id "jf-xyz"}))]
          (is (= 200 (:status resp)))
          (is (= 88 (:media-item-id @captured))))))))
