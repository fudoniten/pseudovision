(ns pseudovision.http.api.playouts
  (:require [pseudovision.db.playouts      :as db]
            [pseudovision.scheduling.core  :as sched]
            [pseudovision.util.time         :as t]
            [pseudovision.util.sql          :as sql-util]
            [pseudovision.util.pagination   :as pagination]))

(defn- unqualify-keys [m]
  (when m
    (reduce-kv (fn [acc k v] (assoc acc (keyword (name k)) v)) {} m)))

(defn get-playout-handler [{:keys [db]}]
  (fn [req]
    (let [channel-id (get-in req [:parameters :path :channel-id])]
      (if-let [p (db/get-playout-for-channel db channel-id)]
        {:status 200 :body (unqualify-keys p)}
        {:status 404 :body {:error "No playout for this channel"}}))))

(defn rebuild-playout-handler [{:keys [db]}]
  (fn [req]
    (let [channel-id   (get-in req [:parameters :path :channel-id])
          playout      (db/get-playout-for-channel db channel-id)
          qp           (get-in req [:parameters :query])
          from         (or (:from qp) "now")
          horizon-days (or (:horizon qp) 14)]
      (if playout
        (let [playout-id (:playouts/id playout)
              count (case from
                      "now"     (sched/rebuild-from-now! db playout-id horizon-days)
                      "horizon" (sched/rebuild-horizon! db playout-id 7 horizon-days)
                      (sched/rebuild-from-now! db playout-id horizon-days))]
          {:status 200
           :body {:message "Rebuild complete"
                  :events-generated count
                  :horizon-days horizon-days}})
        {:status 404 :body {:error "No playout for this channel"}}))))

(defn- parse-instant [s]
  (when s
    (try (java.time.Instant/parse s)
         (catch Exception _ nil))))

(defn- enrich-event
  "Adds display-friendly fields: resolves title from metadata and adds a
   relative API link to the media item."
  [event]
  (let [base         (unqualify-keys event)
        media-id     (:media-item-id base)
        title        (:title base)
        display-name (or (:custom-title base) title)]
    (assoc base
           :title          display-name
           :media-item-link (when media-id
                              (str "/api/media/items/" media-id)))))

(defn list-events-handler [{:keys [db]}]
  (fn [req]
    (let [channel-id (get-in req [:parameters :path :channel-id])
          qp         (get-in req [:parameters :query])
          limit      (or (:limit qp) 100)
          cursor     (parse-instant (:cursor qp))
          playout    (db/get-playout-for-channel db channel-id)]
      (if playout
        (let [now    (t/now)
              events (db/get-upcoming-events-with-metadata
                       db (:playouts/id playout) now limit
                       :cursor cursor)
              items  (mapv enrich-event events)]
          {:status 200
           :body (pagination/cursor-pagination-response
                   items
                   limit
                   (fn [last-item]
                     (when-let [start-at (:start-at last-item)]
                       (str start-at))))})
        {:status 404 :body {:error "No playout for this channel"}}))))

(defn- ->instant [x]
  (cond
    (nil? x)        nil
    (string? x)     (java.time.Instant/parse x)
    :else           x))

(defn inject-event-handler
  "Injects a manual event (bumper, ad, etc.) into the playout timeline.
   The event is inserted with is_manual=true so rebuilds preserve it."
  [{:keys [db]}]
  (fn [req]
    (let [channel-id (get-in req [:parameters :path :channel-id])
          playout    (db/get-playout-for-channel db channel-id)]
      (if playout
        (let [body  (get-in req [:parameters :body])
              kind  (or (:kind body) "content")
              attrs (-> body
                        (assoc :playout-id (:playouts/id playout)
                               :is-manual  true
                               :kind       (sql-util/->pg-enum "event_kind" kind))
                        (cond->
                          (:start-at body)  (update :start-at  ->instant)
                          (:finish-at body) (update :finish-at ->instant)))]
          {:status 201 :body (db/create-event! db attrs)})
        {:status 404 :body {:error "No playout for this channel"}}))))

(defn update-event-handler [{:keys [db]}]
  (fn [req]
    (let [id    (get-in req [:parameters :path :id])
          body  (get-in req [:parameters :body])
          attrs (cond-> body
                  (:start-at  body) (update :start-at  ->instant)
                  (:finish-at body) (update :finish-at ->instant)
                  (:kind      body) (update :kind #(sql-util/->pg-enum "event_kind" %)))]
      (if-let [ev (db/update-event! db id attrs)]
        {:status 200 :body ev}
        {:status 404 :body {:error "Event not found"}}))))

(defn delete-event-handler [{:keys [db]}]
  (fn [req]
    (db/delete-event! db (get-in req [:parameters :path :id]))
    {:status 204 :body nil}))
