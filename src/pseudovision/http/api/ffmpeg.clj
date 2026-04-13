(ns pseudovision.http.api.ffmpeg
  "API handlers for FFmpeg profile management."
  (:require [pseudovision.db.ffmpeg :as db-ffmpeg]
            [taoensso.timbre :as log]))

(defn list-profiles-handler
  "GET /api/ffmpeg/profiles
   
   List all FFmpeg profiles."
  [{:keys [db]}]
  (fn [_req]
    {:status 200
     :body (db-ffmpeg/list-profiles db)}))

(defn get-profile-handler
  "GET /api/ffmpeg/profiles/:id
   
   Get a specific FFmpeg profile by ID."
  [{:keys [db]}]
  (fn [req]
    (let [id (parse-long (get-in req [:path-params :id]))]
      (if-let [profile (db-ffmpeg/get-profile db id)]
        {:status 200
         :body profile}
        {:status 404
         :body {:error "FFmpeg profile not found"
                :id id}}))))

(defn create-profile-handler
  "POST /api/ffmpeg/profiles
   
   Create a new FFmpeg profile.
   
   Request body:
   {
     \"name\": \"High Quality\",
     \"config\": {
       \"video_codec\": \"libx264\",
       \"audio_codec\": \"aac\",
       \"preset\": \"slow\",
       \"video_bitrate\": \"4000k\",
       \"audio_bitrate\": \"192k\"
     }
   }"
  [{:keys [db]}]
  (fn [req]
    (try
      (let [attrs (:body-params req)
            profile (db-ffmpeg/create-profile! db attrs)]
        {:status 201
         :body profile})
      (catch Exception e
        (log/error e "Failed to create FFmpeg profile")
        {:status 400
         :body {:error "Failed to create FFmpeg profile"
                :message (.getMessage e)}}))))

(defn update-profile-handler
  "PUT /api/ffmpeg/profiles/:id
   
   Update an existing FFmpeg profile."
  [{:keys [db]}]
  (fn [req]
    (let [id (parse-long (get-in req [:path-params :id]))
          attrs (:body-params req)]
      (try
        (if-let [profile (db-ffmpeg/update-profile! db id attrs)]
          {:status 200
           :body profile}
          {:status 404
           :body {:error "FFmpeg profile not found"
                  :id id}})
        (catch Exception e
          (log/error e "Failed to update FFmpeg profile" {:id id})
          {:status 400
           :body {:error "Failed to update FFmpeg profile"
                  :message (.getMessage e)}})))))

(defn delete-profile-handler
  "DELETE /api/ffmpeg/profiles/:id
   
   Delete an FFmpeg profile."
  [{:keys [db]}]
  (fn [req]
    (let [id (parse-long (get-in req [:path-params :id]))]
      (try
        (if-let [profile (db-ffmpeg/delete-profile! db id)]
          {:status 200
           :body {:deleted true
                  :profile profile}}
          {:status 404
           :body {:error "FFmpeg profile not found"
                  :id id}})
        (catch Exception e
          (log/error e "Failed to delete FFmpeg profile" {:id id})
          {:status 400
           :body {:error "Failed to delete FFmpeg profile"
                  :message (.getMessage e)}})))))
