(ns pseudovision.http.api.playouts-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [cheshire.core     :as json]
            [pseudovision.http.core       :as http]
            [pseudovision.jobs.runner     :as runner]
            [pseudovision.scheduling.core :as sched]
            [pseudovision.db.playouts  :as pl]
            [pseudovision.db.channels  :as ch])
  (:import [java.time Duration Instant]))

;; ---------------------------------------------------------------------------
;; Fixtures — DB rows carry java.time values, exactly as next.jdbc returns them
;; for TIMESTAMPTZ (Instant) and INTERVAL (Duration) columns.
;; ---------------------------------------------------------------------------

(def ^:private test-playout
  {:playouts/id 23 :playouts/channel-id 91})

(def ^:private test-event
  {:playout-events/id            278
   :playout-events/playout-id    23
   :playout-events/media-item-id 94275
   :playout-events/kind          "content"
   :playout-events/start-at      (Instant/parse "2026-06-14T23:06:36.915898Z")
   :playout-events/finish-at     (Instant/parse "2026-06-14T23:12:54.915898Z")
   :playout-events/in-point      (Duration/ofSeconds 0)
   :metadata/title               "Test Movie"
   :metadata/plot                "A test plot"
   :metadata/release-date        "2025-01-01"})

(defn- make-test-handler []
  (http/make-handler {:db nil :ffmpeg {} :media {} :scheduling {}}))

