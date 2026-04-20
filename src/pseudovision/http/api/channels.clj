(ns pseudovision.http.api.channels
  (:require [pseudovision.db.channels :as db]
            [taoensso.timbre :as log]))

(defn list-channels-handler [{:keys [db]}]
  (fn [req]
    ;; Support ?uuid=xxx query parameter to filter by UUID
    (let [uuid-param (get-in req [:query-params "uuid"])
          _          (when uuid-param
                       (log/debug "UUID query param received" {:uuid uuid-param}))]
      (if uuid-param
        (if-let [channel (db/get-channel-by-uuid db uuid-param)]
          (do
            (log/debug "Found channel by UUID" {:channel-id (:channels/id channel)})
            {:status 200 :body channel})
          (do
            (log/warn "Channel not found by UUID" {:uuid uuid-param})
            {:status 404 :body {:error "Channel not found"}}))
        {:status 200 :body (db/list-channels db)}))))

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
