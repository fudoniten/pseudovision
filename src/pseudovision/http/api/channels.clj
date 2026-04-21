(ns pseudovision.http.api.channels
  (:require [pseudovision.db.channels :as db]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Response shape normalisation
;;
;; The DB query builders return next.jdbc rows with namespace-qualified keys
;; (e.g. :channels/id, :channels/name). The public API advertises an
;; unqualified shape (:id, :name, ...) which matches the shape returned by
;; INSERT/UPDATE RETURNING (as-unqualified-kebab-maps in db/core). This
;; helper strips namespaces so GET/POST/PUT all return the same shape.
;; ---------------------------------------------------------------------------

(defn- unqualify-keys [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (assoc acc (keyword (name k)) v))
               {} m)))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn list-channels-handler [{:keys [db]}]
  (fn [req]
    (if-let [uuid (get-in req [:parameters :query :uuid])]
      (if-let [channel (db/get-channel-by-uuid db uuid)]
        (do
          (log/debug "Found channel by UUID" {:channel-id (:channels/id channel)})
          {:status 200 :body (unqualify-keys channel)})
        (do
          (log/warn "Channel not found by UUID" {:uuid uuid})
          {:status 404 :body {:error "Channel not found"}}))
      {:status 200
       :body   (mapv unqualify-keys (db/list-channels db))})))

(defn get-channel-handler [{:keys [db]}]
  (fn [req]
    (let [id (get-in req [:parameters :path :id])]
      (if-let [ch (db/get-channel db id)]
        {:status 200 :body (unqualify-keys ch)}
        {:status 404 :body {:error "Channel not found"}}))))

(defn create-channel-handler [{:keys [db]}]
  (fn [req]
    (let [attrs (get-in req [:parameters :body])]
      {:status 201
       :body   (db/create-channel! db attrs)})))

(defn update-channel-handler [{:keys [db]}]
  (fn [req]
    (let [id    (get-in req [:parameters :path :id])
          attrs (get-in req [:parameters :body])]
      (if-let [ch (db/update-channel! db id attrs)]
        {:status 200 :body ch}
        {:status 404 :body {:error "Channel not found"}}))))

(defn delete-channel-handler [{:keys [db]}]
  (fn [req]
    (let [id (get-in req [:parameters :path :id])]
      (db/delete-channel! db id)
      {:status 204 :body nil})))
