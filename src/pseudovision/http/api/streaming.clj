(ns pseudovision.http.api.streaming
  "Live channel streaming endpoint.
   Returns HLS playlists and segments for /stream/{uuid}."
  (:require [pseudovision.db.channels :as db-channels]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Walking Skeleton - Minimal Implementation
;; ---------------------------------------------------------------------------

(defn stream-handler
  "Returns an HLS master playlist for the given channel UUID.
   
   For now, returns a minimal hardcoded playlist to validate the routing.
   TODO: Implement actual FFmpeg streaming."
  [{:keys [db]}]
  (fn [req]
    (let [uuid (get-in req [:path-params :uuid])]
      (log/info "Stream request for channel" {:uuid uuid})
      
      ;; Verify channel exists
      (if-let [channel (db-channels/get-channel-by-uuid db uuid)]
        (do
          (log/info "Channel found" {:channel-name (:channels/name channel)})
          
          ;; Return minimal HLS playlist (walking skeleton)
          {:status  200
           :headers {"Content-Type" "application/vnd.apple.mpegurl"
                     "Cache-Control" "no-cache"}
           :body    (str "#EXTM3U\n"
                         "#EXT-X-VERSION:3\n"
                         "#EXT-X-TARGETDURATION:6\n"
                         "#EXT-X-MEDIA-SEQUENCE:0\n"
                         "# TODO: Implement FFmpeg streaming\n"
                         "# Channel: " (:channels/name channel) "\n")})
        
        ;; Channel not found
        (do
          (log/warn "Channel not found" {:uuid uuid})
          {:status 404
           :body {:error "Channel not found"
                  :uuid uuid}})))))
