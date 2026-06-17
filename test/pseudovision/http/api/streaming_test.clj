(ns pseudovision.http.api.streaming-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.http.api.streaming :as streaming]
            [pseudovision.db.playouts :as db-playouts]))

;; The transition helpers are private; grab the vars directly.
(def stream-source-identity  @#'streaming/stream-source-identity)
(def needs-transition?       @#'streaming/needs-transition?)

(def ^:private test-channel
  {:channels/id 1
   :channels/uuid #uuid "00000000-0000-0000-0000-000000000001"})

(defn- content-stream
  "A running stream whose source-info is a real content event."
  [event-id]
  {:source-info {:type :current-event
                 :event {:playout-events/id event-id}}})

(def ^:private slate-refresh-secs @#'streaming/slate-refresh-secs)

(defn- slate-stream
  "A running stream whose source-info is a generated fallback slate, started
   age-secs ago (default just now, so it has not hit its refresh TTL)."
  ([] (slate-stream 0))
  ([age-secs]
   {:source-info        {:type :generated-slate :upcoming-events []}
    :stream-started-at  (- (System/currentTimeMillis) (* age-secs 1000))}))

;; ---------------------------------------------------------------------------
;; stream-source-identity
;; ---------------------------------------------------------------------------

(deftest stream-source-identity-distinguishes-content-from-fallback
  (testing "content event yields [:event id]"
    (is (= [:event 42] (stream-source-identity (content-stream 42)))))
  (testing "a slate (no :event) yields :fallback"
    (is (= :fallback (stream-source-identity (slate-stream))))))

;; ---------------------------------------------------------------------------
;; needs-transition?
;; ---------------------------------------------------------------------------

(defn- with-current-event
  "Runs f with the db layer stubbed so the channel's playout reports
   current-event-id as the live event (nil = a gap)."
  [current-event-id f]
  (with-redefs [db-playouts/get-playout-for-channel (fn [_ _] {:playouts/id 7})
                db-playouts/get-current-event       (fn [_ _ _]
                                                      (when current-event-id
                                                        {:playout-events/id current-event-id}))]
    (f)))

(deftest needs-transition-leaves-slate-when-event-goes-live
  (testing "slate playing but an event is now live -> transition"
    (with-current-event 42
      #(is (true? (needs-transition? nil test-channel (slate-stream)))))))

(deftest needs-transition-switches-between-events
  (testing "playing event A while event B is now live -> transition"
    (with-current-event 99
      #(is (true? (needs-transition? nil test-channel (content-stream 42))))))
  (testing "playing the event that is currently live -> no transition"
    (with-current-event 42
      #(is (false? (needs-transition? nil test-channel (content-stream 42)))))))

(deftest needs-transition-falls-back-into-gap
  (testing "playing an event but the timeline has hit a gap -> transition to fallback"
    (with-current-event nil
      #(is (true? (needs-transition? nil test-channel (content-stream 42)))))))

(deftest needs-transition-stays-on-slate-during-gap
  (testing "fresh slate playing and still no live event -> no churn"
    (with-current-event nil
      #(is (false? (needs-transition? nil test-channel (slate-stream)))))))

(deftest needs-transition-refreshes-stale-slate
  (testing "slate older than its TTL refreshes even with no live event"
    (with-current-event nil
      #(is (true? (needs-transition? nil test-channel
                                     (slate-stream (inc slate-refresh-secs))))))))

(deftest needs-transition-handles-missing-playout
  (testing "no playout configured -> fallback identity, fresh slate stays put"
    (with-redefs [db-playouts/get-playout-for-channel (fn [_ _] nil)]
      (is (false? (needs-transition? nil test-channel (slate-stream))))
      (is (true?  (needs-transition? nil test-channel (content-stream 42)))))))
