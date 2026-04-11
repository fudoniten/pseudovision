(ns pseudovision.http.api.test
  "Test utilities API endpoints.
   
   Provides endpoints for creating and managing test channels for streaming verification."
  (:require [pseudovision.dev.test-channel :as tc]
            [taoensso.timbre :as log]))

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
                           :info (str base-url "/api/test/info")}
                :usage {:create {:method "POST"
                                :url (str base-url "/api/test/channels")
                                :body {:number "999" 
                                      :name "My Test Channel"
                                      :collection_id (when default-collection 
                                                      (:collections/id default-collection))}}
                       :delete {:method "DELETE"
                               :url (str base-url "/api/test/channels/999")}
                       :list {:method "GET"
                             :url (str base-url "/api/test/channels")}}}})
      
      (catch Exception e
        (log/error e "Failed to get test info via API")
        {:status 500
         :body {:error "Failed to get test info"
                :message (.getMessage e)}}))))
