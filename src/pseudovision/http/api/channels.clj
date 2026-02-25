(ns pseudovision.http.api.channels
  (:require [pseudovision.db.channels :as db]))

(defn list-channels-handler [{:keys [db]}]
  (fn [_req]
    {:status 200
     :body   (db/list-channels db)}))

(defn get-channel-handler [{:keys [db]}]
  (fn [req]
    (let [id (parse-long (get-in req [:path-params :id]))]
      (if-let [ch (db/get-channel db id)]
        {:status 200 :body ch}
        {:status 404 :body {:error "Channel not found"}}))))

(defn create-channel-handler [{:keys [db]}]
  (fn [req]
    (let [attrs (:body-params req)]
      {:status 201
       :body   (db/create-channel! db attrs)})))

(defn update-channel-handler [{:keys [db]}]
  (fn [req]
    (let [id    (parse-long (get-in req [:path-params :id]))
          attrs (:body-params req)]
      (if-let [ch (db/update-channel! db id attrs)]
        {:status 200 :body ch}
        {:status 404 :body {:error "Channel not found"}}))))

(defn delete-channel-handler [{:keys [db]}]
  (fn [req]
    (let [id (parse-long (get-in req [:path-params :id]))]
      (db/delete-channel! db id)
      {:status 204 :body nil})))
