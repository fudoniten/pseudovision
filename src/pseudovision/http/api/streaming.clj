(ns pseudovision.http.api.streaming
  "Live channel streaming endpoints (/stream/{uuid}).

   Thin HTTP layer over the Channel Stream Manager: the manager owns the
   per-channel encoder loop and the authoritative HLS playlist (monotonic
   media-sequence + discontinuities across event boundaries). See
   pseudovision.streaming.manager."
  (:require [pseudovision.db.channels :as db-channels]
            [pseudovision.streaming.manager :as mgr]
            [taoensso.timbre :as log]))

;; First-connect latency: a fresh encoder must start FFmpeg and finalize its
;; first segment before the playlist has anything to serve. With software decode
;; (Phase 3-lite) of e.g. HEVC plus the startup burst-fill, that can take several
;; seconds — longer than a client like VLC will tolerate a 503 (it does not retry
;; on 503). So hold the request open until the first segment is ready, up to a
;; generous deadline, and only 503 if the encoder genuinely never produces one.
(def ^:private playlist-wait-attempts 100)   ; 100 × 250ms = 25s ceiling
(def ^:private playlist-wait-ms 250)

(defn- wait-for-playlist
  "Polls the manager until the channel playlist has segments (the encoder has
   produced its first segment), or the deadline passes. Returns the rendered
   playlist or nil."
  [streams uuid]
  (loop [n 0]
    (or (mgr/playlist-content streams uuid)
        (when (< n playlist-wait-attempts)
          (Thread/sleep playlist-wait-ms)
          (recur (inc n))))))

(defn stream-handler
  "Returns the HLS playlist for a channel UUID, starting the manager loop if
   needed."
  [{:keys [db streams]}]
  (fn [req]
    (let [uuid (str (get-in req [:parameters :path :uuid]))]
      (log/info "Stream request for channel" {:uuid uuid})
      (if-let [channel (db-channels/get-channel-by-uuid db uuid)]
        (try
          (mgr/ensure-stream! streams channel)
          (if-let [content (wait-for-playlist streams uuid)]
            {:status  200
             :headers {"Content-Type" "application/vnd.apple.mpegurl"
                       "Cache-Control" "no-cache"}
             :body    content}
            {:status  503
             :headers {"Retry-After" "2"}
             :body    {:error "Stream starting, please retry"}})
          (catch Exception e
            (log/error e "Failed to start stream" {:uuid uuid})
            {:status 500
             :body   {:error "Failed to start stream" :message (.getMessage e)}}))
        (do
          (log/warn "Channel not found" {:uuid uuid})
          {:status 404 :body {:error "Channel not found" :uuid uuid}})))))

(defn segment-handler
  "Serves an HLS segment (.ts) for an active stream via the manager's store."
  [{:keys [streams]}]
  (fn [req]
    (let [uuid    (str (get-in req [:parameters :path :uuid]))
          segment (get-in req [:parameters :path :segment])]
      (log/debug "Segment request" {:uuid uuid :segment segment})
      (if-let [is (mgr/open-segment streams uuid segment)]
        {:status  200
         :headers {"Content-Type" "video/MP2T"
                   "Cache-Control" "public, max-age=31536000"}
         :body    is}
        (do
          (log/warn "Segment not found" {:uuid uuid :segment segment})
          {:status 404 :body {:error "Segment not found"}})))))

(defn stream-debug-handler
  "Returns diagnostic information for a running stream."
  [{:keys [streams]}]
  (fn [req]
    (let [uuid (str (get-in req [:parameters :path :uuid]))]
      (if-let [info (mgr/debug-info streams uuid)]
        {:status 200 :headers {"Content-Type" "application/json"} :body info}
        {:status 404 :body {:error "Stream not active or not found" :uuid uuid}}))))
