(ns pseudovision.scheduling.core-build-failure-test
  "A slot error inside build! must leave build_success=false on the playout.
   The in-transaction failure record is rolled back with the build, so build!
   has to write it again outside the transaction."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [pseudovision.db.channels :as channels-db]
            [pseudovision.db.playouts :as playout-db]
            [pseudovision.db.schedules :as schedules-db]
            [pseudovision.scheduling.core :as sched]
            [pseudovision.scheduling.cursor]
            [pseudovision.util.time :as t]))

(deftest slot-error-is-recorded-outside-the-rolled-back-transaction
  (let [in-tx?  (atom false)
        updates (atom [])]
    (with-redefs-fn
      {#'jdbc/transact (fn [db f & _]
                         (reset! in-tx? true)
                         (try (f db)
                              (catch Exception e
                                ;; rollback: discard what the tx wrote
                                (swap! updates (fn [us] (vec (remove :in-tx? us))))
                                (throw e))
                              (finally (reset! in-tx? false))))
       #'channels-db/get-channel                    (fn [_ _] {:channels/id 3})
       #'schedules-db/get-schedule                  (fn [_ _] {:schedules/name "s"})
       #'schedules-db/list-slots                    (fn [_ _] [{:schedule-slots/id 9
                                                                :schedule-slots/slot-index 0}])
       #'playout-db/list-event-spans-after          (fn [& _] [])
       #'playout-db/get-event-straddling            (fn [& _] nil)
       #'playout-db/lock-for-build!                 (fn [& _] nil)
       #'playout-db/find-overlaps                   (fn [& _] [])
       #'playout-db/delete-non-manual-events-after! (fn [& _] nil)
       #'playout-db/recent-filler-airings           (fn [& _] [])
       #'playout-db/recent-content-airings          (fn [& _] {})
       #'playout-db/bulk-insert-events!             (fn [& _] nil)
       #'playout-db/update-playout!                 (fn [_ _ attrs]
                                                      (swap! updates conj
                                                             (assoc attrs :in-tx? @in-tx?)))
       #'pseudovision.scheduling.core/process-slot  (fn [& _] (throw (ex-info "boom" {})))}
      (fn []
        (is (thrown? Exception
                     (sched/build! :db {:lookahead-hours 24}
                                   {:playouts/id 1 :playouts/schedule-id 2
                                    :playouts/channel-id 3})))
        (testing "the committed playout update records the failure"
          (is (= 1 (count @updates)))
          (is (false? (:build-success (first @updates))))
          (is (re-find #"boom" (:build-message (first @updates)))))))))

(def ^:private t0 (java.time.Instant/parse "2030-01-01T10:00:00Z"))

(defn- with-build-stubs
  "Runs `f` with build!'s database calls stubbed. `overrides` replaces any of
   them. Returns {:inserted [...] :updates [...] :deleted-from [...]}."
  [overrides f]
  (let [log (atom {:inserted [] :updates [] :deleted-from [] :locked []})]
    (with-redefs-fn
      (merge
       {#'jdbc/transact (fn [db f & _] (f db))
        #'channels-db/get-channel                    (fn [_ _] {:channels/id 3})
        #'schedules-db/get-schedule                  (fn [_ _] {:schedules/name "s"})
        #'schedules-db/list-slots                    (fn [_ _] [{:schedule-slots/id 9
                                                                 :schedule-slots/slot-index 0}])
        #'playout-db/list-event-spans-after          (fn [& _] [])
        #'playout-db/get-event-straddling            (fn [& _] nil)
        #'playout-db/lock-for-build!                 (fn [_ id] (swap! log update :locked conj id))
        #'playout-db/find-overlaps                   (fn [& _] [])
        #'playout-db/delete-non-manual-events-after! (fn [_ _ from] (swap! log update :deleted-from conj from))
        #'playout-db/recent-filler-airings           (fn [& _] [])
        #'playout-db/recent-content-airings          (fn [& _] {})
        #'playout-db/bulk-insert-events!             (fn [_ evs] (swap! log update :inserted into evs))
        #'playout-db/update-playout!                 (fn [_ _ attrs] (swap! log update :updates conj attrs))}
       overrides)
      (fn [] (f log)))))

(defn- ev [kind start-min end-min]
  {:kind      kind
   :start-at  (.plusSeconds ^java.time.Instant t0 (* 60 start-min))
   :finish-at (.plusSeconds ^java.time.Instant t0 (* 60 end-min))})

(deftest build-trims-filler-overruns-and-takes-the-lock
  (with-build-stubs
    {#'pseudovision.scheduling.core/process-slot
     (let [n (atom 0)]
       (fn [_ cursor & _]
         (if (= 1 (swap! n inc))
           ;; a post break that runs 3s into the next program
           [[(ev "content" 0 30)
             (assoc (ev "post" 30 60) :finish-at (.plusSeconds ^java.time.Instant t0 3603))
             (ev "content" 60 90)]
            (assoc cursor :next-start (.plusSeconds ^java.time.Instant t0 (* 60 90)))]
           [[] (assoc cursor :next-start (.plusSeconds ^java.time.Instant t0 (* 60 60 48)))])))
     #'t/now (fn [] t0)}
    (fn [log]
      (sched/build! :db {:lookahead-hours 1 :reset-cursor? true}
                    {:playouts/id 1 :playouts/schedule-id 2 :playouts/channel-id 3})
      (let [{:keys [inserted locked]} @log
            post (first (filter #(= "post" (:kind %)) inserted))]
        (is (= [1] locked) "the build takes the playout's advisory lock")
        (is (= (.plusSeconds ^java.time.Instant t0 3600) (:finish-at post))
            "the post break ends where the next program starts")))))

(deftest build-rejects-a-timeline-that-still-overlaps
  (with-build-stubs
    {#'pseudovision.scheduling.core/process-slot
     (fn [_ cursor & _]
       [[(ev "content" 0 30)]
        (assoc cursor :next-start (.plusSeconds ^java.time.Instant t0 (* 60 60 48)))])
     #'playout-db/find-overlaps
     (fn [& _] [{:id 1 :kind "content" :start-at t0 :finish-at t0
                 :next-id 2 :next-kind "content" :next-start-at t0 :overlap-secs 60.0}])
     #'t/now (fn [] t0)}
    (fn [log]
      (is (thrown-with-msg? Exception #"Build rejected: 1 overlapping"
                            (sched/build! :db {:lookahead-hours 1 :reset-cursor? true}
                                          {:playouts/id 1 :playouts/schedule-id 2
                                           :playouts/channel-id 3})))
      (is (false? (:build-success (last (:updates @log))))
          "the failure is recorded on the playout")
      (is (re-find #"overlapping" (:build-message (last (:updates @log))))))))

(deftest resume-starts-after-an-event-playing-at-the-edge
  (let [finish (.plusSeconds ^java.time.Instant t0 (* 60 20))]
    (with-build-stubs
      {#'pseudovision.scheduling.core/process-slot
       (fn [_ cursor & _]
         [[] (assoc cursor :next-start (.plusSeconds ^java.time.Instant t0 (* 60 60 48)))])
       #'playout-db/get-event-straddling
       (fn [_ _ at] (when (= at t0) {:playout-events/finish-at finish}))
       #'t/now (fn [] t0)}
      (fn [log]
        ;; a stale cursor in the past is fast-forwarded to now, which lands
        ;; inside the event on air
        (sched/build! :db {:lookahead-hours 1}
                      {:playouts/id 1 :playouts/schedule-id 2 :playouts/channel-id 3
                       :playouts/cursor (-> (pseudovision.scheduling.cursor/init
                                         (.minusSeconds ^java.time.Instant t0 3600))
                                        (update :next-start str))})
        (is (= [finish] (:deleted-from @log))
            "the new timeline starts when the on-air event finishes")))))

(deftest reset-keeps-each-series-on-its-next-episode
  (testing "a reset rebuild resumes a series after the last episode that aired,
            instead of restarting it at episode 1"
    (let [pool   (mapv (fn [id] {:media-items/id id :_top-id 50
                                 :media-versions/duration (java.time.Duration/ofMinutes 30)})
                       (range 1 41))
          ;; the old cursor had built through episode 30; episode 12 aired last
          ;; as the JSONB reader hands it over (kebab keys, ISO instants)
          saved  {:next-start        (str (.plusSeconds ^java.time.Instant t0 (* 14 86400)))
                  :slot-index        0
                  :next-guide-group  1
                  :enumerator-states {"collection:7" {:index 30 :seed 0 :n 40 :last-id 30
                                                      :playback-order "chronological"}}}
          slot   {:schedule-slots/id 9 :schedule-slots/slot-index 0
                  :schedule-slots/anchor "fixed"
                  :schedule-slots/start-time (java.time.Duration/ofHours 10)
                  :schedule-slots/collection-id 7
                  :schedule-slots/fill-mode "block"
                  :schedule-slots/block-duration (java.time.Duration/ofMinutes 30)
                  :schedule-slots/tail-mode "filler"
                  :schedule-slots/playback-order "chronological"}]
      (with-build-stubs
        {#'schedules-db/list-slots              (fn [_ _] [slot])
         #'pseudovision.db.media/get-collection (fn [_ _] {:collections/id 7 :collections/kind "manual"})
         #'pseudovision.db.collections/resolve-collection (fn [_ _] pool)
         #'pseudovision.db.media/effective-tags-by-item   (fn [_ _] {})
         #'playout-db/recent-content-airings
         (fn [_ _ _] {11 [(.minusSeconds ^java.time.Instant t0 172800)]
                      12 [(.minusSeconds ^java.time.Instant t0 86400)]})
         #'t/now (fn [] t0)}
        (fn [log]
          (sched/build! :db {:lookahead-hours 1 :reset-cursor? true :zone-id "UTC"}
                        {:playouts/id 1 :playouts/schedule-id 2 :playouts/channel-id 3
                         :playouts/cursor saved})
          (is (= 13 (:media-item-id (first (filter #(= 13 (:media-item-id %)) (:inserted @log)))))
              "episode 13 is scheduled")
          (is (= [13] (vec (distinct (keep #(when (<= (:media-item-id % 0) 40) (:media-item-id %))
                                           (:inserted @log))))))
          "only episode 13 airs in the one-hour horizon")))))
