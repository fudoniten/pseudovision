(ns pseudovision.http.api.streaming
  "Live channel streaming endpoint.
   Returns HLS playlists and segments for /stream/{uuid}."
  (:require [pseudovision.db.channels :as db-channels]
            [pseudovision.db.playouts :as db-playouts]
            [pseudovision.db.media :as db-media]
            [pseudovision.media.connection :as conn]
            [pseudovision.ffmpeg.hls :as hls]
            [pseudovision.util.time :as t]
            [taoensso.timbre :as log]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Stream State Management
;; ---------------------------------------------------------------------------

;; Global state (to be refactored into Integrant component later)
(defonce active-streams (atom {}))

(defn cleanup-dead-streams
  "Removes dead FFmpeg processes from active-streams.
   Returns count of cleaned up streams."
  []
  (let [before-count (count @active-streams)
        alive-streams (into {}
                           (filter (fn [[uuid stream]]
                                    (let [alive? (hls/process-alive? stream)]
                                      (when-not alive?
                                        (log/warn "Removing dead stream" 
                                                 {:uuid uuid 
                                                  :pid (:pid stream)}))
                                      alive?))
                                  @active-streams))]
    (reset! active-streams alive-streams)
    (- before-count (count alive-streams))))

;; ---------------------------------------------------------------------------
;; Playout Timeline Integration
;; ---------------------------------------------------------------------------

(defn- get-jellyfin-stream-url
  "Resolves a Jellyfin stream URL for a media item.
   Returns the direct stream URL or nil if not found/accessible."
  [db media-item-id]
  (when-let [row (db-media/get-media-item-with-source db media-item-id)]
    (let [conn-config (:media-sources/connection-config row)
          base-url (conn/active-uri (:connections conn-config))
          api-key (:api-key conn-config)
          item-id (or (:media-items/remote-key row) (:remote-key row))]
      (when (and base-url api-key item-id)
        (let [url (str base-url "/Videos/" item-id "/stream?static=true&api_key=" api-key)]
          (log/info "Resolved Jellyfin stream URL" 
                    {:media-item-id media-item-id
                     :remote-key item-id
                     :base-url base-url})
          url)))))

(defn- calculate-start-position
  "Calculates FFmpeg start position in seconds for a playout event.
   Accounts for:
   - Time elapsed since event started
   - In-point offset (chapter trim)
   Returns integer seconds."
  [event now]
  (let [start-at (:playout-events/start-at event)
        in-point (:playout-events/in-point event)
        elapsed-duration (t/duration-between start-at now)
        elapsed-secs (t/duration->seconds elapsed-duration)
        in-point-secs (if in-point (t/duration->seconds in-point) 0)
        total-secs (+ elapsed-secs in-point-secs)]
    (log/debug "Calculated start position"
               {:elapsed-secs elapsed-secs
                :in-point-secs in-point-secs
                :total-secs total-secs
                :event-id (:playout-events/id event)})
    (max 0 total-secs)))

(defn- get-fallback-stream-source
  "Returns fallback stream source when no current event is available.
   Uses channel's fallback_filler_id if configured, otherwise returns error."
  [db channel]
  (if-let [filler-id (:channels/fallback-filler-id channel)]
    (if-let [url (get-jellyfin-stream-url db filler-id)]
      {:source-url url
       :start-position 0
       :type :fallback-filler
       :media-item-id filler-id}
      (do
        (log/error "Fallback filler media item not found or inaccessible"
                   {:channel-id (:channels/id channel)
                    :filler-id filler-id})
        {:error "Fallback content unavailable"
         :status 503}))
    (do
      (log/warn "No content available for channel and no fallback filler configured"
                {:channel-id (:channels/id channel)
                 :channel-name (:channels/name channel)})
      {:error "No content available"
       :status 503})))

(defn- get-current-stream-source
  "Determines what should be streaming for a channel right now.
   
   Returns map with:
   - :source-url - The media URL to stream
   - :start-position - Seek position in seconds
   - :event - The current event (if any)
   - :type - :current-event, :fallback-filler, or :upcoming-wait
   
   Or returns error map with :error and :status if no content available."
  [db channel]
  (let [channel-id (:channels/id channel)
        playout (db-playouts/get-playout-for-channel db channel-id)]
    
    (if-not playout
      (do
        (log/warn "No playout configured for channel" {:channel-id channel-id})
        (get-fallback-stream-source db channel))
      
      (let [now (t/now)
            current-event (db-playouts/get-current-event db (:playouts/id playout) now)]
        
        (if current-event
          ;; We have a current event - stream it!
          (let [media-item-id (:playout-events/media-item-id current-event)
                source-url (get-jellyfin-stream-url db media-item-id)
                start-pos (calculate-start-position current-event now)]
            (if source-url
              {:source-url source-url
               :start-position start-pos
               :event current-event
               :type :current-event
               :media-item-id media-item-id}
              (do
                (log/error "Failed to resolve stream URL for current event"
                           {:event-id (:playout-events/id current-event)
                            :media-item-id media-item-id})
                (get-fallback-stream-source db channel))))
          
          ;; No current event - check for upcoming events or use fallback
          (let [upcoming (first (db-playouts/get-upcoming-events db (:playouts/id playout) now 1))]
            (if upcoming
              (do
                (log/info "No current event, but upcoming event found - using fallback until it starts"
                          {:channel-id channel-id
                           :next-event-at (:playout-events/start-at upcoming)})
                (get-fallback-stream-source db channel))
              (do
                (log/warn "No current or upcoming events in playout"
                          {:channel-id channel-id})
                (get-fallback-stream-source db channel)))))))))

(defn- ensure-stream-dir
  "Creates and returns the absolute path to the stream directory for a channel."
  [channel-uuid]
  (let [dir (io/file "/tmp/pseudovision/streams" (str channel-uuid))]
    (.mkdirs dir)
    (.getAbsolutePath dir)))

(defn- rewrite-playlist-urls
  "Rewrites segment URLs in HLS playlist to use HTTP endpoints.
   
   FFmpeg generates URLs like 'segment-000.ts', but we need '/stream/{uuid}/segment-000.ts'"
  [playlist-content channel-uuid]
  (clojure.string/replace playlist-content
                         #"segment-(\d+)\.ts"
                         (str "/stream/" channel-uuid "/segment-$1.ts")))

(defn- get-or-start-stream
  "Gets existing stream or starts a new FFmpeg process for the channel.
   
   Returns stream info map: {:process, :pid, :output-dir, :channel-uuid, :last-access}"
  [db channel]
  (let [uuid (:channels/uuid channel)]
    (if-let [stream (get @active-streams uuid)]
      ;; Stream exists, update last access
      (do
        (log/debug "Reusing existing stream" {:uuid uuid :pid (:pid stream)})
        (swap! active-streams assoc-in [uuid :last-access] (System/currentTimeMillis))
        stream)
      ;; Start new stream
      (let [output-dir (ensure-stream-dir uuid)
            ;; Get actual playout event and source URL
            source-info (get-current-stream-source db channel)]
        
        ;; Check if we got an error (no content available)
        (if (:error source-info)
          (throw (ex-info (:error source-info) 
                         {:status (:status source-info)
                          :channel-id (:channels/id channel)}))
          
          (let [source-url (:source-url source-info)
                start-pos (:start-position source-info)]
            (log/info "Preparing to start FFmpeg" 
                      {:source-url source-url 
                       :start-position start-pos
                       :source-type (:type source-info)})
            (let [command (hls/build-hls-command source-url output-dir {:start-position-secs start-pos})
                  stream-info (hls/start-ffmpeg command output-dir)]
              (log/info "Started new FFmpeg stream" 
                        {:uuid uuid 
                         :channel-name (:channels/name channel)
                         :pid (:pid stream-info)
                         :output-dir output-dir
                         :source-type (:type source-info)
                         :media-item-id (:media-item-id source-info)
                         :start-position start-pos})
              (swap! active-streams assoc uuid 
                     (assoc stream-info
                            :channel-uuid uuid
                            :last-access (System/currentTimeMillis)
                            :source-info source-info))
              stream-info))))))) ; Close inner let, inner let, if, outer let, defn

;; ---------------------------------------------------------------------------
;; HTTP Handlers
;; ---------------------------------------------------------------------------

(defn stream-handler
  "Returns an HLS playlist for the given channel UUID.
   
   Starts FFmpeg if not already running, then serves the generated playlist."
  [{:keys [db]}]
  (fn [req]
    (let [uuid (get-in req [:path-params :uuid])]
      (log/info "Stream request for channel" {:uuid uuid})
      
      (if-let [channel (db-channels/get-channel-by-uuid db uuid)]
        (try
          (let [stream (get-or-start-stream db channel)
                playlist-path (str (:output-dir stream) "/playlist.m3u8")]
            
            ;; Wait briefly for FFmpeg to create playlist
            (Thread/sleep 1000)
            
            ;; Check if FFmpeg process is still alive
            (let [process-alive? (hls/process-alive? stream)]
              (if-not process-alive?
                (let [log-path (str (:output-dir stream) "/ffmpeg.log")
                      log-file (io/file log-path)
                      error-msg (if (.exists log-file)
                                 (try
                                   (let [log-content (slurp log-file)
                                         ;; Get last 500 chars of log for error context
                                         error-excerpt (if (> (count log-content) 500)
                                                        (subs log-content (- (count log-content) 500))
                                                        log-content)]
                                     error-excerpt)
                                   (catch Exception _ "Could not read FFmpeg log"))
                                 "No log file found")]
                  (log/error "FFmpeg process died shortly after starting" 
                            {:uuid uuid 
                             :pid (:pid stream)
                             :log-excerpt error-msg})
                  ;; Remove dead stream from active streams
                  (swap! active-streams dissoc uuid)
                  {:status 500
                   :headers {"Content-Type" "application/json"}
                   :body {:error "FFmpeg process failed to start"
                          :details error-msg
                          :log-path log-path}})
                
                ;; Process is alive, check for playlist
                (if (.exists (io/file playlist-path))
                  (do
                    (log/debug "Serving playlist" {:uuid uuid :path playlist-path})
                    (let [playlist-content (slurp playlist-path)
                          rewritten-playlist (rewrite-playlist-urls playlist-content uuid)]
                      {:status 200
                       :headers {"Content-Type" "application/vnd.apple.mpegurl"
                                "Cache-Control" "no-cache"}
                       :body rewritten-playlist}))
                  (do
                    (log/warn "Playlist not ready yet (FFmpeg still starting)" 
                             {:uuid uuid 
                              :path playlist-path 
                              :ffmpeg-pid (:pid stream)
                              :ffmpeg-alive true})
                    {:status 503
                     :headers {"Retry-After" "2"}
                     :body {:error "Stream starting, please retry"}})))))
          
          (catch Exception e
            (log/error e "Failed to start stream" {:uuid uuid})
            {:status 500
             :body {:error "Failed to start stream"
                    :message (.getMessage e)}}))
        
        ;; Channel not found
        (do
          (log/warn "Channel not found" {:uuid uuid})
          {:status 404
           :body {:error "Channel not found"
                  :uuid uuid}})))))

(defn segment-handler
  "Serves HLS segment files (.ts) for active streams."
  [{:keys [db]}]
  (fn [req]
    (let [uuid-str (get-in req [:path-params :uuid])
          uuid (java.util.UUID/fromString uuid-str)
          segment-name (get-in req [:path-params :segment])]
      (log/debug "Segment request" {:uuid uuid :segment segment-name})
      
      (if-let [stream (get @active-streams uuid)]
        (let [segment-path (str (:output-dir stream) "/" segment-name)
              file (io/file segment-path)]
          (log/debug "Checking segment" {:uuid uuid :segment segment-name :path segment-path :exists (.exists file) :output-dir (:output-dir stream)})
          (if (.exists file)
            (do
              (log/debug "Serving segment" {:uuid uuid :segment segment-name})
              {:status 200
               :headers {"Content-Type" "video/MP2T"
                        "Cache-Control" "public, max-age=31536000"}  ; Cache segments
               :body (io/input-stream segment-path)})
            (do
              (log/warn "Segment not found" {:uuid uuid :segment segment-name :path segment-path})
              {:status 404
               :body {:error "Segment not found"}})))
        (do
          (log/warn "Stream not active for segment request" {:uuid uuid})
          {:status 404
           :body {:error "Stream not active"}})))))

(defn stream-debug-handler
  "Returns debug information and FFmpeg logs for a stream."
  [_ctx]
  (fn [req]
    (let [uuid-str (get-in req [:path-params :uuid])
          uuid (java.util.UUID/fromString uuid-str)]
      (log/debug "Stream debug request" {:uuid uuid})
      
      (if-let [stream (get @active-streams uuid)]
        (let [log-path (str (:output-dir stream) "/ffmpeg.log")
              log-file (io/file log-path)
              process-alive? (hls/process-alive? stream)
              log-content (if (.exists log-file)
                           (try (slurp log-file)
                                (catch Exception e (str "Error reading log: " (.getMessage e))))
                           "No log file found")]
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body {:uuid uuid
                  :pid (:pid stream)
                  :output-dir (:output-dir stream)
                  :process-alive process-alive?
                  :last-access (:last-access stream)
                  :source-info (:source-info stream)
                  :ffmpeg-log log-content}})
        {:status 404
         :body {:error "Stream not active or not found"
                :uuid uuid
                :active-streams (keys @active-streams)}}))))
;; Updated Mon Apr 13 10:07:12 AM PDT 2026
