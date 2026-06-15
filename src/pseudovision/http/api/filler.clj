(ns pseudovision.http.api.filler
  (:require [pseudovision.db.filler        :as db]
            [pseudovision.db.media         :as media-db]
            [pseudovision.util.pagination  :as pagination]))

(defn- unqualify-keys [m]
  (when m
    (reduce-kv (fn [acc k v] (assoc acc (keyword (name k)) v)) {} m)))

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

(defn list-presets-handler [{:keys [db]}]
  (fn [req]
    (let [qp     (get-in req [:parameters :query])
          limit  (or (:limit qp) 100)
          offset (or (:offset qp) 0)
          total  (db/count-filler-presets db)
          items  (mapv unqualify-keys (db/list-filler-presets db {:limit limit :offset offset}))]
      {:status 200
       :body (pagination/offset-pagination-response items limit offset total)})))

(defn create-preset-handler [{:keys [db]}]
  (fn [req]
    (let [[result attrs] (resolve-media-item-ref db (get-in req [:parameters :body]))]
      (if (= result :not-found)
        {:status 404 :body {:error (str "Media item not found: " attrs)}}
        {:status 201 :body (unqualify-keys (db/create-filler-preset! db attrs))}))))

(defn get-preset-handler [{:keys [db]}]
  (fn [req]
    (let [id (get-in req [:parameters :path :id])]
      (if-let [p (db/get-filler-preset db id)]
        {:status 200 :body (unqualify-keys p)}
        {:status 404 :body {:error "Filler preset not found"}}))))

(defn update-preset-handler [{:keys [db]}]
  (fn [req]
    (let [id             (get-in req [:parameters :path :id])
          [result attrs] (resolve-media-item-ref db (get-in req [:parameters :body]))]
      (if (= result :not-found)
        {:status 404 :body {:error (str "Media item not found: " attrs)}}
        (if-let [p (db/update-filler-preset! db id attrs)]
          {:status 200 :body (unqualify-keys p)}
          {:status 404 :body {:error "Filler preset not found"}})))))

(defn delete-preset-handler [{:keys [db]}]
  (fn [req]
    (db/delete-filler-preset! db (get-in req [:parameters :path :id]))
    {:status 204 :body nil}))
