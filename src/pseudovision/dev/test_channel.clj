(ns pseudovision.dev.test-channel
  "Utility to create a test channel for streaming verification.
   
   Creates a channel with a simple schedule that plays random media items."
  (:require [pseudovision.db.channels :as db-channels]
            [pseudovision.db.schedules :as db-schedules]
            [pseudovision.db.playouts :as db-playouts]
            [pseudovision.db.media :as db-media]
            [pseudovision.db.core :as db]
            [pseudovision.util.sql :as sql-util]
            [pseudovision.scheduling.builder :as builder]
            [taoensso.timbre :as log]))

(defn- get-or-create-default-ffmpeg-profile!
  "Gets the first ffmpeg profile, or creates a default one if none exist."
  [ds]
  (if-let [profile (db/query-one ds ["SELECT * FROM ffmpeg_profiles LIMIT 1"])]
    (:ffmpeg_profiles/id profile)
    (let [result (db/execute-one! 
                  ds
                  ["INSERT INTO ffmpeg_profiles (name, config) VALUES (?, ?::jsonb) RETURNING id"
                   "default"
                   "{\"video_codec\": \"libx264\", \"audio_codec\": \"aac\"}"])]
      (:ffmpeg_profiles/id result))))

(defn- get-first-collection
  "Gets the first available collection from the database."
  [ds]
  (first (db/query ds ["SELECT * FROM collections ORDER BY id LIMIT 1"])))

(defn create-test-channel!
  "Creates a test channel with a simple schedule.
   
   Options:
   - :number - Channel number (default: \"999\")
   - :name - Channel name (default: \"Test Channel\")
   - :collection-id - Collection to play from (default: first available)
   - :verbose - Print console output (default: true)
   
   Returns: {:channel {...}, :schedule {...}, :playout {...}, :uuid \"...\"}"
  ([ds] (create-test-channel! ds {}))
  ([ds {:keys [number name collection-id verbose]
        :or {number "999"
             name "Test Channel"
             verbose true}}]
   (log/info "Creating test channel" {:number number :name name})
   
   ;; Get or create FFmpeg profile
   (let [ffmpeg-profile-id (get-or-create-default-ffmpeg-profile! ds)]
     (log/debug "Using ffmpeg profile" {:id ffmpeg-profile-id})
     
     ;; Determine collection to use
     (let [coll-id (or collection-id
                       (when-let [coll (get-first-collection ds)]
                         (do
                           (log/info "Using first available collection" 
                                     {:id (:collections/id coll)
                                      :name (:collections/name coll)})
                           (:collections/id coll))))
           _ (when-not coll-id
               (throw (ex-info "No collections found. Create a collection first or specify :collection-id"
                               {:available-collections (db/query ds ["SELECT * FROM collections"])})))
           
           ;; Create channel
           channel (db-channels/create-channel! 
                    ds
                    {:number number
                     :sort_number (parse-double number)
                     :name name
                     :group_name "Testing"
                     :streaming_mode (sql-util/->pg-enum "streaming_mode" "hls_segmenter")
                     :ffmpeg_profile_id ffmpeg-profile-id
                     :is_enabled true
                     :show_in_epg true})
           
           channel-id (:channels/id channel)
           uuid (:channels/uuid channel)]
       
       (log/info "Created channel" {:id channel-id :uuid uuid :number number})
       
       ;; Create schedule with a simple 24-hour flood slot
       (let [schedule (db-schedules/create-schedule! 
                       ds
                       {:name (str "Test Schedule - " name)
                        :shuffle_slots false
                        :random_start_point false})
             
             schedule-id (:schedules/id schedule)]
         
         (log/info "Created schedule" {:id schedule-id})
         
         ;; Create a single flood slot that plays random items from collection
         (db-schedules/create-slot! 
          ds
          {:schedule_id schedule-id
           :slot_index 0
           :anchor (sql-util/->pg-enum "slot_anchor" "sequential")
           :fill_mode (sql-util/->pg-enum "slot_fill_mode" "flood")
           :collection_id coll-id
           :playback_order (sql-util/->pg-enum "playback_order" "random")
           :guide_mode (sql-util/->pg-enum "guide_mode" "normal")})
         
         (log/info "Created schedule slot" {:collection-id coll-id})
         
         ;; Create playout and build initial events
         (let [playout (db-playouts/upsert-playout! ds channel-id schedule-id)
               playout-id (:playouts/id playout)]
           
           (log/info "Created playout" {:id playout-id})
           
           ;; Build playout events
           (try
             (builder/rebuild-playout! ds playout-id)
             (log/info "Built playout events for test channel")
             (catch Exception e
               (log/warn e "Failed to build playout events - you may need to run rebuild manually")))
           
           ;; Return info
           (let [result {:channel channel
                         :schedule schedule
                         :playout playout
                         :uuid (str uuid)
                         :stream-url (str "http://localhost:8080/stream/" uuid)}]
             
             (log/info "Test channel ready!" 
                       {:uuid uuid
                        :number number
                        :stream-url (:stream-url result)})
             
             (println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
             (println "✅ Test Channel Created!")
             (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
             (println (format "  Channel:    %s (%s)" name number))
             (println (format "  UUID:       %s" uuid))
             (println (format "  Stream URL: %s" (:stream-url result)))
             (println "\n  Test with:")
             (println (format "    curl %s" (:stream-url result)))
             (println (format "    vlc %s" (:stream-url result)))
             (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
             
             result)))))))

(defn delete-test-channel!
  "Deletes a test channel by UUID or channel number.
   
   Options:
   - :verbose - Print console output (default: true)
   
   Usage:
     (delete-test-channel! ds \"999\")
     (delete-test-channel! ds uuid)
     (delete-test-channel! ds \"999\" {:verbose false})"
  ([ds identifier] (delete-test-channel! ds identifier {}))
  ([ds identifier {:keys [verbose] :or {verbose true}}]
   (let [channel (or (db-channels/get-channel-by-uuid ds identifier)
                     (db-channels/get-channel-by-number ds identifier))]
     (if channel
       (let [channel-id (:channels/id channel)
             channel-name (:channels/name channel)]
         (db-channels/delete-channel! ds channel-id)
         (log/info "Deleted test channel" {:id channel-id :name channel-name})
         (when verbose
           (println (format "✅ Deleted channel: %s" channel-name)))
         channel)
       (do
         (log/warn "Channel not found" {:identifier identifier})
         (when verbose
           (println (format "❌ Channel not found: %s" identifier)))
         nil)))))

;; Usage in REPL:
;;
;; Assuming you have a database connection `ds`:
;; (require '[pseudovision.dev.test-channel :as tc])
;;
;; Create a test channel:
;; (def test-chan (tc/create-test-channel! ds))
;;
;; Get the stream URL:
;; (:stream-url test-chan)
;; => "http://localhost:8080/stream/550e8400-e29b-41d4-a716-446655440000"
;;
;; Create with custom options:
;; (tc/create-test-channel! ds {:number "998" 
;;                               :name "My Test Stream"
;;                               :collection-id 5})
;;
;; Delete the test channel when done:
;; (tc/delete-test-channel! ds "999")
;; (tc/delete-test-channel! ds (:uuid test-chan))
