(ns pseudovision.http.api.channels-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [cheshire.core     :as json]
            [pseudovision.http.core :as http]
            [pseudovision.db.channels :as chan]

            [clojure.pprint :refer [pprint]]))

;; ---------------------------------------------------------------------------
;; Minimal in-memory db stub
;; ---------------------------------------------------------------------------

(def ^:private test-channel
  {:channels/id        1
   :channels/uuid      #uuid "00000000-0000-0000-0000-000000000001"
   :channels/number    "2"
   :channels/name      "Test Channel"
   ;; :channels/slug is required by the Channel response schema added in
   ;; PR #145.  Without it the schema rejects the row and the handler
   ;; 500s on "Request coercion failed" (which is why #145 was needed —
   ;; the slug contract enforces data-layer fixes before they hit the
   ;; scheduling engine).
   :channels/slug      "test-channel"
   :channels/sort-number 2.0
   :channels/streaming-mode "ts"
   :channels/ffmpeg-profile-id 1
   :channels/subtitle-mode "disabled"
   :channels/subtitle-default-enabled true
   :channels/stream-selector-mode "auto"
   :channels/is-enabled true
   :channels/show-in-epg true})

(defn- make-test-handler []
  (let [stub {:db nil
              :ffmpeg {}
              :media {}
              :scheduling {}
              :streams {:registry (atom {})}}]
    (http/make-handler stub)))

(defn- parse-json-body [resp]
  (let [parsed (some-> resp :body (json/parse-string true))]
    (if (sequential? parsed) (vec parsed) parsed)))

(defn- parse-json-body-str-keys [resp]
  (some-> resp :body (json/parse-string false)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest list-channels-returns-200
  (testing "GET /api/channels returns 200"
    (with-redefs [pseudovision.db.channels/list-channels (fn [_ _] [test-channel])
                  pseudovision.db.channels/count-channels (fn [_] 1)]
      (let [handler (make-test-handler)
            resp    (handler (mock/request :get "/api/channels"))]
        (is (= 200 (:status resp)))))))

(defn pthru [o]
  (println (format "DEBUG OUTPUT: %s" (with-out-str (pprint o))))
  o)

(deftest list-channels-unqualifies-response-keys
  (testing "GET /api/channels strips :channels/ namespace from response body"
    (with-redefs [chan/list-channels (fn [_ _] [test-channel])
                  chan/count-channels (fn [_] 1)]
      (let [handler (make-test-handler)
            resp    (handler (mock/request :get "/api/channels"))
            body    (pthru (parse-json-body resp))
            items   (get body :items)]
        (is (vector? items))
        (is (= 1 (get-in items [0 :id])))
        (is (= "Test Channel" (get-in items [0 :name])))
        (is (nil? (get-in items [0 (keyword "channels/id")])))))))

(deftest get-channel-not-found
  (testing "GET /api/channels/999 returns 404 when channel is missing"
    (with-redefs [chan/get-channel (fn [_ _] nil)]
      (let [handler (make-test-handler)
            resp    (handler (mock/request :get "/api/channels/999"))]
        (is (= 404 (:status resp)))))))

(deftest get-channel-coerces-path-id-to-int
  (testing "GET /api/channels/7 passes int 7 to the db layer"
    (let [captured (atom nil)]
      (with-redefs [chan/get-channel (fn [_ id]
                                       (reset! captured id)
                                       test-channel)]
        (let [handler (make-test-handler)
              resp    (handler (mock/request :get "/api/channels/7"))]
          (is (= 200 (:status resp)))
          (is (= 7 @captured))
          (is (integer? @captured)))))))

(deftest get-channel-rejects-non-integer-id
  (testing "GET /api/channels/abc returns 400 with a structured error"
    (let [handler (make-test-handler)
          resp    (handler (mock/request :get "/api/channels/abc"))
          body    (parse-json-body resp)]
      (is (= 400 (:status resp)))
      (is (= "Request coercion failed" (:error body))))))

(deftest health-check
  (testing "GET /health returns 200"
    (let [handler (make-test-handler)
          resp    (handler (mock/request :get "/health"))]
      (is (= 200 (:status resp))))))

(deftest openapi-spec-is-served
  (testing "GET /openapi.json returns an OpenAPI document listing channels paths"
    (let [handler (make-test-handler)
          resp    (handler (mock/request :get "/openapi.json"))
          body    (parse-json-body-str-keys resp)]
      (is (= 200 (:status resp)))
      (is (some? (get-in body ["paths" "/api/channels"])))
      (is (some? (get-in body ["paths" "/api/channels/{id}"]))))))
