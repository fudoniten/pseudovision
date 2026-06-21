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
      :hls       {:segment-duration 2 :playlist-size 10 :warm-segments 2 :initial-burst 10}}
     ```

   `:video :codec` is a *logical* name (`h264` / `hevc`) mapped to the right
   encoder per backend; a concrete encoder name (e.g. `libx264`, `copy`) is
   passed through verbatim. JSONB round-trips keys as kebab-case keywords and
   values as strings, so both keyword and string values are tolerated."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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

(defn- render-node-drivers
  "DRM driver backing each present /dev/dri/renderD* node, e.g. \"i915\",
   \"amdgpu\", \"nvidia\". A node whose driver can't be read yields \"unknown\"
   so it is still treated as potentially VAAPI-capable."
  []
  (keep (fn [n]
          (when (file-exists? (str "/dev/dri/renderD" n))
            (let [ue (io/file (str "/sys/class/drm/renderD" n "/device/uevent"))]
              (or (when (.exists ue)
                    (some #(second (re-find #"^DRIVER=(.+)$" (str/trim %)))
                          (str/split-lines (slurp ue))))
                  "unknown"))))
        (range 128 144)))

(defn- accels-from
  "Pure decision: given the DRM render-node drivers and whether an NVIDIA device
   node is present, which accel backends are available.

   VAAPI requires a render node that is NOT nvidia-backed (iHD/AMD VAAPI cannot
   drive an NVIDIA node — a render node alone is not enough, which is the bug
   that made an NVIDIA-only host advertise VAAPI). NVENC requires an actual
   /dev/nvidia* device (the NVIDIA DRM render node alone, without the injected
   userspace driver, cannot encode)."
  [render-drivers nvidia-device?]
  (cond-> #{:none}
    (some #(not= % "nvidia") render-drivers) (conj :vaapi)
    nvidia-device?                            (conj :nvenc)))

(defn detect-accels
  "Probes the host for available hardware-acceleration backends. Returns a set
   always containing :none plus any of :nvenc / :vaapi actually usable here."
  []
  (accels-from (render-node-drivers) (file-exists? "/dev/nvidia0")))

(def available-accels
  "Memoised set of accel backends available on this host. Detection is a cheap
   filesystem probe; memoised so it runs once per process."
  (memoize detect-accels))

;; ---------------------------------------------------------------------------
;; Config normalisation
;; ---------------------------------------------------------------------------

(def ^:private defaults
  {:accel  :none
   :decode :software
   :device default-vaapi-device
   :video  {:codec "h264" :bitrate "2000k" :rate-control :vbr}
   :audio  {:codec "aac" :bitrate "128k" :sample-rate 48000 :channels 2}
   :normalize nil
   :hls    {:segment-duration 2 :playlist-size 10 :warm-segments 2 :initial-burst 10}})

(defn- fold-legacy
  "Folds any legacy flat encoder keys ({:video-codec ...}) into the nested shape
   WITHOUT overriding nested or :accel settings already present. This makes a
   config that mixes an explicit :accel with legacy keys safe: the legacy keys no
   longer force software encoding. A purely-legacy config (no :accel) still
   resolves to software because :accel simply defaults to :none."
  [config]
  (let [lv (cond-> {}
             (:video-codec config)   (assoc :codec   (:video-codec config))
             (:video-bitrate config) (assoc :bitrate (:video-bitrate config))
             (:preset config)        (assoc :preset  (:preset config)))
        la (cond-> {}
             (:audio-codec config)   (assoc :codec   (:audio-codec config))
             (:audio-bitrate config) (assoc :bitrate (:audio-bitrate config)))]
    (-> config
        (dissoc :video-codec :audio-codec :preset :video-bitrate :audio-bitrate)
        (update :video #(merge lv %))
        (update :audio #(merge la %)))))

(defn resolve-config
  "Normalises a raw `ffmpeg_profiles.config` map (legacy flat or nested) into the
   canonical internal shape, filling defaults and downgrading the accel backend
   to :none with a warning when the requested backend is unavailable on this
   host.

   The single-arg arity uses the memoised host detection; the two-arg arity
   takes an explicit `available` set (used by tests)."
  ([config] (resolve-config config (available-accels)))
  ([config available]
   (let [raw      (fold-legacy (or config {}))
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
     (assoc merged :accel accel :decode (keyword (:decode merged))))))

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
  "Decode / device-setup flags that must appear BEFORE `-i`.

   `:hardware` decode runs the decoder on the GPU (fast, but the input codec must
   be GPU-decodable). `:software` decode (the default) decodes on the CPU and only
   uploads frames to the GPU for encoding — robust to ANY input codec (e.g. 10-bit
   HEVC, odd formats) at the cost of CPU decode. See `video-filter` for the
   upload step."
  [{:keys [accel decode device]}]
  (let [dev (or device default-vaapi-device)]
    (case [accel decode]
      [:vaapi :hardware] ["-hwaccel" "vaapi" "-hwaccel_device" dev "-hwaccel_output_format" "vaapi"]
      [:vaapi :software] ["-vaapi_device" dev]
      [:nvenc :hardware] ["-hwaccel" "cuda" "-hwaccel_output_format" "cuda"]
      [:nvenc :software] ["-init_hw_device" "cuda=cu" "-filter_hw_device" "cu"]
      [])))

(defn video-filter
  "Returns the `-vf` filter string for the resolved backend + decode mode, or nil
   when no filtering is needed.

   For a hardware ENCODER fed by SOFTWARE decode, frames live in system memory
   and must be converted to nv12 and uploaded to the GPU
   (`format=nv12,hwupload[_cuda]`). That upload is emitted even without a
   `:normalize` block — it is what makes any input codec encodable — and any
   scale/pad happens on the CPU before the upload. For HARDWARE decode the frames
   are already GPU surfaces, so only a GPU scale is added when normalizing.
   Software encode (`:none`) does a full CPU scale/pad/format."
  [{:keys [accel decode normalize]}]
  (let [{:keys [width height fps pixfmt sar]
         :or   {pixfmt "yuv420p" sar "1:1"}} normalize
        cpu-scale (when normalize
                    (cond-> []
                      fps  (conj (str "fps=" fps))
                      true (conj (str "scale=" width ":" height
                                      ":force_original_aspect_ratio=decrease"))
                      true (conj (str "pad=" width ":" height ":(ow-iw)/2:(oh-ih)/2"))
                      true (conj (str "setsar=" sar))))
        join      (fn [parts] (when (seq parts) (str/join "," parts)))]
    (case [accel decode]
      [:vaapi :software] (join (concat cpu-scale ["format=nv12" "hwupload"]))
      [:nvenc :software] (join (concat cpu-scale ["format=nv12" "hwupload_cuda"]))
      [:vaapi :hardware] (when normalize
                           (join (cond-> []
                                   fps  (conj (str "fps=" fps))
                                   true (conj (str "scale_vaapi=w=" width ":h=" height)))))
      [:nvenc :hardware] (when normalize
                           (join (cond-> []
                                   fps  (conj (str "fps=" fps))
                                   true (conj (str "scale_cuda=w=" width ":h=" height)))))
      ;; software encode (:none) — full CPU normalize, or nothing
      (when normalize (join (conj cpu-scale (str "format=" pixfmt)))))))

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
