(ns pseudovision.http.api.playouts
  (:require [pseudovision.db.playouts      :as db]
            [pseudovision.scheduling.engine :as engine]
            [pseudovision.util.time         :as t]
            [pseudovision.util.sql          :as sql-util]))

(defn get-playout-handler [{:keys [db]}]
  (fn [req]
    (let [channel-id (parse-long (get-in req [:path-params :channel-id]))]
      (if-let [p (db/get-playout-for-channel db channel-id)]
        {:status 200 :body p}
        {:status 404 :body {:error "No playout for this channel"}}))))

(defn rebuild-playout-handler [{:keys [db]}]
  (fn [req]
    (let [channel-id  (parse-long (get-in req [:path-params :channel-id]))
          playout     (db/get-playout-for-channel db channel-id)
          from        (get-in req [:query-params "from"] "now")
          horizon-days (parse-long (get-in req [:query-params "horizon"] "14"))]
      (if playout
        (let [playout-id (:playouts/id playout)
              count (case from
                     "now" (engine/rebuild-from-now! db playout-id horizon-days)
                     "horizon" (engine/rebuild-horizon! db playout-id 7 horizon-days)
                     (engine/rebuild-from-now! db playout-id horizon-days))]
          {:status 200 
           :body {:message "Rebuild complete"
                  :events-generated count
                  :horizon-days horizon-days}})
        {:status 404 :body {:error "No playout for this channel"}}))))

(defn list-events-handler [{:keys [db]}]
  (fn [req]
    (let [channel-id  (parse-long (get-in req [:path-params :channel-id]))
          playout     (db/get-playout-for-channel db channel-id)]
      (if playout
        (let [now    (t/now)
              events (db/get-upcoming-events db (:playouts/id playout) now 500)]
          {:status 200 :body events})
        {:status 404 :body {:error "No playout for this channel"}}))))

(defn inject-event-handler
  "Injects a manual event (bumper, ad, etc.) into the playout timeline.
   Body: { media_item_id, start_at, finish_at, kind, custom_title? }
   The event is inserted with is_manual=true so rebuilds preserve it."
  [{:keys [db]}]
  (fn [req]
    (let [channel-id (parse-long (get-in req [:path-params :channel-id]))
          playout    (db/get-playout-for-channel db channel-id)]
(if playout
        (let [kind (or (get (:body-params req) :kind) "content")
              attrs (-> (:body-params req)
                        (assoc :playout-id (:playouts/id playout)
                               :is-manual  true
                               :kind (sql-util/->pg-enum "event_kind" kind))
                        (cond->
                          ;; Parse ISO-8601 timestamp strings to Instant
                          (:start-at (:body-params req))
                          (update :start-at #(java.time.Instant/parse %))
                          (:finish-at (:body-params req))
                          (update :finish-at #(java.time.Instant/parse %))))]
          {:status 201 :body (db/create-event! db attrs)})
        {:status 404 :body {:error "No playout for this channel"}}))))

(defn update-event-handler [{:keys [db]}]
  (fn [req]
    (let [id    (parse-long (get-in req [:path-params :id]))
          attrs (:body-params req)]
      (if-let [ev (db/update-event! db id attrs)]
        {:status 200 :body ev}
        {:status 404 :body {:error "Event not found"}}))))

(defn delete-event-handler [{:keys [db]}]
  (fn [req]
    (db/delete-event! db (parse-long (get-in req [:path-params :id])))
    {:status 204 :body nil}))
