(ns pseudovision.ffmpeg.hls
  (:require [clojure.string :as str]
            [pseudovision.ffmpeg.profile :as profile]
            [taoensso.timbre :as log])
  (:import [java.lang ProcessBuilder]
           [java.io File]))

(defn- resolve-ffmpeg-path []
  (let [configured (System/getenv "FFMPEG_PATH")]
    (or
     ;; Only trust FFMPEG_PATH if it actually points at a real file.  A
     ;; baked-in absolute path (e.g. a /nix/store path that isn't present
     ;; in the running container) would otherwise be handed straight to
     ;; ProcessBuilder, which execs it verbatim and fails with ENOENT
     ;; without ever consulting PATH.
     (when (and (not (str/blank? configured))
                (.exists (File. ^String configured)))
       configured)
     (first (filter #(.exists (File. ^String %))
                    ["/usr/bin/ffmpeg"
                     "/usr/local/bin/ffmpeg"]))
     ;; Bare command name: ProcessBuilder/exec resolves this against PATH,
     ;; so as long as ffmpeg is on PATH this works regardless of where it
     ;; lives.
     "ffmpeg")))

(defn build-hls-command
  "Builds an FFmpeg command array for HLS streaming.

   Args:
   - source-url: Input media URL (e.g., Jellyfin stream)
   - output-dir: Directory for HLS segments
   - opts: {:start-position-secs 0
            :segment-duration <override, else profile :hls>
            :playlist-size     <override, else profile :hls>
            :profile-config <raw ffmpeg_profiles.config — legacy flat or nested;
                             see pseudovision.ffmpeg.profile>}

   The encoder/decoder/filter flags are derived from the profile via
   `pseudovision.ffmpeg.profile`, which selects the software / NVENC / VAAPI
   backend (downgrading to software when the requested one is unavailable).

   Returns: String array for ProcessBuilder"
  [source-url output-dir {:keys [start-position-secs segment-duration playlist-size profile-config]
                          :or   {start-position-secs 0}}]
  (let [ffmpeg-bin (resolve-ffmpeg-path)
        cfg        (profile/resolve-config (or profile-config {}))
        seg-dur    (or segment-duration (get-in cfg [:hls :segment-duration]))
        list-size  (or playlist-size (get-in cfg [:hls :playlist-size]))
        vf         (profile/video-filter cfg)]
    (log/debug "Using ffmpeg" {:path ffmpeg-bin :accel (:accel cfg)})
    (into-array String
      (-> [ffmpeg-bin
           ;; Read the input at its native frame rate.  This is a live channel:
           ;; without -re, FFmpeg transcodes the whole remaining file as fast as
           ;; the CPU allows, races past the player, deletes segments it just
           ;; wrote (delete_segments + hls_list_size), and then exits at EOF —
           ;; which the player sees as 404s on segments followed by the stream
           ;; dying.  -re paces output to real time so segments stay available.
           "-re"]
          (into (profile/input-args cfg))          ; -hwaccel … (before -i)
          (into ["-ss" (str start-position-secs)   ; Start position
                 "-i" source-url])                 ; Input URL
          (cond-> vf (into ["-vf" vf]))            ; Normalisation filter chain
          (into (profile/video-encode-args cfg))   ; Video codec + rate control
          (into (profile/audio-encode-args cfg))   ; Audio codec + params
          (into ["-f" "hls"                         ; HLS format
                 "-hls_time" (str seg-dur)          ; Segment duration
                 "-hls_list_size" (str list-size)   ; Segments in playlist
                 "-hls_flags" "delete_segments"     ; Auto-cleanup old segments
                 "-hls_segment_filename" (str output-dir "/segment-%03d.ts")
                 (str output-dir "/playlist.m3u8")])))))

