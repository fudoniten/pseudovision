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
      (is (= :none (:accel cfg))))))

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
  (testing "with :decode hardware the frames are GPU surfaces, scaled on-GPU"
    (is (re-find #"scale_vaapi=w=1280:h=720"
                 (profile/video-filter
                  (profile/resolve-config {:accel "vaapi" :decode "hardware"
                                           :normalize {:width 1280 :height 720}}
                                          all-accels))))
    (is (re-find #"scale_cuda=w=1280:h=720"
                 (profile/video-filter
                  (profile/resolve-config {:accel "nvenc" :decode "hardware"
                                           :normalize {:width 1280 :height 720}}
                                          all-accels))))))

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
