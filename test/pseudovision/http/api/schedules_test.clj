(ns pseudovision.http.api.schedules-test
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

(defn- put-json [path body]
  (-> (mock/request :put path)
      (mock/content-type "application/json")
      (mock/body (json/generate-string body))))

;; ---------------------------------------------------------------------------
;; Schedules
;; ---------------------------------------------------------------------------

(def ^:private test-schedule
  {:schedules/id 1
   :schedules/name "Weeknight Lineup"
   :schedules/fixed-start-time-behavior "skip"
   :schedules/shuffle-slots false
   :schedules/random-start-point false
   :schedules/keep-multi-part-together false
   :schedules/treat-collections-as-shows false})

(deftest list-schedules-returns-unqualified-keys
  (testing "GET /api/schedules strips :schedules/ prefixes"
    (with-redefs [pseudovision.db.schedules/list-schedules (fn [_] [test-schedule])]
      (let [resp ((make-test-handler) (mock/request :get "/api/schedules"))
            body (parse-json-body resp)]
        (is (= 200 (:status resp)))
        (is (vector? body))
        (is (= 1 (get-in body [0 :id])))
        (is (= "Weeknight Lineup" (get-in body [0 :name])))
        (is (nil? (get-in body [0 (keyword "schedules/id")])))))))

(deftest get-schedule-coerces-id
  (testing "GET /api/schedules/7 coerces the path id to int"
    (let [captured (atom nil)]
      (with-redefs [pseudovision.db.schedules/get-schedule
                    (fn [_ id] (reset! captured id) test-schedule)]
        (let [resp ((make-test-handler) (mock/request :get "/api/schedules/7"))]
          (is (= 200 (:status resp)))
          (is (= 7 @captured))
          (is (integer? @captured)))))))

(deftest get-schedule-rejects-non-integer-id
  (testing "GET /api/schedules/abc returns a 400 coercion error"
    (let [resp ((make-test-handler) (mock/request :get "/api/schedules/abc"))
          body (parse-json-body resp)]
      (is (= 400 (:status resp)))
      (is (= "Request coercion failed" (:error body))))))

(deftest create-schedule-requires-name
  (testing "POST /api/schedules with an empty body returns 400"
    (let [resp ((make-test-handler) (post-json "/api/schedules" {}))
          body (parse-json-body resp)]
      (is (= 400 (:status resp)))
      (is (= "Request coercion failed" (:error body))))))

(deftest create-schedule-happy-path
  (testing "POST /api/schedules passes the coerced body to the db layer"
    (let [captured (atom nil)]
      (with-redefs [pseudovision.db.schedules/create-schedule!
                    (fn [_ attrs]
                      (reset! captured attrs)
                      (assoc attrs :id 42))]
        (let [resp ((make-test-handler)
                    (post-json "/api/schedules"
                               {:name "Weeknights"
                                :shuffle-slots true}))]
          (is (= 201 (:status resp)))
          (is (= {:name "Weeknights" :shuffle-slots true} @captured)))))))

;; ---------------------------------------------------------------------------
;; Slots
;; ---------------------------------------------------------------------------

(def ^:private test-slot
  {:schedule-slots/id 5
   :schedule-slots/schedule-id 1
   :schedule-slots/slot-index 0
   :schedule-slots/anchor "sequential"
   :schedule-slots/fill-mode "once"
   :schedule-slots/playback-order "chronological"})

(deftest list-slots-coerces-schedule-id
  (testing "GET /api/schedules/3/slots coerces the path schedule-id to int"
    (let [captured (atom nil)]
      (with-redefs [pseudovision.db.schedules/list-slots
                    (fn [_ sid] (reset! captured sid) [test-slot])]
        (let [resp ((make-test-handler) (mock/request :get "/api/schedules/3/slots"))
              body (parse-json-body resp)]
          (is (= 200 (:status resp)))
          (is (= 3 @captured))
          (is (= 5 (get-in body [0 :id]))))))))

(deftest create-slot-rejects-invalid-anchor
  (testing "POST with an anchor value outside the enum returns 400"
    (let [resp ((make-test-handler)
                (post-json "/api/schedules/1/slots"
                           {:slot-index 0 :anchor "bogus"}))
          body (parse-json-body resp)]
      (is (= 400 (:status resp)))
      (is (= "Request coercion failed" (:error body))))))

(deftest create-slot-passes-schedule-id-from-path
  (testing "POST /api/schedules/:sid/slots injects the schedule-id path param"
    (let [captured (atom nil)]
      (with-redefs [pseudovision.db.schedules/create-slot!
                    (fn [_ attrs]
                      (reset! captured attrs)
                      (assoc attrs :id 99))]
        (let [resp ((make-test-handler)
                    (put-json "/api/schedules/4/slots" {}))]
          ;; PUT isn't declared on the collection route → method-not-allowed
          (is (= 405 (:status resp))))
        (let [resp ((make-test-handler)
                    (post-json "/api/schedules/4/slots"
                               {:slot-index 0
                                :anchor "sequential"
                                :fill-mode "once"
                                :playback-order "chronological"}))]
          (is (= 201 (:status resp)))
          (is (= 4 (:schedule-id @captured)))
          (is (= "sequential" (:anchor @captured))))))))

(deftest update-slot-via-coerced-path
  (testing "PUT /api/schedules/1/slots/5 passes int ids to db"
    (let [captured (atom nil)]
      (with-redefs [pseudovision.db.schedules/update-slot!
                    (fn [_ id attrs]
                      (reset! captured {:id id :attrs attrs})
                      (assoc attrs :id id))]
        (let [resp ((make-test-handler)
                    (put-json "/api/schedules/1/slots/5"
                              {:custom-title "Weekly Special"}))]
          (is (= 200 (:status resp)))
          (is (= 5 (:id @captured)))
          (is (= {:custom-title "Weekly Special"} (:attrs @captured))))))))

(deftest openapi-spec-lists-schedules-paths
  (testing "OpenAPI document enumerates the migrated schedule paths"
    (let [resp ((make-test-handler) (mock/request :get "/openapi.json"))
          body (json/parse-string (:body resp) false)]
      (is (= 200 (:status resp)))
      (is (some? (get-in body ["paths" "/api/schedules"])))
      (is (some? (get-in body ["paths" "/api/schedules/{id}"])))
      (is (some? (get-in body ["paths" "/api/schedules/{schedule-id}/slots"])))
      (is (some? (get-in body ["paths" "/api/schedules/{schedule-id}/slots/{id}"]))))))