(defn start-ffmpeg
  "Starts an FFmpeg process using ProcessBuilder.
   
   Returns: {:process Process, :pid long, :output-dir String}"
  [command output-dir]
  (let [pb (ProcessBuilder. command)
        env (.environment pb)
        ;; The command's first element is the resolved ffmpeg binary (see
        ;; resolve-ffmpeg-path).  Base the Nix library-path setup on what we
        ;; actually launch, not on the raw env var, which may have been
        ;; rejected in favour of a PATH lookup.
        ffmpeg-path (first command)
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

(defn build-slate-command
  "Builds an FFmpeg command to generate a fallback slate with channel info and upcoming content.
   
   Args:
   - output-dir: Directory for HLS segments
   - opts: {:channel-name \"Movie Channel\"
            :channel-number 5
            :upcoming-events [{:title \"The Matrix\" :start-time \"8:00 PM\"}]
            :segment-duration 6
            :playlist-size 10
            :profile-config {...}}
   
   Returns: String array for ProcessBuilder"
  [output-dir {:keys [channel-name channel-number upcoming-events segment-duration playlist-size profile-config]
               :or {segment-duration 2
                    playlist-size 10
                    profile-config {}
                    upcoming-events []}}]
  (let [ffmpeg-bin (resolve-ffmpeg-path)
        ;; The slate is a synthetic lavfi source rendered with -filter_complex
        ;; drawtext, so it cannot share the input -vf normalisation path.  A
        ;; static 1080p30 screen is trivial to encode, so we always render it in
        ;; software (forcing :accel :none) and reuse only the codec/bitrate
        ;; selection from the profile.  Hardware slate encoding can come later.
        cfg (assoc (profile/resolve-config (or profile-config {})) :accel :none)

        channel-text (if channel-number
                      (format "Channel %s: %s" channel-number channel-name)
                      channel-name)
        
        ;; Escape special characters for FFmpeg drawtext filter
        ;; FFmpeg drawtext requires escaping: \ ' : % (and newlines as \n)
        ;; Also need to escape = when not using quotes
        escape-text (fn [text]
                     (-> text
                         (str/replace "\\" "\\\\\\\\")  ; Escape backslash (4 backslashes)
                         (str/replace ":" "\\\\:")       ; Escape colon
                         (str/replace "'" "\\\\'")       ; Escape single quote
                         (str/replace "%" "\\\\%")))     ; Escape percent
        
        channel-text-escaped (escape-text channel-text)
        
        ;; Build upcoming events text  
        upcoming-text-raw (if (seq upcoming-events)
                            (let [events-str (str/join "\\n" 
                                               (map #(format "%s - %s" 
                                                            (:start-time %)
                                                            (:title %))
                                                    (take 3 upcoming-events)))]
                              (str "Coming Up\\n\\n" events-str))
                            "No upcoming content scheduled")
        upcoming-text-escaped (escape-text upcoming-text-raw)
        
        ;; Build drawtext filter (without quotes around text values)
        ;; Position channel name at top center
        ;; Position "Coming Up" section in middle
        filter-str (str "drawtext="
                       "text=" channel-text-escaped ":"
                       "fontsize=56:"
                       "fontcolor=white:"
                       "x=(w-text_w)/2:"
                       "y=h/6:"
                       "borderw=2:"
                       "bordercolor=black,"
                       "drawtext="
                       "text=" upcoming-text-escaped ":"
                       "fontsize=36:"
                       "fontcolor=white:"
                       "x=(w-text_w)/2:"
                       "y=h/2-text_h/2:"
                       "borderw=2:"
                       "bordercolor=black")]
    
    (log/debug "Building fallback slate" {:channel-name channel-name 
                                          :channel-number channel-number
                                          :upcoming-count (count upcoming-events)})
    
    (into-array String
      (-> [ffmpeg-bin
           ;; Pace both synthetic sources to real time.  Without -re the lavfi
           ;; color/anullsrc generators run flat-out, pinning a CPU and racing
           ;; segment numbers far ahead of the player (same failure mode as a
           ;; real input without -re).
           ;; Generate solid color background (dark blue/gray)
           "-re"
           "-f" "lavfi"
           "-i" "color=c=#1a1a2e:s=1920x1080:r=30"
           ;; Generate silent audio
           "-re"
           "-f" "lavfi"
           "-i" "anullsrc=channel_layout=stereo:sample_rate=48000"
           ;; Apply text overlays
           "-filter_complex" filter-str]
          ;; Video encoding (software; codec/bitrate/preset from the profile)
          (into (profile/video-encode-args cfg))
          (into ["-pix_fmt" "yuv420p"])           ; Ensure compatibility
          ;; Audio encoding
          (into (profile/audio-encode-args cfg))
          ;; HLS output settings
          (into ["-f" "hls"
                 "-hls_time" (str segment-duration)
                 "-hls_list_size" (str playlist-size)
                 "-hls_flags" "delete_segments"
                 "-hls_segment_filename" (str output-dir "/segment-%03d.ts")
                 (str output-dir "/playlist.m3u8")])))))
