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
             {:accel "nvenc" :video {:codec "h264" :bitrate "4000k"
                                     :preset "p4" :rate-control "vbr"}}
             all-accels)]
    (is (= ["-hwaccel" "cuda" "-hwaccel_output_format" "cuda"]
           (profile/input-args cfg)))
    (is (= ["-c:v" "h264_nvenc" "-preset" "p4" "-rc" "vbr" "-b:v" "4000k"]
           (profile/video-encode-args cfg)))))

(deftest vaapi-encode-args
  (let [cfg (profile/resolve-config
             {:accel "vaapi" :device "/dev/dri/renderD128"
              :video {:codec "h264" :bitrate "4000k" :rate-control "vbr"}}
             all-accels)]
    (is (= ["-hwaccel" "vaapi" "-hwaccel_device" "/dev/dri/renderD128"
            "-hwaccel_output_format" "vaapi"]
           (profile/input-args cfg)))
    (testing "VAAPI uses -rc_mode and no -preset"
      (is (= ["-c:v" "h264_vaapi" "-rc_mode" "VBR" "-b:v" "4000k"]
             (profile/video-encode-args cfg))))))

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

(deftest hardware-normalize-uses-gpu-scaler
  (is (re-find #"scale_vaapi=w=1280:h=720"
               (profile/video-filter
                (profile/resolve-config {:accel "vaapi" :normalize {:width 1280 :height 720}}
                                        all-accels))))
  (is (re-find #"scale_cuda=w=1280:h=720"
               (profile/video-filter
                (profile/resolve-config {:accel "nvenc" :normalize {:width 1280 :height 720}}
                                        all-accels)))))

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
  (testing "when nvenc is available the decode flags land before -i"
    (with-redefs [profile/available-accels (constantly #{:none :nvenc})]
      (let [cmd (vec (hls/build-hls-command
                      "http://example/stream" "/tmp/out"
                      {:profile-config {:accel "nvenc"
                                        :video {:codec "h264" :bitrate "4000k" :preset "p4"}}}))]
        (is (< (.indexOf cmd "-hwaccel") (.indexOf cmd "-i")))
        (is (= "h264_nvenc" (nth cmd (inc (.indexOf cmd "-c:v")))))))))
