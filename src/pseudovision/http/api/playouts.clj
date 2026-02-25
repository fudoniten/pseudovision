(ns pseudovision.http.api.playouts
  (:require [pseudovision.db.playouts    :as db]
            [pseudovision.scheduling.core :as sched]
            [pseudovision.util.time      :as t]))

(defn get-playout-handler [{:keys [db]}]
  (fn [req]
    (let [channel-id (parse-long (get-in req [:path-params :channel-id]))]
      (if-let [p (db/get-playout-for-channel db channel-id)]
        {:status 200 :body p}
        {:status 404 :body {:error "No playout for this channel"}}))))

(defn rebuild-playout-handler [{:keys [db scheduling]}]
  (fn [req]
    (let [channel-id  (parse-long (get-in req [:path-params :channel-id]))
          playout     (db/get-playout-for-channel db channel-id)]
      (if playout
        (do
          (sched/rebuild! db scheduling playout)
          {:status 200 :body {:message "Rebuild triggered"}})
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
        (let [attrs (-> (:body-params req)
                        (assoc :playout-id (:playouts/id playout)
                               :is-manual  true
                               :kind       (get (:body-params req) :kind "content")))]
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
