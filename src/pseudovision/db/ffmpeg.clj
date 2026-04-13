(ns pseudovision.db.ffmpeg
  "Database operations for FFmpeg profiles."
  (:require [pseudovision.db.core :as db]
            [pseudovision.util.sql :as sql-util]
            [honey.sql :as h]
            [taoensso.timbre :as log]))

(defn list-profiles
  "List all FFmpeg profiles."
  [ds]
  (db/query ds (h/format {:select [:*]
                          :from [:ffmpeg-profiles]
                          :order-by [[:id :asc]]})))

(defn get-profile
  "Get an FFmpeg profile by ID."
  [ds id]
  (db/query-one ds (h/format {:select [:*]
                              :from [:ffmpeg-profiles]
                              :where [:= :id id]})))

(defn create-profile!
  "Create a new FFmpeg profile.
   
   attrs should contain:
   - :name - Profile name (required)
   - :config - JSONB config map (optional, defaults to {})
   
   Example config:
   {:video-codec \"libx264\"
    :audio-codec \"aac\"
    :preset \"veryfast\"
    :video-bitrate \"2000k\"
    :audio-bitrate \"128k\"}"
  [ds attrs]
  (let [result (db/execute-one! 
                ds
                (h/format 
                  {:insert-into :ffmpeg-profiles
                   :values [(cond-> attrs
                              true (assoc :config (sql-util/->jsonb (or (:config attrs) {}))))]
                   :returning [:*]}))]
    (log/info "Created FFmpeg profile"
              {:profile-id (:ffmpeg-profiles/id result)
               :name (:name attrs)})
    result))

(defn update-profile!
  "Update an existing FFmpeg profile."
  [ds id attrs]
  (let [result (db/execute-one!
                ds
                (h/format
                  {:update :ffmpeg-profiles
                   :set (cond-> attrs
                          (:config attrs) (update :config sql-util/->jsonb))
                   :where [:= :id id]
                   :returning [:*]}))]
    (log/info "Updated FFmpeg profile" {:profile-id id})
    result))

(defn delete-profile!
  "Delete an FFmpeg profile by ID."
  [ds id]
  (let [result (db/execute-one!
                ds
                (h/format
                  {:delete-from :ffmpeg-profiles
                   :where [:= :id id]
                   :returning [:*]}))]
    (log/info "Deleted FFmpeg profile" {:profile-id id})
    result))
