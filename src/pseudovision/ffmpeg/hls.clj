(ns pseudovision.ffmpeg.hls
  (:require [taoensso.timbre :as log])
  (:import [java.lang ProcessBuilder]
           [java.io File]))

(defn build-hls-command
  "Builds an FFmpeg command array for HLS streaming.
   
   Args:
   - source-url: Input media URL (e.g., Jellyfin stream)
   - output-dir: Directory for HLS segments
   - opts: {:start-position-secs 0
            :segment-duration 6
            :playlist-size 10}
   
   Returns: String array for ProcessBuilder"
  [source-url output-dir {:keys [start-position-secs segment-duration playlist-size]
                          :or {start-position-secs 0
                               segment-duration 6
                               playlist-size 10}}]
   (let [ffmpeg-bin (or (System/getenv "FFMPEG_PATH") 
                        ;; Try to find ffmpeg in common locations
                        (first (filter #(.exists (File. %))
                                      ["/usr/bin/ffmpeg"
                                       "/usr/local/bin/ffmpeg"]))
                        "ffmpeg")]
     (log/debug "Using ffmpeg" {:path ffmpeg-bin})
     (into-array String
       [ffmpeg-bin
        "-ss" (str start-position-secs)           ; Start position
        "-i" source-url                            ; Input URL
        "-c:v" "libx264"                           ; H.264 video
        "-preset" "veryfast"                       ; Fast encoding
        "-b:v" "2000k"                             ; 2 Mbps video bitrate
        "-c:a" "aac"                               ; AAC audio
        "-b:a" "128k"                              ; 128 kbps audio
        "-f" "hls"                                 ; HLS format
        "-hls_time" (str segment-duration)        ; Segment duration
        "-hls_list_size" (str playlist-size)      ; Segments in playlist
        "-hls_flags" "delete_segments"            ; Auto-cleanup old segments
        "-hls_segment_filename" (str output-dir "/segment-%03d.ts")
        (str output-dir "/playlist.m3u8")])))

(defn start-ffmpeg
  "Starts an FFmpeg process using ProcessBuilder.
   
   Returns: {:process Process, :pid long, :output-dir String}"
  [command output-dir]
  (let [pb (ProcessBuilder. command)
        env (.environment pb)
        ffmpeg-path (or (System/getenv "FFMPEG_PATH") "ffmpeg")
        ;; Extract /nix/store path and set up library paths for Nix binaries
        _ (when (.startsWith ffmpeg-path "/nix/store/")
            (let [nix-store-path (second (re-find #"(/nix/store/[^/]+)" ffmpeg-path))
                  lib-path (str nix-store-path "/lib")]
              (log/debug "Setting up Nix library paths" {:nix-store-path nix-store-path})
              (.put env "LD_LIBRARY_PATH" 
                    (str lib-path ":" (or (.get env "LD_LIBRARY_PATH") "")))))
        _  (.directory pb (File. output-dir))
        _  (.redirectErrorStream pb true)      ; Merge stderr to stdout
        process (.start pb)]
    {:process process
     :pid (.pid process)
     :output-dir output-dir}))

(defn stop-ffmpeg
  "Gracefully stops an FFmpeg process."
  [{:keys [process]}]
  (.destroy process)
  ;; Wait up to 5 seconds for graceful shutdown
  (when-not (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)
    ;; Force kill if still alive
    (.destroyForcibly process)))

(defn process-alive?
  "Check if FFmpeg process is still running."
  [{:keys [process]}]
  (.isAlive process))
