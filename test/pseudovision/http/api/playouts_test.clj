(ns pseudovision.http.api.playouts-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [cheshire.core     :as json]
            [pseudovision.http.core    :as http]
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
                  ch/get-channel-by-number             (fn [_ n] (when (= n 41) {:channels/id 7}))
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
