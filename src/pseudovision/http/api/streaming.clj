(ns pseudovision.http.api.streaming
  "Live channel streaming endpoint.
   Returns HLS playlists and segments for /stream/{uuid}."
  (:require [pseudovision.db.channels :as db-channels]
            [pseudovision.ffmpeg.hls :as hls]
            [taoensso.timbre :as log]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Stream State Management
;; ---------------------------------------------------------------------------

;; Global state (to be refactored into Integrant component later)
(defonce active-streams (atom {}))

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
            ;; TODO: Get actual playout event and source URL
            ;; For now, use public test stream
            source-url "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            command (hls/build-hls-command source-url output-dir {})
            stream-info (hls/start-ffmpeg command output-dir)]
        (log/info "Started new FFmpeg stream" 
                  {:uuid uuid 
                   :channel-name (:channels/name channel)
                   :pid (:pid stream-info)
                   :output-dir output-dir})
        (swap! active-streams assoc uuid 
               (assoc stream-info
                      :channel-uuid uuid
                      :last-access (System/currentTimeMillis)))
        stream-info))))

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
                (log/warn "Playlist not ready yet" {:uuid uuid :path playlist-path})
                {:status 503
                 :headers {"Retry-After" "2"}
                 :body {:error "Stream starting, please retry"}})))
          
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
    (let [uuid (get-in req [:path-params :uuid])
          segment-name (get-in req [:path-params :segment])]
      (log/debug "Segment request" {:uuid uuid :segment segment-name})
      
      (if-let [stream (get @active-streams uuid)]
        (let [segment-path (str (:output-dir stream) "/" segment-name)]
          (if (.exists (io/file segment-path))
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
