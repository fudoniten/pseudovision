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
            :playlist-size 10
            :profile-config {:video-codec \"libx264\"
                            :audio-codec \"aac\"
                            :preset \"veryfast\"
                            :video-bitrate \"2000k\"
                            :audio-bitrate \"128k\"}}
   
   Returns: String array for ProcessBuilder"
  [source-url output-dir {:keys [start-position-secs segment-duration playlist-size profile-config]
                          :or {start-position-secs 0
                               segment-duration 6
                               playlist-size 10
                               profile-config {}}}]
   (let [ffmpeg-bin (or (System/getenv "FFMPEG_PATH") 
                        ;; Try to find ffmpeg in common locations
                        (first (filter #(.exists (File. %))
                                      ["/usr/bin/ffmpeg"
                                       "/usr/local/bin/ffmpeg"]))
                        "ffmpeg")
         ;; Extract profile settings with defaults
         video-codec (get profile-config :video-codec "libx264")
         audio-codec (get profile-config :audio-codec "aac")
         preset (get profile-config :preset "veryfast")
         video-bitrate (get profile-config :video-bitrate "2000k")
         audio-bitrate (get profile-config :audio-bitrate "128k")]
     (log/debug "Using ffmpeg" {:path ffmpeg-bin :profile profile-config})
     (into-array String
       [ffmpeg-bin
        "-ss" (str start-position-secs)           ; Start position
        "-i" source-url                            ; Input URL
        "-c:v" video-codec                         ; Video codec from profile
        "-preset" preset                           ; Encoding preset from profile
        "-b:v" video-bitrate                       ; Video bitrate from profile
        "-c:a" audio-codec                         ; Audio codec from profile
        "-b:a" audio-bitrate                       ; Audio bitrate from profile
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
        ;; Redirect stderr to a log file for debugging
        stderr-file (File. output-dir "ffmpeg.log")
        _  (.redirectError pb stderr-file)
        _  (.redirectOutput pb stderr-file)
        process (.start pb)]
    (log/info "FFmpeg process started" 
              {:pid (.pid process) 
               :output-dir output-dir
               :log-file (.getAbsolutePath stderr-file)})
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
