(ns pseudovision.http.api.schedules
  (:require [pseudovision.db.schedules     :as db]
            [pseudovision.db.media         :as media-db]
            [pseudovision.util.pagination  :as pagination]))

;; ---------------------------------------------------------------------------
;; Response shape normalisation
;;
;; Schedule/slot reads come back with next.jdbc's namespace-qualified keys
;; (e.g. :schedules/id). INSERT/UPDATE RETURNING rows are already unqualified.
;; Strip the :domain/ prefix so GET/POST/PUT all emit the same shape.
;; ---------------------------------------------------------------------------

(defn- unqualify-keys [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (assoc acc (keyword (name k)) v))
               {} m)))

;; ---------------------------------------------------------------------------
;; Schedules
;; ---------------------------------------------------------------------------

(defn list-schedules-handler [{:keys [db]}]
  (fn [req]
    (let [qp     (get-in req [:parameters :query])
          limit  (or (:limit qp) 100)
          offset (or (:offset qp) 0)
          total  (db/count-schedules db)
          items  (mapv unqualify-keys (db/list-schedules db {:limit limit :offset offset}))]
      {:status 200
       :body (pagination/offset-pagination-response items limit offset total)})))

(defn get-schedule-handler [{:keys [db]}]
  (fn [req]
    (let [id (get-in req [:parameters :path :id])]
      (if-let [s (db/get-schedule db id)]
        {:status 200 :body (unqualify-keys s)}
        {:status 404 :body {:error "Schedule not found"}}))))

(defn create-schedule-handler [{:keys [db]}]
  (fn [req]
    (let [attrs (get-in req [:parameters :body])]
      {:status 201 :body (db/create-schedule! db attrs)})))

(defn update-schedule-handler [{:keys [db]}]
  (fn [req]
    (let [id    (get-in req [:parameters :path :id])
          attrs (get-in req [:parameters :body])]
      (if-let [s (db/update-schedule! db id attrs)]
        {:status 200 :body s}
        {:status 404 :body {:error "Schedule not found"}}))))

(defn delete-schedule-handler [{:keys [db]}]
  (fn [req]
    (db/delete-schedule! db (get-in req [:parameters :path :id]))
    {:status 204 :body nil}))

;; ---------------------------------------------------------------------------
;; Slots
;; ---------------------------------------------------------------------------

(defn list-slots-handler [{:keys [db]}]
  (fn [req]
    (let [schedule-id (get-in req [:parameters :path :schedule-id])
          qp          (get-in req [:parameters :query])
          limit       (or (:limit qp) 100)
          offset      (or (:offset qp) 0)
          total       (db/count-slots db schedule-id)
          items       (mapv unqualify-keys (db/list-slots db schedule-id {:limit limit :offset offset}))]
      {:status 200
       :body (pagination/offset-pagination-response items limit offset total)})))

(defn get-slot-handler [{:keys [db]}]
  (fn [req]
    (let [id (get-in req [:parameters :path :id])]
      (if-let [slot (db/get-slot db id)]
        {:status 200 :body (unqualify-keys slot)}
        {:status 404 :body {:error "Slot not found"}}))))

(defn- resolve-media-item-ref
  "If `attrs` carries a non-nil :media-item-id, resolves it (an internal id or a
   remote_key, e.g. a Jellyfin item id) to the internal integer id. Returns
   `[:ok attrs']` on success (or when no :media-item-id is present), or
   `[:not-found ref]` if the ref matches no media item."
  [db attrs]
  (let [ref (:media-item-id attrs)]
    (if (nil? ref)
      [:ok attrs]
      (if-let [item-id (media-db/resolve-media-item-id db ref)]
        [:ok (assoc attrs :media-item-id item-id)]
        [:not-found ref]))))

(defn create-slot-handler [{:keys [db]}]
  (fn [req]
    (let [schedule-id     (get-in req [:parameters :path :schedule-id])
          body            (assoc (get-in req [:parameters :body]) :schedule-id schedule-id)
          [result attrs]  (resolve-media-item-ref db body)]
      (if (= result :not-found)
        {:status 404 :body {:error (str "Media item not found: " attrs)}}
        {:status 201 :body (db/create-slot! db attrs)}))))

(defn update-slot-handler [{:keys [db]}]
  (fn [req]
    (let [id              (get-in req [:parameters :path :id])
          [result attrs]  (resolve-media-item-ref db (get-in req [:parameters :body]))]
      (if (= result :not-found)
        {:status 404 :body {:error (str "Media item not found: " attrs)}}
        (if-let [slot (db/update-slot! db id attrs)]
          {:status 200 :body slot}
          {:status 404 :body {:error "Slot not found"}})))))

(defn delete-slot-handler [{:keys [db]}]
  (fn [req]
    (db/delete-slot! db (get-in req [:parameters :path :id]))
    {:status 204 :body nil}))

(defn reorder-slots-handler [{:keys [db]}]
  (fn [req]
    (let [schedule-id (get-in req [:parameters :path :schedule-id])
          slot-ids    (get-in req [:parameters :body :slot-ids])]
      (db/reorder-slots! db schedule-id slot-ids)
      (let [slots (mapv unqualify-keys (db/list-slots db schedule-id))]
        {:status 200 :body {:slots slots}}))))
