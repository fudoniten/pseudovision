(ns pseudovision.http.api.schedules
  (:require [pseudovision.db.schedules :as db]))

;; ---------------------------------------------------------------------------
;; Schedules
;; ---------------------------------------------------------------------------

(defn list-schedules-handler [{:keys [db]}]
  (fn [_req]
    {:status 200 :body (db/list-schedules db)}))

(defn get-schedule-handler [{:keys [db]}]
  (fn [req]
    (let [id (parse-long (get-in req [:path-params :id]))]
      (if-let [s (db/get-schedule db id)]
        {:status 200 :body s}
        {:status 404 :body {:error "Schedule not found"}}))))

(defn create-schedule-handler [{:keys [db]}]
  (fn [req]
    {:status 201 :body (db/create-schedule! db (:body-params req))}))

(defn update-schedule-handler [{:keys [db]}]
  (fn [req]
    (let [id (parse-long (get-in req [:path-params :id]))]
      (if-let [s (db/update-schedule! db id (:body-params req))]
        {:status 200 :body s}
        {:status 404 :body {:error "Schedule not found"}}))))

(defn delete-schedule-handler [{:keys [db]}]
  (fn [req]
    (db/delete-schedule! db (parse-long (get-in req [:path-params :id])))
    {:status 204 :body nil}))

;; ---------------------------------------------------------------------------
;; Slots
;; ---------------------------------------------------------------------------

(defn list-slots-handler [{:keys [db]}]
  (fn [req]
    (let [schedule-id (parse-long (get-in req [:path-params :schedule-id]))]
      {:status 200 :body (db/list-slots db schedule-id)})))

(defn get-slot-handler [{:keys [db]}]
  (fn [req]
    (let [id (parse-long (get-in req [:path-params :id]))]
      (if-let [slot (db/get-slot db id)]
        {:status 200 :body slot}
        {:status 404 :body {:error "Slot not found"}}))))

(defn create-slot-handler [{:keys [db]}]
  (fn [req]
    (let [schedule-id (parse-long (get-in req [:path-params :schedule-id]))
          attrs       (assoc (:body-params req) :schedule-id schedule-id)]
      {:status 201 :body (db/create-slot! db attrs)})))

(defn update-slot-handler [{:keys [db]}]
  (fn [req]
    (let [id (parse-long (get-in req [:path-params :id]))]
      (if-let [slot (db/update-slot! db id (:body-params req))]
        {:status 200 :body slot}
        {:status 404 :body {:error "Slot not found"}}))))

(defn delete-slot-handler [{:keys [db]}]
  (fn [req]
    (db/delete-slot! db (parse-long (get-in req [:path-params :id])))
    {:status 204 :body nil}))
