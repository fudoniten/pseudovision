(ns pseudovision.ffmpeg.profile
  "FFmpeg encoding-profile model.

   Maps a logical encoding profile (stored in `ffmpeg_profiles.config`) to
   concrete FFmpeg argument vectors for the configured hardware-acceleration
   backend: software (`:none`), NVIDIA NVENC (`:nvenc`), or VAAPI (`:vaapi`).

   Two `config` shapes are accepted and both normalise to one canonical map via
   `resolve-config`:

   - **legacy flat** — `{:video-codec :audio-codec :preset :video-bitrate
     :audio-bitrate}` (the original shape; always software).
   - **nested (preferred)** —
     ```
     {:accel     :none            ; :none | :nvenc | :vaapi (or the strings)
      :device    \"/dev/dri/renderD128\"
      :video     {:codec \"h264\" :bitrate \"4000k\" :rate-control :vbr :preset \"p4\"}
      :audio     {:codec \"aac\" :bitrate \"192k\" :sample-rate 48000 :channels 2}
      :normalize {:width 1920 :height 1080 :fps 30 :pixfmt \"yuv420p\" :sar \"1:1\"}
      :hls       {:segment-duration 2 :playlist-size 10 :warm-segments 2}}
     ```

   `:video :codec` is a *logical* name (`h264` / `hevc`) mapped to the right
   encoder per backend; a concrete encoder name (e.g. `libx264`, `copy`) is
   passed through verbatim. JSONB round-trips keys as kebab-case keywords and
   values as strings, so both keyword and string values are tolerated."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.io File]))

(def ^:private default-vaapi-device "/dev/dri/renderD128")

(def ^:private video-codec-map
  "Logical codec name -> per-accel FFmpeg encoder."
  {"h264" {:none "libx264" :nvenc "h264_nvenc" :vaapi "h264_vaapi"}
   "hevc" {:none "libx265" :nvenc "hevc_nvenc" :vaapi "hevc_vaapi"}
   "h265" {:none "libx265" :nvenc "hevc_nvenc" :vaapi "hevc_vaapi"}})

(def ^:private known-encoders
  "Concrete encoder names we recognise (so they pass through verbatim)."
  (into #{"copy"} (mapcat vals (vals video-codec-map))))

;; ---------------------------------------------------------------------------
;; Hardware-acceleration detection
;; ---------------------------------------------------------------------------

(defn- file-exists? [^String path]
  (.exists (File. path)))

(defn detect-accels
  "Probes the host for available hardware-acceleration backends.
   Returns a set always containing :none plus any of :nvenc / :vaapi detected
   from the presence of their device nodes."
  []
  (cond-> #{:none}
    (file-exists? "/dev/nvidia0")
    (conj :nvenc)
    (some file-exists? ["/dev/dri/renderD128" "/dev/dri/renderD129"])
    (conj :vaapi)))

(def available-accels
  "Memoised set of accel backends available on this host. Detection is a cheap
   filesystem probe; memoised so it runs once per process."
  (memoize detect-accels))

;; ---------------------------------------------------------------------------
;; Config normalisation
;; ---------------------------------------------------------------------------

(def ^:private defaults
  {:accel  :none
   :device default-vaapi-device
   :video  {:codec "h264" :bitrate "2000k" :rate-control :vbr}
   :audio  {:codec "aac" :bitrate "128k" :sample-rate 48000 :channels 2}
   :normalize nil
   :hls    {:segment-duration 2 :playlist-size 10 :warm-segments 2}})

(defn- legacy?
  "True when `config` uses the original flat encoder keys."
  [config]
  (boolean (some #(contains? config %)
                 [:video-codec :audio-codec :video-bitrate :audio-bitrate])))

(defn- from-legacy
  "Translates a flat legacy config into the nested shape (always software)."
  [{:keys [video-codec audio-codec preset video-bitrate audio-bitrate]}]
  (cond-> {:accel :none
           :video (cond-> {}
                    video-codec   (assoc :codec video-codec)
                    video-bitrate (assoc :bitrate video-bitrate)
                    preset        (assoc :preset preset))
           :audio (cond-> {}
                    audio-codec   (assoc :codec audio-codec)
                    audio-bitrate (assoc :bitrate audio-bitrate))}))

(defn resolve-config
  "Normalises a raw `ffmpeg_profiles.config` map (legacy flat or nested) into the
   canonical internal shape, filling defaults and downgrading the accel backend
   to :none with a warning when the requested backend is unavailable on this
   host.

   The single-arg arity uses the memoised host detection; the two-arg arity
   takes an explicit `available` set (used by tests)."
  ([config] (resolve-config config (available-accels)))
  ([config available]
   (let [raw      (if (legacy? (or config {})) (from-legacy config) (or config {}))
         merged   (-> defaults
                      (merge (dissoc raw :video :audio :normalize :hls))
                      (update :video merge (:video raw))
                      (update :audio merge (:audio raw))
                      (assoc  :normalize (get raw :normalize (:normalize defaults)))
                      (update :hls merge (:hls raw)))
         requested (keyword (:accel merged))
         accel     (if (contains? available requested)
                     requested
                     (do (when (not= requested :none)
                           (log/warn "Requested FFmpeg accel unavailable; using software encoding"
                                     {:requested requested :available available}))
                         :none))]
     (assoc merged :accel accel))))

;; ---------------------------------------------------------------------------
;; Argument builders (operate on a resolved config)
;; ---------------------------------------------------------------------------

(defn- resolve-encoder
  "Resolves a logical or concrete video codec to a concrete encoder name."
  [codec accel]
  (cond
    (known-encoders codec)      codec
    (video-codec-map codec)     (get-in video-codec-map [codec accel])
    :else                       codec))

(defn input-args
  "Decode / hwaccel flags that must appear BEFORE `-i`."
  [{:keys [accel device]}]
  (case accel
    :nvenc ["-hwaccel" "cuda" "-hwaccel_output_format" "cuda"]
    :vaapi ["-hwaccel" "vaapi" "-hwaccel_device" (or device default-vaapi-device)
            "-hwaccel_output_format" "vaapi"]
    []))

(defn video-filter
  "Returns the `-vf` filter string that normalises output geometry for the given
   backend, or nil when no `:normalize` block is configured.

   Software does a full aspect-preserving scale + pad + format; the hardware
   paths scale on-GPU (padding on-GPU is deferred to the normalization-hardening
   phase)."
  [{:keys [accel normalize]}]
  (when normalize
    (let [{:keys [width height fps pixfmt sar]
           :or   {pixfmt "yuv420p" sar "1:1"}} normalize]
      (str/join ","
        (case accel
          :vaapi (cond-> []
                   fps (conj (str "fps=" fps))
                   true (conj (str "scale_vaapi=w=" width ":h=" height)))
          :nvenc (cond-> []
                   fps (conj (str "fps=" fps))
                   true (conj (str "scale_cuda=w=" width ":h=" height)))
          (cond-> []
            fps  (conj (str "fps=" fps))
            true (conj (str "scale=" width ":" height
                            ":force_original_aspect_ratio=decrease"))
            true (conj (str "pad=" width ":" height ":(ow-iw)/2:(oh-ih)/2"))
            true (conj (str "setsar=" sar))
            true (conj (str "format=" pixfmt))))))))

(defn- default-preset
  "Per-backend default encoder preset when none is configured."
  [accel]
  (case accel
    :none  "veryfast"
    :nvenc "p4"
    nil))            ; vaapi has no -preset concept

(defn video-encode-args
  "Video encoder selection + rate control for the resolved backend."
  [{:keys [accel video]}]
  (let [{:keys [codec bitrate rate-control preset]} video
        enc    (resolve-encoder codec accel)
        preset (or preset (default-preset accel))
        rc     (when rate-control (name rate-control))]
    (if (= enc "copy")
      ["-c:v" "copy"]
      (into ["-c:v" enc]
            (case accel
              :vaapi (cond-> []
                       rc      (into ["-rc_mode" (str/upper-case rc)])
                       bitrate (into ["-b:v" bitrate]))
              :nvenc (cond-> []
                       preset  (into ["-preset" preset])
                       rc      (into ["-rc" rc])
                       bitrate (into ["-b:v" bitrate]))
              ;; software / libx264
              (cond-> []
                preset  (into ["-preset" preset])
                bitrate (into ["-b:v" bitrate])))))))

(defn audio-encode-args
  "Audio encoder selection and stream parameters."
  [{:keys [audio]}]
  (let [{:keys [codec bitrate sample-rate channels]} audio
        codec (or codec "aac")]
    (if (= codec "copy")
      ["-c:a" "copy"]
      (cond-> ["-c:a" codec]
        bitrate     (into ["-b:a" bitrate])
        sample-rate (into ["-ar" (str sample-rate)])
        channels    (into ["-ac" (str channels)])))))
