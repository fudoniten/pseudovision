(ns pseudovision.http.api.streaming
  "Live channel streaming endpoint.
   Returns HLS playlists and segments for /stream/{uuid}."
  (:require [pseudovision.db.channels :as db-channels]
            [pseudovision.db.playouts :as db-playouts]
            [pseudovision.db.media :as db-media]
            [pseudovision.db.ffmpeg :as db-ffmpeg]
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

(defn- format-time-12h
  "Formats an Instant to 12-hour time like '8:00 PM'"
  [^java.time.Instant inst]
  (let [zdt (.atZone inst (java.time.ZoneId/of "America/Los_Angeles"))
        formatter (java.time.format.DateTimeFormatter/ofPattern "h:mm a")]
    (.format zdt formatter)))

(defn- truncate-title
  "Truncates a title to max-length characters, adding ellipsis if needed."
  [title max-length]
  (if (> (count title) max-length)
    (str (subs title 0 (- max-length 3)) "...")
    title))

(defn- get-upcoming-events-for-slate
  "Gets upcoming events with metadata for displaying on fallback slate."
  [db playout-id]
  (when playout-id
    (let [now (t/now)
          events (db-playouts/get-upcoming-events-with-metadata db playout-id now 5)]
      (map (fn [event]
             (let [raw-title (or (:metadata/title event) "Untitled")
                   ;; Truncate to 50 characters to prevent overflow
                   title (truncate-title raw-title 50)]
               {:title title
                :start-time (format-time-12h (:playout-events/start-at event))}))
           events))))

(defn- get-fallback-stream-source
  "Returns fallback stream source when no current event is available.
   Uses channel's fallback_filler_id if configured, otherwise uses generated slate."
  [db channel playout-id]
  (if-let [filler-id (:channels/fallback-filler-id channel)]
    (if-let [url (get-jellyfin-stream-url db filler-id)]
      {:source-url url
       :start-position 0
       :type :fallback-filler
       :media-item-id filler-id}
      (do
        (log/warn "Fallback filler media item not found or inaccessible - using generated slate"
                   {:channel-id (:channels/id channel)
                    :filler-id filler-id})
        {:type :generated-slate
         :upcoming-events (get-upcoming-events-for-slate db playout-id)}))
    (do
      (log/info "No fallback filler configured - using generated slate"
                {:channel-id (:channels/id channel)
                 :channel-name (:channels/name channel)})
      {:type :generated-slate
       :upcoming-events (get-upcoming-events-for-slate db playout-id)})))

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
        (get-fallback-stream-source db channel nil))
      
      (let [now (t/now)
            playout-id (:playouts/id playout)
            current-event (db-playouts/get-current-event db playout-id now)]
        
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
                (get-fallback-stream-source db channel playout-id))))
          
          ;; No current event - check for upcoming events or use fallback
          (let [upcoming (first (db-playouts/get-upcoming-events db playout-id now 1))]
            (if upcoming
              (do
                (log/info "No current event, but upcoming event found - using fallback until it starts"
                          {:channel-id channel-id
                           :next-event-at (:playout-events/start-at upcoming)})
                (get-fallback-stream-source db channel playout-id))
              (do
                (log/warn "No current or upcoming events in playout"
                          {:channel-id channel-id})
                (get-fallback-stream-source db channel playout-id)))))))))

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

(defn- needs-transition?
  "Checks if a stream needs to transition to a new event.
   Returns true if the current event has ended or will end very soon."
  [stream]
  (when-let [event (get-in stream [:source-info :event])]
    (let [finish-at (:playout-events/finish-at event)
          now (t/now)
          ;; Transition 5 seconds before event ends to allow FFmpeg startup time
          transition-threshold (t/sub-duration finish-at (t/seconds->duration 5))]
      (.isAfter now transition-threshold))))

(defn- stop-and-remove-stream
  "Stops FFmpeg process and removes stream from active-streams."
  [uuid stream]
  (log/info "Stopping stream for transition" 
            {:uuid uuid 
             :pid (:pid stream)})
  (hls/stop-ffmpeg stream)
  (swap! active-streams dissoc uuid))

(defn- start-new-stream
  "Starts a new FFmpeg stream for the channel.
   
   Returns stream info map."
  [db channel uuid]
  (let [output-dir (ensure-stream-dir uuid)
        ;; Get actual playout event and source URL
        source-info (get-current-stream-source db channel)]
    
    ;; Check if we got an error (no content available)
    (if (:error source-info)
      (throw (ex-info (:error source-info) 
                     {:status (:status source-info)
                      :channel-id (:channels/id channel)}))
      
      ;; Load FFmpeg profile from database (used for both slate and regular streams)
      (let [profile-id (:channels/ffmpeg-profile-id channel)
            profile (when profile-id (db-ffmpeg/get-profile db profile-id))
            profile-config (or (:ffmpeg-profiles/config profile) {})]
        
        ;; Check if we're generating a slate or streaming real content
        (if (= (:type source-info) :generated-slate)
          ;; Generate fallback slate with channel info
          (let [upcoming-events (:upcoming-events source-info)]
            (log/info "Starting fallback slate" 
                      {:channel-name (:channels/name channel)
                       :channel-number (:channels/number channel)
                       :upcoming-count (count upcoming-events)})
            (let [command (hls/build-slate-command output-dir
                                                  {:channel-name (:channels/name channel)
                                                   :channel-number (:channels/number channel)
                                                   :upcoming-events upcoming-events
                                                   :profile-config profile-config})
                  stream-info (hls/start-ffmpeg command output-dir)]
              (log/info "Started fallback slate stream" 
                        {:uuid uuid 
                         :channel-name (:channels/name channel)
                         :pid (:pid stream-info)
                         :output-dir output-dir})
              (swap! active-streams assoc uuid 
                     (assoc stream-info
                            :channel-uuid uuid
                            :last-access (System/currentTimeMillis)
                            :source-info source-info))
              stream-info))
          
          ;; Regular content stream
          (let [source-url (:source-url source-info)
                start-pos (:start-position source-info)]
            (log/info "Preparing to start FFmpeg" 
                      {:source-url source-url 
                       :start-position start-pos
                       :source-type (:type source-info)
                       :profile-id profile-id
                       :profile-name (:ffmpeg-profiles/name profile)})
            (let [command (hls/build-hls-command source-url output-dir 
                                                {:start-position-secs start-pos
                                                 :profile-config profile-config})
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
              stream-info)))))))

(defn- get-or-start-stream
  "Gets existing stream or starts a new FFmpeg process for the channel.
   Handles event transitions by detecting when current event has ended.
   
   Returns stream info map: {:process, :pid, :output-dir, :channel-uuid, :last-access}"
  [db channel]
  (let [uuid (:channels/uuid channel)
        existing-stream (get @active-streams uuid)]
    
    ;; Check if we need to transition to a new event
    (when (and existing-stream (needs-transition? existing-stream))
      (log/info "Event transition required" 
                {:uuid uuid 
                 :current-event-id (get-in existing-stream [:source-info :event :playout-events/id])})
      (stop-and-remove-stream uuid existing-stream))
    
    ;; Now either reuse existing stream or start new one
    (if-let [stream (get @active-streams uuid)]
      ;; Stream exists and doesn't need transition - reuse it
      (do
        (log/debug "Reusing existing stream" {:uuid uuid :pid (:pid stream)})
        (swap! active-streams assoc-in [uuid :last-access] (System/currentTimeMillis))
        stream)
      ;; No stream exists - start new one
      (start-new-stream db channel uuid))))

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
