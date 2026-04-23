(ns pseudovision.http.api.ffmpeg-test
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

(def ^:private test-profile
  {:ffmpeg-profiles/id 1
   :ffmpeg-profiles/name "High Quality"
   :ffmpeg-profiles/config {:video-codec "libx264"}})

(deftest list-profiles-returns-unqualified-keys
  (testing "GET /api/ffmpeg/profiles strips :ffmpeg-profiles/ prefixes"
    (with-redefs [pseudovision.db.ffmpeg/list-profiles (fn [_] [test-profile])]
      (let [resp ((make-test-handler) (mock/request :get "/api/ffmpeg/profiles"))
            body (parse-json-body resp)]
        (is (= 200 (:status resp)))
        (is (= 1 (get-in body [0 :id])))
        (is (= "High Quality" (get-in body [0 :name])))))))

(deftest get-profile-coerces-id
  (testing "GET /api/ffmpeg/profiles/12 passes int 12 to the db"
    (let [captured (atom nil)]
      (with-redefs [pseudovision.db.ffmpeg/get-profile
                    (fn [_ id] (reset! captured id) test-profile)]
        (let [resp ((make-test-handler)
                    (mock/request :get "/api/ffmpeg/profiles/12"))]
          (is (= 200 (:status resp)))
          (is (= 12 @captured)))))))

(deftest get-profile-rejects-non-integer-id
  (testing "GET /api/ffmpeg/profiles/abc returns 400"
    (let [resp ((make-test-handler) (mock/request :get "/api/ffmpeg/profiles/abc"))
          body (parse-json-body resp)]
      (is (= 400 (:status resp)))
      (is (= "Request coercion failed" (:error body))))))

(deftest create-profile-requires-name
  (testing "POST /api/ffmpeg/profiles with empty body returns 400"
    (let [resp ((make-test-handler) (post-json "/api/ffmpeg/profiles" {}))
          body (parse-json-body resp)]
      (is (= 400 (:status resp)))
      (is (= "Request coercion failed" (:error body))))))
