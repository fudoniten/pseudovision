(ns pseudovision.http.api.test
  "Test utilities API endpoints.
   
   Provides endpoints for creating and managing test channels for streaming verification."
  (:require [pseudovision.dev.test-channel :as tc]
            [pseudovision.db.channels :as db-channels]
            [pseudovision.db.core :as db-core]
            [pseudovision.util.sql :as sql-util]
            [honey.sql :as h]
            [honey.sql.helpers :as hh]
            [taoensso.timbre :as log]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell])
  (:import [java.util UUID]))

(defn create-test-channel-handler
  "POST /api/test/channels
   
   Creates a test channel for streaming verification.
   
   Request body (all optional):
   {
     \"number\": \"999\",           // Channel number (default: \"999\")
     \"name\": \"Test Channel\",    // Channel name (default: \"Test Channel\")
     \"collection_id\": 5          // Collection ID (default: first available)
   }
   
   Response:
   {
     \"channel\": {...},
     \"schedule\": {...},
     \"playout\": {...},
     \"uuid\": \"550e8400-e29b-41d4-a716-446655440000\",
     \"stream_url\": \"http://localhost:8080/stream/550e8400-...\",
     \"playlist_url\": \"http://localhost:8080/iptv/channels.m3u\",
     \"epg_url\": \"http://localhost:8080/xmltv\"
   }"
  [{:keys [db]}]
  (fn [req]
    (try
      (let [params (:body-params req)
            opts (cond-> {:verbose false}  ; Don't print console output for API calls
                   (:number params) (assoc :number (:number params))
                   (:name params) (assoc :name (:name params))
                   (:collection_id params) (assoc :collection-id (:collection_id params)))
            
            result (tc/create-test-channel! db opts)
            host (or (get-in req [:headers "host"]) "localhost:8080")
            scheme (if (get-in req [:headers "x-forwarded-proto"])
                     (get-in req [:headers "x-forwarded-proto"])
                     "http")]
        
        (log/info "Created test channel via API" 
                  {:uuid (:uuid result)
                   :number (get-in result [:channel :channels/number])})
        
        {:status 201
         :body {:channel (:channel result)
                :schedule (:schedule result)
                :playout (:playout result)
                :uuid (:uuid result)
                :stream_url (str scheme "://" host "/stream/" (:uuid result))
                :playlist_url (str scheme "://" host "/iptv/channels.m3u")
                :epg_url (str scheme "://" host "/xmltv")}})
      
      (catch Exception e
        (log/error e "Failed to create test channel via API")
        {:status 500
         :body {:error "Failed to create test channel"
                :message (.getMessage e)}}))))

