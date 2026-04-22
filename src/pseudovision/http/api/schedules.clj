(ns pseudovision.http.api.schedules
  (:require [pseudovision.db.schedules :as db]))

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
  (fn [_req]
    {:status 200 :body (mapv unqualify-keys (db/list-schedules db))}))

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
    (let [schedule-id (get-in req [:parameters :path :schedule-id])]
      {:status 200 :body (mapv unqualify-keys (db/list-slots db schedule-id))})))

(defn get-slot-handler [{:keys [db]}]
  (fn [req]
    (let [id (get-in req [:parameters :path :id])]
      (if-let [slot (db/get-slot db id)]
        {:status 200 :body (unqualify-keys slot)}
        {:status 404 :body {:error "Slot not found"}}))))

(defn create-slot-handler [{:keys [db]}]
  (fn [req]
    (let [schedule-id (get-in req [:parameters :path :schedule-id])
          attrs       (assoc (get-in req [:parameters :body]) :schedule-id schedule-id)]
      {:status 201 :body (db/create-slot! db attrs)})))

(defn update-slot-handler [{:keys [db]}]
  (fn [req]
    (let [id    (get-in req [:parameters :path :id])
          attrs (get-in req [:parameters :body])]
      (if-let [slot (db/update-slot! db id attrs)]
        {:status 200 :body slot}
        {:status 404 :body {:error "Slot not found"}}))))

(defn delete-slot-handler [{:keys [db]}]
  (fn [req]
    (db/delete-slot! db (get-in req [:parameters :path :id]))
    {:status 204 :body nil}))
