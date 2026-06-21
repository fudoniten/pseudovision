(ns pseudovision.http.api.jobs-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [cheshire.core     :as json]
            [pseudovision.http.core       :as http]
            [pseudovision.jobs.runner     :as runner]
            [pseudovision.scheduling.core :as sched]
            [pseudovision.db.playouts     :as pl]
            [pseudovision.db.channels     :as ch]))

(def ^:private test-playout {:playouts/id 23 :playouts/channel-id 91})

(defn- make-handler [runner]
  (http/make-handler {:db nil :ffmpeg {} :media {} :scheduling {} :jobs runner}))

(defn- parse [resp] (some-> resp :body (json/parse-string true)))

(defn- await-job
  "Poll GET /api/jobs/:id until the job reaches a terminal status."
  [handler job-id]
  (loop [n 200]
    (let [body (parse (handler (mock/request :get (str "/api/jobs/" job-id))))
          job  (:job body)]
      (if (or (#{"succeeded" "failed"} (:status job)) (zero? n))
        job
        (do (Thread/sleep 5) (recur (dec n)))))))

(deftest rebuild-submits-async-job
  (testing "POST /api/channels/:id/playout returns 202 with a job that completes"
    (with-redefs [ch/get-channel             (fn [_ id] (when (= id 91) {:channels/id 91}))
                  ch/get-channel-by-number  (fn [_ _] nil)
                  pl/get-playout-for-channel (fn [_ _] test-playout)
                  sched/rebuild-from-now!    (fn [_ _ _] 17)]
      (let [r       (runner/create {})
            handler (make-handler r)
            resp    (handler (mock/request :post "/api/channels/91/playout"))
            body    (parse resp)
            job     (:job body)]
        (is (= 202 (:status resp)))
        (is (string? (:id job)))
        ;; Namespaced keyword serialises to a namespaced string on the wire.
        (is (= "playout/rebuild" (:type job)))
        (is (contains? #{"queued" "running" "succeeded"} (:status job)))
        (is (= {:channel-id 91 :from "now" :horizon-days 14} (:metadata job)))
        (let [final (await-job handler (:id job))]
          (is (= "succeeded" (:status final)))
          (is (= 17 (get-in final [:result :events-generated])))
          (is (= "now" (get-in final [:result :from]))))))))

(deftest rebuild-without-playout-404s
  (testing "POST /api/channels/:id/playout returns 404 when no playout exists"
    (with-redefs [ch/get-channel             (fn [_ _] nil)
                  ch/get-channel-by-number  (fn [_ _] nil)
                  pl/get-playout-for-channel (fn [_ _] nil)]
      (let [handler (make-handler (runner/create {}))
            resp    (handler (mock/request :post "/api/channels/91/playout"))]
        (is (= 404 (:status resp)))))))

(deftest list-jobs-endpoint
  (testing "GET /api/jobs lists submitted jobs"
    (let [r (runner/create {})]
      (runner/submit! r {:type :playout/rebuild :metadata {:channel-id 1}}
                      (fn [_] {:events-generated 1}))
      (let [handler (make-handler r)
            body    (parse (handler (mock/request :get "/api/jobs")))]
        (is (vector? (:jobs body)))
        (is (= 1 (count (:jobs body))))
        (is (= "playout/rebuild" (get-in body [:jobs 0 :type])))))))

(deftest get-unknown-job-404s
  (testing "GET /api/jobs/:id returns 404 for an unknown job id"
    (let [handler (make-handler (runner/create {}))
          resp    (handler (mock/request :get "/api/jobs/nope"))]
      (is (= 404 (:status resp))))))
