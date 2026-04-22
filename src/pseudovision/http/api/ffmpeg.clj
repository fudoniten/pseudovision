(ns pseudovision.http.api.ffmpeg
  "API handlers for FFmpeg profile management."
  (:require [pseudovision.db.ffmpeg :as db-ffmpeg]
            [taoensso.timbre :as log]))

(defn- unqualify-keys [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (assoc acc (keyword (name k)) v))
               {} m)))

(defn list-profiles-handler
  "GET /api/ffmpeg/profiles — list all FFmpeg profiles."
  [{:keys [db]}]
  (fn [_req]
    {:status 200
     :body   (mapv unqualify-keys (db-ffmpeg/list-profiles db))}))

(defn get-profile-handler
  "GET /api/ffmpeg/profiles/:id"
  [{:keys [db]}]
  (fn [req]
    (let [id (get-in req [:parameters :path :id])]
      (if-let [profile (db-ffmpeg/get-profile db id)]
        {:status 200 :body (unqualify-keys profile)}
        {:status 404 :body {:error "FFmpeg profile not found"}}))))

(defn create-profile-handler
  "POST /api/ffmpeg/profiles"
  [{:keys [db]}]
  (fn [req]
    (try
      (let [attrs   (get-in req [:parameters :body])
            profile (db-ffmpeg/create-profile! db attrs)]
        {:status 201 :body profile})
      (catch Exception e
        (log/error e "Failed to create FFmpeg profile")
        {:status 400
         :body {:error (str "Failed to create FFmpeg profile: " (.getMessage e))}}))))

(defn update-profile-handler
  "PUT /api/ffmpeg/profiles/:id"
  [{:keys [db]}]
  (fn [req]
    (let [id    (get-in req [:parameters :path :id])
          attrs (get-in req [:parameters :body])]
      (try
        (if-let [profile (db-ffmpeg/update-profile! db id attrs)]
          {:status 200 :body profile}
          {:status 404 :body {:error "FFmpeg profile not found"}})
        (catch Exception e
          (log/error e "Failed to update FFmpeg profile" {:id id})
          {:status 400
           :body {:error (str "Failed to update FFmpeg profile: " (.getMessage e))}})))))

(defn delete-profile-handler
  "DELETE /api/ffmpeg/profiles/:id"
  [{:keys [db]}]
  (fn [req]
    (let [id (get-in req [:parameters :path :id])]
      (try
        (if-let [profile (db-ffmpeg/delete-profile! db id)]
          {:status 200
           :body {:deleted true
                  :profile profile}}
          {:status 404 :body {:error "FFmpeg profile not found"}})
        (catch Exception e
          (log/error e "Failed to delete FFmpeg profile" {:id id})
          {:status 400
           :body {:error (str "Failed to delete FFmpeg profile: " (.getMessage e))}})))))
