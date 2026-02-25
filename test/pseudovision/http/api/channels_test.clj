(ns pseudovision.http.api.channels-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [cheshire.core     :as json]
            [pseudovision.http.core :as http]))

;; ---------------------------------------------------------------------------
;; Minimal in-memory db stub
;; ---------------------------------------------------------------------------

(def ^:private test-channel
  {:channels/id        1
   :channels/uuid      #uuid "00000000-0000-0000-0000-000000000001"
   :channels/number    "2"
   :channels/name      "Test Channel"
   :channels/sort-number 2.0})

(def ^:private stub-db
  (reify
    ;; We stub only what channels handler calls.
    Object))

;; Because handler functions close over the db, we inject a stub atom.
(defonce ^:private channels-store (atom [test-channel]))

(defn- make-test-handler []
  (let [stub {:db      nil    ; we override the db functions in tests
              :ffmpeg  {}
              :media   {}
              :scheduling {}}]
    (http/make-handler stub)))

;; ---------------------------------------------------------------------------
;; Tests (smoke-level — just verify HTTP plumbing, not DB)
;; ---------------------------------------------------------------------------

(deftest list-channels-returns-200
  (testing "GET /api/channels returns 200"
    (with-redefs [pseudovision.db.channels/list-channels (fn [_] [test-channel])]
      (let [handler (make-test-handler)
            req     (mock/request :get "/api/channels")
            resp    (handler req)]
        (is (= 200 (:status resp)))))))

(deftest get-channel-not-found
  (testing "GET /api/channels/999 returns 404 when channel is missing"
    (with-redefs [pseudovision.db.channels/get-channel (fn [_ _] nil)]
      (let [handler (make-test-handler)
            req     (mock/request :get "/api/channels/999")
            resp    (handler req)]
        (is (= 404 (:status resp)))))))

(deftest health-check
  (testing "GET /health returns 200"
    (let [handler (make-test-handler)
          req     (mock/request :get "/health")
          resp    (handler req)]
      (is (= 200 (:status resp))))))