(defn delete-test-channel-handler
  "DELETE /api/test/channels/:identifier
   
   Deletes a test channel by UUID or channel number.
   
   Path params:
   - identifier: Channel UUID or channel number (e.g., \"999\")
   
   Response:
   {
     \"deleted\": true,
     \"channel\": {...}
   }"
  [{:keys [db]}]
  (fn [req]
    (let [identifier (get-in req [:path-params :identifier])]
      (try
        (if-let [deleted-channel (tc/delete-test-channel! db identifier {:verbose false})]
          (do
            (log/info "Deleted test channel via API" 
                      {:identifier identifier
                       :channel-id (:channels/id deleted-channel)})
            {:status 200
             :body {:deleted true
                    :channel deleted-channel
                    :message (str "Deleted channel: " (:channels/name deleted-channel))}})
          
          {:status 404
           :body {:deleted false
                  :error "Channel not found"
                  :identifier identifier}})
        
        (catch Exception e
          (log/error e "Failed to delete test channel via API" {:identifier identifier})
          {:status 500
           :body {:deleted false
                  :error "Failed to delete test channel"
                  :message (.getMessage e)}})))))

(defn list-test-channels-handler
  "GET /api/test/channels
   
   Lists all channels with 'Test' in the name or in the 'Testing' group.
   Useful for finding test channels to clean up.
   
   Response:
   {
     \"channels\": [...]
   }"
  [{:keys [db]}]
  (fn [_req]
    (try
      (let [test-channels (pseudovision.db.core/query 
                           db
                           ["SELECT * FROM channels 
                             WHERE name LIKE '%Test%' 
                                OR group_name = 'Testing' 
                             ORDER BY sort_number"])]
        
        {:status 200
         :body {:channels test-channels
                :count (count test-channels)}})
      
      (catch Exception e
        (log/error e "Failed to list test channels via API")
        {:status 500
         :body {:error "Failed to list test channels"
                :message (.getMessage e)}}))))

(defn create-test-collection-handler
  "POST /api/test/collection
   
   Creates a manual collection with ALL media items from all libraries.
   This is a convenience endpoint for quick testing.
   
   Request body (optional):
   {
     \"name\": \"My Test Collection\"  // default: \"Test Collection (All Media)\"
   }
   
   Response:
   {
     \"collection\": {...},
     \"item_count\": 42
   }"
  [{:keys [db]}]
  (fn [req]
    (try
      (let [params (:body-params req)
            coll-name (or (:name params) "Test Collection (All Media)")
            
            ;; Create manual collection
            collection (pseudovision.db.core/execute-one! 
                        db
                        (h/format
                          {:insert-into :collections
                           :values [{:kind (sql-util/->pg-enum "collection_kind" "manual")
                                    :name coll-name
                                    :config (sql-util/->jsonb {})}]
                           :returning [:*]}))
            
            collection-id (:collections/id collection)
            
            ;; Add ALL media items to it
            ;; Using raw SQL for INSERT SELECT since HoneySQL syntax is complex
            _ (pseudovision.db.core/execute! 
               db
               [(str "INSERT INTO collection_items (collection_id, media_item_id) "
                     "SELECT ?::int, id FROM media_items "
                     "ON CONFLICT (collection_id, media_item_id) DO NOTHING")
                collection-id])
            
            ;; Count items
            count-result (pseudovision.db.core/query-one
                          db
                          (h/format
                            {:select [[[:count :*] :count]]
                             :from :collection-items
                             :where [:= :collection-id collection-id]}))
            
            item-count (:count count-result)]
        
        (log/info "Created test collection with all media" 
                  {:collection-id collection-id
                   :name coll-name
                   :item-count item-count})
        
        {:status 201
         :body {:collection collection
                :item_count item-count
                :message (str "Created collection with " item-count " items")}})
      
      (catch Exception e
        (log/error e "Failed to create test collection via API")
        {:status 500
         :body {:error "Failed to create test collection"
                :message (.getMessage e)}}))))

(defn add-test-artwork-handler
  "POST /api/test/channels/:identifier/artwork
   
   Generates a simple test logo for a channel.
   
   Response:
   {
     \"artwork\": {...},
     \"logo_url\": \"https://.../logos/{uuid}\"
   }"
  [{:keys [db]}]
  (fn [req]
    (try
      (let [identifier (get-in req [:path-params :identifier])
            ;; Try to find channel by UUID or number
            channel (or (try (db-channels/get-channel-by-uuid 
                              db (UUID/fromString identifier))
                            (catch Exception _ nil))
                       (db-channels/get-channel-by-number db identifier))]
        
        (if-not channel
          {:status 404
           :body {:error "Channel not found"}}
          
          (let [channel-id (:channels/id channel)
                channel-uuid (:channels/uuid channel)
                channel-number (:channels/number channel)
                channel-name (:channels/name channel)
                
                ;; Generate logo using ImageMagick
                logo-dir "/tmp/pseudovision-logos"
                logo-filename (str "channel-" channel-number ".png")
                logo-path (str logo-dir "/" logo-filename)
                
                ;; For testing, just insert a placeholder path
                ;; In production, you'd upload an actual image file or generate it
                logo-path (str "/tmp/pseudovision-logos/channel-" channel-number ".png")
                
                ;; Check if artwork already exists
                existing-artwork (first (db-core/query 
                                        db
                                        ["SELECT * FROM channel_artwork WHERE channel_id = ? AND kind = 'logo'" 
                                         channel-id]))
                
                ;; Insert or update database entry (DB entry only, file doesn't exist yet)
                ;; This lets us test the M3U/XMLTV integration (they'll show the URL)
                ;; The /logos endpoint will return 404 until a real file is uploaded
                artwork (if existing-artwork
                          (db-core/execute-one!
                           db
                           (-> (hh/update :channel-artwork)
                               (hh/set {:path logo-path
                                        :original-content-type "image/png"})
                               (hh/where [:= :id (:channel-artwork/id existing-artwork)])
                               (hh/returning :*)
                               h/format))
                          (db-core/execute-one!
                           db
                           (-> (hh/insert-into :channel-artwork)
                               (hh/values [{:channel-id channel-id
                                            :kind (sql-util/->pg-enum "artwork_kind" "logo")
                                            :path logo-path
                                            :original-content-type "image/png"}])
                               (hh/returning :*)
                               h/format)))
                
                host (or (get-in req [:headers "host"]) "localhost:8080")
                scheme (if (get-in req [:headers "x-forwarded-proto"])
                         (get-in req [:headers "x-forwarded-proto"])
                         "http")
                logo-url (str scheme "://" host "/logos/" channel-uuid)]
            
            (log/info "Created test artwork for channel" 
                      {:channel-id channel-id
                       :channel-name channel-name
                       :path logo-path})
            
            {:status 201
             :body {:artwork artwork
                    :logo_url logo-url
                    :message (str "Created logo at " logo-path)}})))
      
      (catch Exception e
        (log/error e "Failed to create test artwork via API")
        {:status 500
         :body {:error "Failed to create test artwork"
                :message (.getMessage e)}}))))

(defn test-info-handler
  "GET /api/test/info
   
   Returns information about the test API and available collections.
   
   Response:
   {
     \"collections\": [...],
     \"default_collection\": {...},
     \"endpoints\": {...}
   }"
  [{:keys [db]}]
  (fn [req]
    (try
      (let [collections (pseudovision.db.core/query db ["SELECT * FROM collections ORDER BY id"])
            default-collection (first collections)
            host (or (get-in req [:headers "host"]) "localhost:8080")
            scheme (if (get-in req [:headers "x-forwarded-proto"])
                     (get-in req [:headers "x-forwarded-proto"])
                     "http")
            base-url (str scheme "://" host)]
        
        {:status 200
         :body {:collections collections
                :default_collection default-collection
                :endpoints {:create (str base-url "/api/test/channels")
                           :list (str base-url "/api/test/channels")
                           :delete (str base-url "/api/test/channels/:identifier")
                           :add_artwork (str base-url "/api/test/channels/:identifier/artwork")
                           :info (str base-url "/api/test/info")}
                :usage {:create {:method "POST"
                                :url (str base-url "/api/test/channels")
                                :body {:number "999" 
                                      :name "My Test Channel"
                                      :collection_id (when default-collection 
                                                      (:collections/id default-collection))}}
                       :delete {:method "DELETE"
                               :url (str base-url "/api/test/channels/999")}
                       :add_artwork {:method "POST"
                                    :url (str base-url "/api/test/channels/999/artwork")
                                    :body {}}
                       :list {:method "GET"
                             :url (str base-url "/api/test/channels")}}}})
      
      (catch Exception e
        (log/error e "Failed to get test info via API")
        {:status 500
         :body {:error "Failed to get test info"
                :message (.getMessage e)}}))))
