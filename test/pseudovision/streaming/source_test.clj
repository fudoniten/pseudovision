(ns pseudovision.streaming.source-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.streaming.source :as source]
            [pseudovision.db.playouts :as db-playouts]))

(def ^:private test-channel
  {:channels/id 1
   :channels/uuid #uuid "00000000-0000-0000-0000-000000000001"})

(defn- content-source [event-id]
  {:type :current-event :event {:playout-events/id event-id}})

(defn- slate-source [] {:type :generated-slate :upcoming-events []})

(defn- ms-ago [secs] (- (System/currentTimeMillis) (* secs 1000)))

;; ---------------------------------------------------------------------------
;; source-identity
;; ---------------------------------------------------------------------------

(deftest source-identity-distinguishes-content-from-fallback
  (testing "content event yields [:event id]"
    (is (= [:event 42] (source/source-identity (content-source 42)))))
  (testing "a slate (no :event) yields :fallback"
    (is (= :fallback (source/source-identity (slate-source))))))

;; ---------------------------------------------------------------------------
;; needs-transition? — now takes (db channel current-source-info started-ms)
;; ---------------------------------------------------------------------------

(defn- with-current-event
  "Runs f with the db stubbed so the channel's playout reports
   current-event-id as the live event (nil = a gap)."
  [current-event-id f]
  (with-redefs [db-playouts/get-playout-for-channel (fn [_ _] {:playouts/id 7})
                db-playouts/get-current-event       (fn [_ _ _]
                                                      (when current-event-id
                                                        {:playout-events/id current-event-id}))]
    (f)))

(deftest needs-transition-leaves-slate-when-event-goes-live
  (with-current-event 42
    #(is (true? (source/needs-transition? nil test-channel (slate-source) (ms-ago 0))))))

(deftest needs-transition-switches-between-events
  (testing "playing event A while event B is now live -> transition"
    (with-current-event 99
      #(is (true? (source/needs-transition? nil test-channel (content-source 42) (ms-ago 0))))))
  (testing "playing the event that is currently live -> no transition"
    (with-current-event 42
      #(is (false? (source/needs-transition? nil test-channel (content-source 42) (ms-ago 0)))))))

(deftest needs-transition-falls-back-into-gap
  (with-current-event nil
    #(is (true? (source/needs-transition? nil test-channel (content-source 42) (ms-ago 0))))))

(deftest needs-transition-stays-on-slate-during-gap
  (with-current-event nil
    #(is (false? (source/needs-transition? nil test-channel (slate-source) (ms-ago 0))))))

(deftest needs-transition-refreshes-stale-slate
  (testing "slate older than its TTL refreshes even with no live event"
    (with-current-event nil
      #(is (true? (source/needs-transition? nil test-channel (slate-source)
                                            (ms-ago (inc source/slate-refresh-secs))))))))

(deftest needs-transition-handles-missing-playout
  (testing "no playout configured -> fallback identity, fresh slate stays put"
    (with-redefs [db-playouts/get-playout-for-channel (fn [_ _] nil)]
      (is (false? (source/needs-transition? nil test-channel (slate-source) (ms-ago 0))))
      (is (true?  (source/needs-transition? nil test-channel (content-source 42) (ms-ago 0)))))))
