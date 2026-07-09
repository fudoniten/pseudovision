(ns pseudovision.ffmpeg.profile-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.ffmpeg.profile :as profile]
            [pseudovision.ffmpeg.hls :as hls]))

(def ^:private all-accels #{:none :nvenc :vaapi})

;; ---------------------------------------------------------------------------
;; Config normalisation
;; ---------------------------------------------------------------------------

(deftest legacy-config-resolves-to-software
  (testing "the original flat shape maps to software encoding"
    (let [cfg (profile/resolve-config
               {:video-codec "libx264" :audio-codec "aac"
                :preset "veryfast" :video-bitrate "3000k" :audio-bitrate "160k"}
               all-accels)]
      (is (= :none (:accel cfg)))
      (is (= "libx264" (get-in cfg [:video :codec])))
      (is (= "3000k" (get-in cfg [:video :bitrate])))
      (is (= "veryfast" (get-in cfg [:video :preset])))
      (is (= "aac" (get-in cfg [:audio :codec])))
      (is (nil? (:normalize cfg)) "legacy configs carry no normalisation"))))

(deftest empty-config-uses-defaults
  (let [cfg (profile/resolve-config {} all-accels)]
    (is (= :none (:accel cfg)))
    (is (= "h264" (get-in cfg [:video :codec])))
    (is (= 2 (get-in cfg [:hls :segment-duration])))
    (is (= 10 (get-in cfg [:hls :playlist-size])))))

(deftest nested-config-keeps-requested-accel-when-available
  (let [cfg (profile/resolve-config
             {:accel "nvenc" :video {:codec "h264" :bitrate "5000k"}}
             #{:none :nvenc})]
    (is (= :nvenc (:accel cfg)))
    (is (= "5000k" (get-in cfg [:video :bitrate])))))

(deftest accel-downgrades-when-unavailable
  (testing "a requested backend missing from the host falls back to software"
    (let [cfg (profile/resolve-config {:accel "vaapi"} #{:none})]
      (is (= :none (:accel cfg)))))
  (testing "a requested backend missing but another hardware accel is available falls back to it"
    (let [cfg (profile/resolve-config {:accel "vaapi"} #{:none :nvenc})]
      (is (= :nvenc (:accel cfg))))
    (let [cfg (profile/resolve-config {:accel "nvenc"} #{:none :vaapi})]
      (is (= :vaapi (:accel cfg))))))

(deftest string-accel-values-are-normalised
  (testing "JSONB round-trips :accel as a string; resolve-config keywordises it"
    (is (= :vaapi (:accel (profile/resolve-config {:accel "vaapi"} all-accels))))))

(deftest accel-detection-excludes-nvidia-render-nodes
  (let [accels-from @#'profile/accels-from]
    (testing "an NVIDIA-only host (render nodes are nvidia, no /dev/nvidia0) offers no HW accel"
      (is (= #{:none} (accels-from ["nvidia" "nvidia" "nvidia"] false))))
    (testing "NVENC needs the /dev/nvidia* device, not just an nvidia render node"
      (is (= #{:none :nvenc} (accels-from ["nvidia"] true))))
    (testing "an Intel/AMD render node enables VAAPI"
      (is (= #{:none :vaapi} (accels-from ["i915"] false)))
      (is (= #{:none :vaapi} (accels-from ["amdgpu"] false))))
    (testing "an unreadable driver is assumed VAAPI-capable (avoids false negative)"
      (is (= #{:none :vaapi} (accels-from ["unknown"] false))))
    (testing "no render nodes, no nvidia device -> software only"
      (is (= #{:none} (accels-from [] false))))))

(deftest accel-survives-alongside-legacy-keys
  (testing "an explicit :accel is NOT clobbered by leftover legacy flat keys"
    (let [cfg (profile/resolve-config
               {:accel "vaapi" :video-bitrate "4000k" :audio-bitrate "128k"}
               all-accels)]
      (is (= :vaapi (:accel cfg)))
      (is (= "4000k" (get-in cfg [:video :bitrate])) "legacy bitrate is folded in")
      (is (= "128k"  (get-in cfg [:audio :bitrate])))))
  (testing "nested settings win over folded legacy ones"
    (let [cfg (profile/resolve-config
               {:accel "nvenc" :video-bitrate "1000k" :video {:bitrate "8000k"}}
               all-accels)]
      (is (= "8000k" (get-in cfg [:video :bitrate]))))))

;; ---------------------------------------------------------------------------
;; GOP derivation (one keyframe per HLS segment)
;; ---------------------------------------------------------------------------
;;
;; Without an explicit GOP, the HLS muxer would inherit the encoder's default
;; keyframe interval — NVENC's 250 frames @ 30fps forces 8.33s segments even
;; when hls.segment-duration is 2. These tests pin the default to one segment.

(deftest gop-defaults-to-one-hls-segment
  (testing "fps × hls.segment-duration when both are present"
    (let [cfg (profile/resolve-config
               {:accel "nvenc"
                :normalize {:fps 30 :width 1920 :height 1080}
                :hls      {:segment-duration 2}}
               all-accels)]
      (is (= 60 (-> cfg :video :gop)) "30fps × 2s = 60-frame GOP"))))

(deftest gop-scales-with-fps
  (testing "24fps content with 2s segments gets 48-frame GOP"
    (let [cfg (profile/resolve-config
               {:accel "nvenc"
                :normalize {:fps 24}
                :hls      {:segment-duration 2}}
               all-accels)]
      (is (= 48 (-> cfg :video :gop))))))

(deftest gop-scales-with-segment-duration
  (testing "10s segments get 10×fps GOP"
    (let [cfg (profile/resolve-config
               {:accel "nvenc"
                :normalize {:fps 30}
                :hls      {:segment-duration 10}}
               all-accels)]
      (is (= 300 (-> cfg :video :gop))))))

(deftest gop-explicit-override-wins
  (testing ":video.gop in the profile overrides the derived default"
    (let [cfg (profile/resolve-config
               {:accel "nvenc"
                :video     {:codec "h264" :gop 250}
                :normalize {:fps 30}
                :hls      {:segment-duration 2}}
               all-accels)]
      (is (= 250 (-> cfg :video :gop))))))

(deftest gop-fallback-when-fps-and-segment-missing
  (testing "no :normalize :fps and no :hls :segment-duration → 30×2=60 fallback"
    (let [cfg (profile/resolve-config
               {:accel "nvenc" :video {:codec "h264"}}
               all-accels)]
      (is (= 60 (-> cfg :video :gop))))))

;; ---------------------------------------------------------------------------
;; Argument builders
;; ---------------------------------------------------------------------------

(deftest software-encode-args
  (let [cfg (profile/resolve-config {:accel "none" :video {:codec "h264" :bitrate "2000k"}}
                                    all-accels)]
    (is (= [] (profile/input-args cfg)) "no -hwaccel for software")
    (is (= ["-c:v" "libx264" "-preset" "veryfast" "-b:v" "2000k"]
           (profile/video-encode-args cfg)))))

(deftest nvenc-encode-args
  (let [cfg (profile/resolve-config
             {:accel "nvenc" :decode "hardware"
              :video {:codec "h264" :bitrate "4000k" :preset "p4" :rate-control "vbr"}}
             all-accels)]
    (is (= ["-hwaccel" "cuda" "-hwaccel_output_format" "cuda"]
           (profile/input-args cfg)))
    (is (= ["-c:v" "h264_nvenc" "-preset" "p4" "-rc" "vbr" "-b:v" "4000k"]
           (profile/video-encode-args cfg)))))

(deftest vaapi-encode-args
  (let [cfg (profile/resolve-config
             {:accel "vaapi" :decode "hardware" :device "/dev/dri/renderD128"
              :video {:codec "h264" :bitrate "4000k" :rate-control "vbr"}}
             all-accels)]
    (is (= ["-hwaccel" "vaapi" "-hwaccel_device" "/dev/dri/renderD128"
            "-hwaccel_output_format" "vaapi"]
           (profile/input-args cfg)))
    (testing "VAAPI uses -rc_mode and no -preset"
      (is (= ["-c:v" "h264_vaapi" "-rc_mode" "VBR" "-b:v" "4000k"]
             (profile/video-encode-args cfg))))))

(deftest nvenc-encode-args-emits-gop
  (testing "NVENC: -g <gop> is emitted so segments align with hls_time"
    (let [args (profile/video-encode-args
                {:accel :nvenc
                 :video {:codec "h264" :bitrate "4000k"
                         :rate-control :vbr :preset "p4" :gop 60}})]
      (is (some #(= "-g" %) args))
      (is (some #(= "60" %) args)))))

(deftest software-encode-args-emits-gop-and-keyint-min
  (testing "libx264: both -g and -keyint_min force keyframes at the GOP interval"
    (let [args (profile/video-encode-args
                {:accel :none
                 :video {:codec "h264" :bitrate "2000k"
                         :preset "veryfast" :gop 60}})]
      (is (some #(= "-g" %) args))
      (is (some #(= "60" %) args))
      (is (some #(= "-keyint_min" %) args)))))

(deftest vaapi-encode-args-emits-gop
  (testing "VAAPI: -g <gop> is emitted"
    (let [args (profile/video-encode-args
                {:accel :vaapi
                 :video {:codec "h264" :bitrate "4000k"
                         :rate-control :vbr :gop 60}})]
      (is (some #(= "-g" %) args))
      (is (some #(= "60" %) args)))))

(deftest encode-args-omit-gop-when-not-set
  (testing "backwards compat: no -g emitted when :gop is nil"
    (let [args (profile/video-encode-args
                {:accel :nvenc
                 :video {:codec "h264" :bitrate "4000k"
                         :rate-control :vbr :preset "p4"}})]  ; no :gop
      (is (not (some #(= "-g" %) args))))))

(deftest copy-codec-bypasses-gop
  (testing ":codec 'copy' emits only -c:v copy; no GOP args"
    (let [args (profile/video-encode-args
                {:accel :nvenc :video {:codec "copy" :gop 60}})]
      (is (= ["-c:v" "copy"] args)))))

;; ---------------------------------------------------------------------------
;; Decode mode: software / hardware / auto -> hwupload -> hardware encode
;; ---------------------------------------------------------------------------

(deftest decode-defaults-to-auto
  (is (= :auto (:decode (profile/resolve-config {:accel "vaapi"} all-accels)))))

(deftest vaapi-auto-decode-best-effort-with-upload
  (testing "auto = best-effort GPU decode (SW fallback) feeding the GPU encoder"
    (let [cfg (profile/resolve-config
               {:accel "vaapi" :decode "auto" :device "/dev/dri/renderD128"
                :video {:codec "h264" :bitrate "4000k" :rate-control "vbr"}}
               all-accels)]
      ;; -hwaccel WITHOUT -hwaccel_output_format => per-stream software fallback
      (is (= ["-init_hw_device" "vaapi=va:/dev/dri/renderD128" "-hwaccel" "vaapi"
              "-hwaccel_device" "va" "-filter_hw_device" "va"]
             (profile/input-args cfg)))
      (is (not (some #{"-hwaccel_output_format"} (profile/input-args cfg)))
          "no strict output format, so a non-decodable codec falls back to software")
      (is (= "format=nv12,hwupload" (profile/video-filter cfg)))
      (is (= ["-c:v" "h264_vaapi" "-rc_mode" "VBR" "-b:v" "4000k"]
             (profile/video-encode-args cfg))))))

(deftest nvenc-auto-decode-best-effort
  (let [cfg (profile/resolve-config
             {:accel "nvenc" :decode "auto" :video {:codec "h264" :bitrate "4000k" :preset "p4"}}
             all-accels)]
    (is (= ["-init_hw_device" "cuda=cu" "-hwaccel" "cuda" "-hwaccel_device" "cu"
            "-filter_hw_device" "cu"]
           (profile/input-args cfg)))
    (is (= "format=nv12,hwupload_cuda" (profile/video-filter cfg)))))

(deftest vaapi-software-decode-uploads-then-encodes
  (testing "explicit software decode: CPU decode, no -hwaccel, upload for encode"
    (let [cfg (profile/resolve-config
               {:accel "vaapi" :decode "software" :device "/dev/dri/renderD128"
                :video {:codec "h264" :bitrate "4000k" :rate-control "vbr"}}
               all-accels)]
      (is (= ["-vaapi_device" "/dev/dri/renderD128"] (profile/input-args cfg))
          "device is set up for filtering/encoding, but no -hwaccel decode")
      (is (= "format=nv12,hwupload" (profile/video-filter cfg)))
      (is (= ["-c:v" "h264_vaapi" "-rc_mode" "VBR" "-b:v" "4000k"]
             (profile/video-encode-args cfg))))))

(deftest nvenc-software-decode-uploads-then-encodes
  (let [cfg (profile/resolve-config
             {:accel "nvenc" :decode "software" :video {:codec "h264" :bitrate "4000k" :preset "p4"}}
             all-accels)]
    (is (= ["-init_hw_device" "cuda=cu" "-filter_hw_device" "cu"] (profile/input-args cfg)))
    (is (= "format=nv12,hwupload_cuda" (profile/video-filter cfg)))))

(deftest software-decode-normalize-scales-on-cpu-before-upload
  (let [cfg (profile/resolve-config
             {:accel "vaapi" :decode "software" :normalize {:width 1280 :height 720 :fps 30}}
             all-accels)
        vf  (profile/video-filter cfg)]
    (is (re-find #"scale=1280:720" vf) "CPU scale precedes the upload")
    (is (clojure.string/ends-with? vf "format=nv12,hwupload"))))

(deftest hevc-logical-codec-maps-per-accel
  (is (= "libx265"
         (-> {:accel "none" :video {:codec "hevc"}}
             (profile/resolve-config all-accels) profile/video-encode-args (nth 1))))
  (is (= "hevc_nvenc"
         (-> {:accel "nvenc" :video {:codec "hevc"}}
             (profile/resolve-config all-accels) profile/video-encode-args (nth 1)))))

(deftest copy-codecs-pass-through
  (let [cfg (profile/resolve-config {:accel "nvenc" :video {:codec "copy"}
                                     :audio {:codec "copy"}}
                                    all-accels)]
    (is (= ["-c:v" "copy"] (profile/video-encode-args cfg)))
    (is (= ["-c:a" "copy"] (profile/audio-encode-args cfg)))))

(deftest audio-encode-args-include-stream-params
  (let [cfg (profile/resolve-config {:audio {:codec "aac" :bitrate "192k"
                                             :sample-rate 48000 :channels 2}}
                                    all-accels)]
    (is (= ["-c:a" "aac" "-b:a" "192k" "-ar" "48000" "-ac" "2"]
           (profile/audio-encode-args cfg)))))

;; ---------------------------------------------------------------------------
;; Normalisation filter
;; ---------------------------------------------------------------------------

(deftest no-normalize-block-yields-no-filter
  (is (nil? (profile/video-filter (profile/resolve-config {} all-accels)))))

(deftest software-normalize-filter
  (let [cfg (profile/resolve-config
             {:normalize {:width 1920 :height 1080 :fps 30 :pixfmt "yuv420p"}}
             all-accels)
        vf  (profile/video-filter cfg)]
    (is (re-find #"fps=30" vf))
    (is (re-find #"scale=1920:1080:force_original_aspect_ratio=decrease" vf))
    (is (re-find #"pad=1920:1080" vf))
    (is (re-find #"format=yuv420p" vf))))

(deftest hardware-decode-normalize-uses-gpu-scaler
  (testing "VAAPI hardware normalize scales aspect-correct + pads on-GPU to 8-bit"
    (let [vf (profile/video-filter
              (profile/resolve-config {:accel "vaapi" :decode "hardware"
                                       :normalize {:width 1280 :height 720}}
                                      all-accels))]
      (is (re-find #"scale_vaapi=w=1280:h=720:force_original_aspect_ratio=decrease:force_divisible_by=2:format=nv12" vf))
      (is (re-find #"pad_vaapi=w=1280:h=720:x=\(ow-iw\)/2:y=\(oh-ih\)/2" vf)
          "non-16:9 sources are letterboxed/pillarboxed, not stretched")))
  (testing "NVENC hardware normalize scales on-GPU (pad is a TODO)"
    (is (re-find #"scale_cuda=w=1280:h=720:format=nv12"
                 (profile/video-filter
                  (profile/resolve-config {:accel "nvenc" :decode "hardware"
                                           :normalize {:width 1280 :height 720}}
                                          all-accels))))))

(deftest hardware-decode-downconverts-10bit-even-without-normalize
  (testing "10-bit sources are downconverted to nv12 (8-bit) so h264_vaapi can encode"
    (is (= "scale_vaapi=format=nv12"
           (profile/video-filter
            (profile/resolve-config {:accel "vaapi" :decode "hardware"} all-accels))))
    (is (= "scale_cuda=format=nv12"
           (profile/video-filter
            (profile/resolve-config {:accel "nvenc" :decode "hardware"} all-accels))))))

;; ---------------------------------------------------------------------------
;; Full command integration (software path is always available)
;; ---------------------------------------------------------------------------

(deftest build-hls-command-software-ordering
  (testing "legacy profile produces a well-ordered software HLS command"
    (let [cmd (vec (hls/build-hls-command
                    "http://example/stream" "/tmp/out"
                    {:start-position-secs 12
                     :profile-config {:video-codec "libx264" :video-bitrate "2500k"}}))]
      ;; -hwaccel must never appear for the software path
      (is (not (some #{"-hwaccel"} cmd)))
      ;; -ss precedes -i (input seeking)
      (is (< (.indexOf cmd "-ss") (.indexOf cmd "-i")))
      (is (= "12" (nth cmd (inc (.indexOf cmd "-ss")))))
      (is (= "libx264" (nth cmd (inc (.indexOf cmd "-c:v")))))
      (is (= "2500k" (nth cmd (inc (.indexOf cmd "-b:v")))))
      ;; HLS muxer still wired up
      (is (some #{"hls"} cmd))
      (is (= "/tmp/out/playlist.m3u8" (last cmd))))))

(deftest build-hls-command-hwaccel-flags-precede-input
  (testing "with :decode hardware the -hwaccel flags land before -i"
    (with-redefs [profile/available-accels (constantly #{:none :nvenc})]
      (let [cmd (vec (hls/build-hls-command
                      "http://example/stream" "/tmp/out"
                      {:profile-config {:accel "nvenc" :decode "hardware"
                                        :video {:codec "h264" :bitrate "4000k" :preset "p4"}}}))]
        (is (< (.indexOf cmd "-hwaccel") (.indexOf cmd "-i")))
        (is (= "h264_nvenc" (nth cmd (inc (.indexOf cmd "-c:v")))))))))

(deftest build-hls-command-vaapi-auto-decode-default
  (testing "default VAAPI command uses best-effort decode (-hwaccel, no strict format) + upload"
    (with-redefs [profile/available-accels (constantly #{:none :vaapi})]
      (let [cmd (vec (hls/build-hls-command
                      "http://example/stream" "/tmp/out"
                      {:profile-config {:accel "vaapi" :device "/dev/dri/renderD128"
                                        :video {:codec "h264" :bitrate "4000k" :rate-control "vbr"}}}))]
        (is (= "vaapi" (nth cmd (inc (.indexOf cmd "-hwaccel")))) "best-effort GPU decode")
        (is (not (some #{"-hwaccel_output_format"} cmd)) "no strict format -> software fallback")
        (is (< (.indexOf cmd "-hwaccel") (.indexOf cmd "-i")))
        (is (= "h264_vaapi" (nth cmd (inc (.indexOf cmd "-c:v")))))
        (is (= "format=nv12,hwupload" (nth cmd (inc (.indexOf cmd "-vf")))))))))

(deftest build-hls-command-maps-only-video-and-audio
  (testing "only the first video + audio tracks are mapped; subtitles dropped"
    (let [cmd (vec (hls/build-hls-command
                    "http://example/stream" "/tmp/out"
                    {:profile-config {:video-codec "libx264"}}))]
      (is (= "0:v:0" (nth cmd (inc (.indexOf cmd "-map")))))
      (is (some #{"0:a:0?"} cmd) "audio mapped optionally")
      (is (some #{"-sn"} cmd) "subtitles explicitly disabled")
      ;; mapping must come after the input, before the muxer
      (is (< (.indexOf cmd "-i") (.indexOf cmd "-map")))
      (is (< (.indexOf cmd "-map") (.indexOf cmd "hls"))))))

(deftest build-hls-command-readrate-and-initial-burst
  (testing "input is paced at 1x and bursts the initial buffer by default"
    (let [cmd (vec (hls/build-hls-command
                    "http://example/stream" "/tmp/out"
                    {:profile-config {:video-codec "libx264"}}))]
      (is (not (some #{"-re"} cmd)) "bare -re replaced by -readrate")
      (is (= "1" (nth cmd (inc (.indexOf cmd "-readrate")))))
      (is (= "10" (nth cmd (inc (.indexOf cmd "-readrate_initial_burst")))))
      ;; pacing flags precede the input
      (is (< (.indexOf cmd "-readrate") (.indexOf cmd "-i")))))
  (testing ":initial-burst 0 disables the burst but keeps 1x pacing"
    (let [cmd (vec (hls/build-hls-command
                    "http://example/stream" "/tmp/out"
                    {:profile-config {:video-codec "libx264" :hls {:initial-burst 0}}}))]
      (is (= "1" (nth cmd (inc (.indexOf cmd "-readrate")))))
      (is (not (some #{"-readrate_initial_burst"} cmd))))))

;; ---------------------------------------------------------------------------
;; Regression: gop must be stringified so build-hls-command can materialise
;; the arg sequence to a String[] for ProcessBuilder.
;;
;; 2026-07-08 incident: NVENC streams returned HTTP 503 to VLC. Logs showed
;; java.lang.IllegalArgumentException: array element type mismatch at
;; clojure.core/into-array, raised by pseudovision.ffmpeg.hls/build-hls-command.
;; Root cause: video-encode-args spliced the integer :video.gop (introduced in
;; 5da1350) into the ffmpeg arg vector as ["-g" <Long>], and hls.clj wraps the
;; final sequence in (into-array String …), which rejects non-String elements.
;; The stream manager retried every 500ms; the encoder was never spawned.
;;
;; The existing per-encoder tests above (nvenc-encode-args-emits-gop et al.)
;; walked the seq with `some` and never materialised to String[], so the
;; mismatch went uncaught. These tests assert the full materialisation.
;; ---------------------------------------------------------------------------

(deftest build-hls-command-materialises-to-string-array-nvenc
  (testing "NVENC + gop → arg vector must be a String[] (no Integer leakage)"
    (with-redefs [profile/available-accels (constantly #{:none :nvenc})]
      (let [cmd (hls/build-hls-command
                 "http://example/stream" "/tmp/out"
                 {:profile-config {:accel "nvenc" :decode "hardware"
                                   :video {:codec "h264" :bitrate "4000k"
                                           :preset "p4" :rate-control "vbr"
                                           :gop 60}}})]
        ;; This is the assertion the existing tests were missing: materialising
        ;; to a String[] is what trips the IllegalArgumentException. Before
        ;; the fix, `cmd` was an Object[] containing Long values, and any
        ;; downstream caller using it as a String[] would crash.
        (is (instance? (Class/forName "[Ljava.lang.String;") cmd)
            "build-hls-command must return a String[]")
        (is (every? string? cmd) "every element must be a String")
        (is (some #(= "-g" %) cmd) "-g flag emitted")
        (is (some #(= "60" %) cmd) "gop is stringified, not an integer")
        (is (some #(= "h264_nvenc" %) cmd) "NVENC encoder selected"))))

  (testing "VAAPI + gop → String[]"
    (with-redefs [profile/available-accels (constantly #{:none :vaapi})]
      (let [cmd (hls/build-hls-command
                 "http://example/stream" "/tmp/out"
                 {:profile-config {:accel "vaapi" :decode "hardware"
                                   :device "/dev/dri/renderD128"
                                   :video {:codec "h264" :bitrate "4000k"
                                           :rate-control "vbr" :gop 60}}})]
        (is (instance? (Class/forName "[Ljava.lang.String;") cmd))
        (is (every? string? cmd))
        (is (some #(= "60" %) cmd) "VAAPI gop is stringified"))))

  (testing "libx264 + gop → String[] (the gop case is doubled: -g and -keyint_min)"
    (let [cmd (hls/build-hls-command
               "http://example/stream" "/tmp/out"
               {:profile-config {:accel "none"
                                 :video {:codec "h264" :bitrate "2000k"
                                         :preset "veryfast" :gop 60}}})]
      (is (instance? (Class/forName "[Ljava.lang.String;") cmd))
      (is (every? string? cmd))
      (is (some #(= "-g" %) cmd))
      (is (some #(= "-keyint_min" %) cmd))
      ;; Both -g and -keyint_min must point at the stringified gop
      (is (= "60" (nth cmd (inc (.indexOf cmd "-g"))))
          "-g value is \"60\"")
      (is (= "60" (nth cmd (inc (.indexOf cmd "-keyint_min"))))
          "-keyint_min value is \"60\""))))

(deftest video-encode-args-emits-string-gop
  (testing "every encoder backend that supports gop emits a String, not a Long"
    (let [nvenc (profile/video-encode-args
                 {:accel :nvenc :video {:codec "h264" :bitrate "4000k"
                                        :rate-control :vbr :preset "p4" :gop 60}})
          vaapi (profile/video-encode-args
                 {:accel :vaapi :video {:codec "h264" :bitrate "4000k"
                                        :rate-control :vbr :gop 60}})
          sw    (profile/video-encode-args
                 {:accel :none :video {:codec "h264" :bitrate "2000k"
                                       :preset "veryfast" :gop 60}})]
      (doseq [[label args] [["NVENC" nvenc] ["VAAPI" vaapi] ["libx264" sw]]]
        (is (every? string? args) (str label ": every arg is a String"))
        (is (some #(= "60" %) args) (str label ": gop value is the string \"60\""))
        (is (not-any? #(= 60 %) args) (str label ": integer 60 must NOT appear"))))))

;; ---------------------------------------------------------------------------
;; Regression: slate must not leak an NVENC preset into libx264.
;;
;; 2026-07-09 incident: every channel using ffmpeg profile id=5 ("NVENC 1080p",
;; which has :video.preset "p4") returned HTTP 503 to VLC within seconds of
;; the stream manager falling back to the generated slate (typically when the
;; playout queue had no upcoming event).  The ffmpeg logs in the pod showed
;; 16+ consecutive failures, each with
;;   "x264 [error]: invalid preset 'p4'"
;;   "Possible presets: ultrafast superfast veryfast faster fast medium slow
;;    slower veryslow placebo"
;; and exit code 234.
;;
;; Root cause: build-slate-command in hls.clj forced :accel :none so the
;; slate would render in software, but did NOT strip the profile's
;; :video.preset (NVENC's "p1".."p7" quality shorthand) or
;; :video.rate-control (:vbr/:cbr — libx264's branch in video-encode-args
;; doesn't emit -rc_mode or -rc).  The :none branch then dutifully emitted
;;   -c:v libx264 -preset p4 -b:v 4000k
;; and libx264 died in 500ms.  video-encode-args was the wrong place to fix
;; this — the accel override in build-slate-command must strip the
;; accel-specific fields, otherwise the same class of bug will recur for any
;; future accel that adds new fields to its :video branch.
;;
;; These two tests pin the fix from BOTH ends: the slate command never
;; carries an accel-specific value, and the underlying video-encode-args
;; caller (with the override applied) produces a clean libx264 invocation.
;; ---------------------------------------------------------------------------

(deftest build-slate-command-strips-accel-specific-video-fields
  (testing "an NVENC profile's :video.preset (p1..p7) does not leak into the slate"
    (let [cmd (vec (hls/build-slate-command
                    "/tmp/slate-out"
                    {:channel-name "Spotlight"
                     :channel-number 5
                     :upcoming-events []
                     :profile-config
                     {:accel "nvenc"
                      :decode "hardware"
                      :video {:codec "h264" :bitrate "4000k"
                              :preset "p4" :rate-control :vbr}}}))]
      ;; The exact failure mode: libx264 was being passed -preset p4.
      ;; After the fix, no element of the arg vector is "p4".
      (is (not-any? #(= "p4" %) cmd)
          "NVENC's :video.preset \"p4\" must NOT appear in the slate command")
      (is (not-any? #(= "p1" %) cmd)
          "nor p1, p2, p3, p5, p6, p7 (all NVENC quality shorthand)")
      ;; The slate still uses libx264 (forced by the :accel :none override).
      (is (some #(= "libx264" %) cmd)
          "slate still encodes in software via libx264")
      ;; And the libx264 default preset ("veryfast") is what the
      ;; :none branch falls back to.
      (is (some #(= "veryfast" %) cmd)
          "libx264 default preset is \"veryfast\"")
      ;; rate-control for NVENC is :vbr/:cbr; libx264 doesn't take a
      ;; -rc_mode / -rc flag, so the value must not be passed at all.
      (is (not-any? #(= "vbr" %) cmd)
          "NVENC :vbr rate-control must not leak into the libx264 arg list")
      (is (not-any? #(= "cbr" %) cmd)
          "nor :cbr")
      ;; The full arg vector must materialise to a String[] (regression
      ;; on the build-hls-command-materialises-to-string-array pattern).
      (let [materialised (hls/build-slate-command
                          "/tmp/slate-out"
                          {:channel-name "Spotlight"
                           :channel-number 5
                           :upcoming-events []
                           :profile-config
                           {:accel "nvenc"
                            :video {:codec "h264" :bitrate "4000k"
                                    :preset "p4" :rate-control :vbr}}})]
        (is (instance? (Class/forName "[Ljava.lang.String;") materialised)
            "build-slate-command must return a String[]")
        (is (every? string? materialised)
            "every element of the slate arg vector is a String")))))

(deftest build-slate-command-with-software-profile-unchanged
  (testing "a software profile's codec/bitrate survive the slate override"
    ;; The slate path forces :accel :none and strips :video.preset so
    ;; the slate always uses the libx264 default ("veryfast") — this is
    ;; deliberate, so the slate's encoder behaviour is independent of
    ;; whatever the channel's regular-accel profile says.  What MUST
    ;; survive the override is the codec and the bitrate.
    (let [cmd (vec (hls/build-slate-command
                    "/tmp/slate-out"
                    {:channel-name "Some Channel"
                     :channel-number 9
                     :upcoming-events []
                     :profile-config
                     {:accel "none"
                      :video {:codec "h264" :bitrate "2500k"
                              :preset "fast"}}}))]
      (is (some #(= "libx264" %) cmd)
          "software profile's codec is preserved (libx264)")
      (is (some #(= "2500k" %) cmd)
          "software profile's bitrate is preserved")
      (is (some #(= "veryfast" %) cmd)
          "slate always uses the libx264 default preset (NOT the profile's)")
      (is (not-any? #(= "fast" %) cmd)
          "profile's :video.preset is not passed through (the dissoc works)"))))