(defn- parse-json-body [resp]
  (some-> resp :body (json/parse-string true)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest list-events-coerces-temporal-values-to-strings
  (testing "GET /api/channels/:id/playout/events does not 500 when events carry
            java.time.Instant / java.time.Duration values (response coercion)"
    (with-redefs [ch/get-channel                      (fn [_ id] (when (= id 91) {:channels/id 91}))
                  ch/get-channel-by-number             (fn [_ _] nil)
                  pl/get-playout-for-channel            (fn [_ _] test-playout)
                  pl/get-upcoming-events-with-metadata  (fn [_ _ _ _ & _] [test-event])]
      (let [handler (make-test-handler)
            resp    (handler (mock/request :get "/api/channels/91/playout/events"))
            body    (parse-json-body resp)
            item    (get-in body [:items 0])]
        (is (= 200 (:status resp)))
        (is (string? (:start-at item)))
        (is (= "2026-06-14T23:06:36.915898Z" (:start-at item)))
        (is (string? (:finish-at item)))
        (is (= "00:00:00" (:in-point item))
            "Duration interval renders as HH:MM:SS string")
        (is (= "Test Movie" (:title item))
            "Title is resolved from metadata")
        (is (= "/api/media/items/94275" (:media-item-link item))
            "Media item link is included")))))

(deftest list-events-resolves-channel-by-number
  (testing "GET /api/channels/:number/playout/events falls back to channel number lookup"
    (with-redefs [ch/get-channel                      (fn [_ _] nil)
                  ch/get-channel-by-number             (fn [_ n] (when (= n "41") {:channels/id 7}))
                  pl/get-playout-for-channel            (fn [_ id] (when (= id 7) test-playout))
                  pl/get-upcoming-events-with-metadata  (fn [_ _ _ _ & _] [test-event])]
      (let [handler (make-test-handler)
            resp    (handler (mock/request :get "/api/channels/41/playout/events"))
            body    (parse-json-body resp)]
        (is (= 200 (:status resp)))
        (is (= "Test Movie" (get-in body [:items 0 :title])))))))

(deftest list-events-returns-404-without-playout
  (testing "GET /api/channels/:id/playout/events returns 404 when no playout"
    (with-redefs [ch/get-channel             (fn [_ _] nil)
                  ch/get-channel-by-number  (fn [_ _] nil)
                  pl/get-playout-for-channel (fn [_ _] nil)]
      (let [handler (make-test-handler)
            resp    (handler (mock/request :get "/api/channels/91/playout/events"))]
        (is (= 404 (:status resp)))))))

;; ---------------------------------------------------------------------------
;; Clearing the playout
;; ---------------------------------------------------------------------------

(deftest clear-playout-resets-cursor
  (testing "DELETE /api/channels/:id/playout clears all events and resets cursor"
    (let [captured (atom nil)]
      (with-redefs [ch/get-channel             (fn [_ id] (when (= id 91) {:channels/id 91}))
                    ch/get-channel-by-number  (fn [_ _] nil)
                    pl/get-playout-for-channel (fn [_ _] test-playout)
                    pl/reset-playout!          (fn [_ pid keep-manual?]
                                                 (reset! captured {:pid pid :keep-manual? keep-manual?})
                                                 7)]
        (let [handler (make-test-handler)
              resp    (handler (mock/request :delete "/api/channels/91/playout"))
              body    (parse-json-body resp)]
          (is (= 200 (:status resp)))
          (is (= 7 (:events-deleted body)))
          (is (false? (:manual-events-removed body)))
          (is (= {:pid 23 :keep-manual? true} @captured)
              "Manual events preserved by default"))))))

;; ---------------------------------------------------------------------------
;; Attaching a schedule (PUT) — schedule_id lives on playouts, not channels;
;; this endpoint was the missing link until 2026-07 (see PLAYOUT_JOBS.md).
;; ---------------------------------------------------------------------------

(deftest attach-schedule-creates-playout-row
  (testing "PUT /api/channels/:id/playout attaches the given schedule-id"
    (let [captured (atom nil)]
      (with-redefs [ch/get-channel        (fn [_ id] (when (= id 91) {:channels/id 91}))
                    ch/get-channel-by-number (fn [_ _] nil)
                    pl/attach-schedule!   (fn [_ channel-id schedule-id]
                                            (reset! captured {:channel-id channel-id :schedule-id schedule-id})
                                            {:playouts/id 23 :playouts/channel-id 91
                                             :playouts/schedule-id schedule-id})]
        (let [handler (make-test-handler)
              resp    (handler (-> (mock/request :put "/api/channels/91/playout")
                                   (mock/json-body {:schedule-id 9})))
              body    (parse-json-body resp)]
          (is (= 200 (:status resp)))
          (is (= {:channel-id 91 :schedule-id 9} @captured))
          (is (= 9 (:schedule-id body)))
          (is (= 23 (:id body))))))))

(deftest attach-schedule-resolves-channel-by-number
  (testing "PUT /api/channels/:number/playout falls back to channel number lookup"
    (with-redefs [ch/get-channel        (fn [_ _] nil)
                  ch/get-channel-by-number (fn [_ n] (when (= n "41") {:channels/id 7}))
                  pl/attach-schedule!   (fn [_ channel-id schedule-id]
                                          {:playouts/id 5 :playouts/channel-id channel-id
                                           :playouts/schedule-id schedule-id})]
      (let [handler (make-test-handler)
            resp    (handler (-> (mock/request :put "/api/channels/41/playout")
                                 (mock/json-body {:schedule-id 9})))
            body    (parse-json-body resp)]
        (is (= 200 (:status resp)))
        (is (= 7 (:channel-id body)))))))

(deftest attach-schedule-returns-404-for-unknown-channel
  (testing "PUT /api/channels/:id/playout returns 404 when the channel doesn't resolve"
    (with-redefs [ch/get-channel        (fn [_ _] nil)
                  ch/get-channel-by-number (fn [_ _] nil)
                  pl/attach-schedule!   (fn [& _] (throw (ex-info "should not be called" {})))]
      (let [handler (make-test-handler)
            resp    (handler (-> (mock/request :put "/api/channels/91/playout")
                                 (mock/json-body {:schedule-id 9})))]
        (is (= 404 (:status resp)))))))

(deftest attach-schedule-with-rebuild-submits-a-job
  (testing "PUT .../playout?rebuild=true also submits a rebuild job"
    (with-redefs [ch/get-channel        (fn [_ id] (when (= id 91) {:channels/id 91}))
                  ch/get-channel-by-number (fn [_ _] nil)
                  pl/attach-schedule!   (fn [_ _ schedule-id]
                                          {:playouts/id 23 :playouts/channel-id 91
                                           :playouts/schedule-id schedule-id})
                  sched/rebuild-from-now! (fn [_ _ _] 17)]
      (let [r       (runner/create {})
            handler (http/make-handler {:db nil :ffmpeg {} :media {} :scheduling {} :jobs r})
            resp    (handler (-> (mock/request :put "/api/channels/91/playout?rebuild=true&horizon=14")
                                 (mock/json-body {:schedule-id 9})))
            body    (parse-json-body resp)]
        (is (= 200 (:status resp)))
        (is (= 9 (:schedule-id body))
            "the attach response is still the Playout, not the job")))))

(deftest clear-playout-can-wipe-manual-events
  (testing "DELETE /api/channels/:id/playout?manual=true also removes manual events"
    (let [captured (atom nil)]
      (with-redefs [ch/get-channel             (fn [_ id] (when (= id 91) {:channels/id 91}))
                    ch/get-channel-by-number  (fn [_ _] nil)
                    pl/get-playout-for-channel (fn [_ _] test-playout)
                    pl/reset-playout!          (fn [_ _ keep-manual?]
                                                 (reset! captured keep-manual?)
                                                 3)]
        (let [handler (make-test-handler)
              resp    (handler (mock/request :delete "/api/channels/91/playout?manual=true"))
              body    (parse-json-body resp)]
          (is (= 200 (:status resp)))
          (is (true? (:manual-events-removed body)))
          (is (false? @captured) "keep-manual? is false when ?manual=true"))))))

(deftest clear-playout-returns-404-without-playout
  (testing "DELETE /api/channels/:id/playout returns 404 when no playout"
    (with-redefs [ch/get-channel             (fn [_ _] nil)
                  ch/get-channel-by-number  (fn [_ _] nil)
                  pl/get-playout-for-channel (fn [_ _] nil)]
      (let [handler (make-test-handler)
            resp    (handler (mock/request :delete "/api/channels/91/playout"))]
        (is (= 404 (:status resp)))))))

(deftest clear-events-passes-window-bounds
  (testing "DELETE /api/channels/:id/playout/events?from&to deletes the overlapping window"
    (let [captured (atom nil)]
      (with-redefs [ch/get-channel             (fn [_ id] (when (= id 91) {:channels/id 91}))
                    ch/get-channel-by-number  (fn [_ _] nil)
                    pl/get-playout-for-channel (fn [_ _] test-playout)
                    pl/delete-events!          (fn [_ pid opts]
                                                 (reset! captured {:pid pid :opts opts})
                                                 4)]
        (let [handler (make-test-handler)
              resp    (handler (mock/request :delete
                                 "/api/channels/91/playout/events?from=2026-06-14T00:00:00Z&to=2026-06-15T00:00:00Z"))
              body    (parse-json-body resp)
              opts    (:opts @captured)]
          (is (= 200 (:status resp)))
          (is (= 4 (:events-deleted body)))
          (is (= (Instant/parse "2026-06-14T00:00:00Z") (:from opts)))
          (is (= (Instant/parse "2026-06-15T00:00:00Z") (:to opts)))
          (is (true? (:keep-manual? opts)) "Manual events preserved by default"))))))

(deftest clear-events-without-bounds-clears-all
  (testing "DELETE /api/channels/:id/playout/events with no window passes nil bounds"
    (let [captured (atom nil)]
      (with-redefs [ch/get-channel             (fn [_ id] (when (= id 91) {:channels/id 91}))
                    ch/get-channel-by-number  (fn [_ _] nil)
                    pl/get-playout-for-channel (fn [_ _] test-playout)
                    pl/delete-events!          (fn [_ _ opts] (reset! captured opts) 9)]
        (let [handler (make-test-handler)
              resp    (handler (mock/request :delete "/api/channels/91/playout/events"))]
          (is (= 200 (:status resp)))
          (is (nil? (:from @captured)))
          (is (nil? (:to @captured))))))))

(deftest clear-events-rejects-bad-timestamp
  (testing "DELETE /api/channels/:id/playout/events returns 400 on unparseable bound"
    (with-redefs [ch/get-channel             (fn [_ id] (when (= id 91) {:channels/id 91}))
                  ch/get-channel-by-number  (fn [_ _] nil)
                  pl/get-playout-for-channel (fn [_ _] test-playout)
                  pl/delete-events!          (fn [_ _ _] (throw (ex-info "should not be called" {})))]
      (let [handler (make-test-handler)
            resp    (handler (mock/request :delete "/api/channels/91/playout/events?to=not-a-date"))]
        (is (= 400 (:status resp)))))))

;; ---------------------------------------------------------------------------
;; POST /api/playouts/ensure — bulk daily top-up
;; ---------------------------------------------------------------------------

(deftest ensure-horizon-all-submits-job-with-requested-horizon
  (testing "POST /api/playouts/ensure?horizon=10 submits a bulk top-up job and
            runs ensure-all-horizons! with the requested horizon"
    (let [captured (promise)]
      ;; ensure-all-horizons! runs inside the job's future; the redef is global
      ;; (alter-var-root), so the future thread sees the stub, and we deref the
      ;; promise INSIDE the with-redefs body so the stub has run before it exits.
      (with-redefs [sched/ensure-all-horizons!
                    (fn [_db horizon-days _opts]
                      (deliver captured horizon-days)
                      {:channels 3 :horizon-days horizon-days
                       :events-generated 42 :results []})]
        (let [r       (runner/create {})
              handler (http/make-handler {:db nil :ffmpeg {} :media {} :scheduling {} :jobs r})
              resp    (handler (mock/request :post "/api/playouts/ensure?horizon=10"))
              body    (parse-json-body resp)]
          (is (= 202 (:status resp)))
          (is (= "playout/ensure-all" (get-in body [:job :type])))
          (is (= 10 (get-in body [:job :metadata :horizon-days])))
          (is (= 10 (deref captured 2000 :timed-out))
              "job runs ensure-all-horizons! with horizon=10"))))))

(deftest ensure-horizon-all-defaults-horizon-to-7
  (testing "POST /api/playouts/ensure with no ?horizon defaults to a 7-day window"
    (let [captured (promise)]
      (with-redefs [sched/ensure-all-horizons!
                    (fn [_db horizon-days _opts]
                      (deliver captured horizon-days)
                      {:channels 0 :horizon-days horizon-days
                       :events-generated 0 :results []})]
        (let [r       (runner/create {})
              handler (http/make-handler {:db nil :ffmpeg {} :media {} :scheduling {} :jobs r})
              resp    (handler (mock/request :post "/api/playouts/ensure"))]
          (is (= 202 (:status resp)))
          (is (= 7 (deref captured 2000 :timed-out))
              "absent horizon defaults to 7 days"))))))
