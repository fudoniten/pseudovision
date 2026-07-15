(ns pseudovision.ffmpeg.hls-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.ffmpeg.hls :as sut]))

(defn- build [source-url opts]
  (vec (sut/build-hls-command source-url "/tmp/out" (merge {:profile-config {}} opts))))

(defn- args-after [cmd flag]
  (->> cmd (drop-while #(not= flag %)) second))

(defn- map-args [cmd]
  (keep-indexed (fn [i v] (when (= "-map" v) (nth cmd (inc i)))) cmd))

(deftest default-audio-map-unchanged-with-no-preference
  (testing "no audio-stream-index resolved -> falls back to ffmpeg's own 0:a:0?"
    (let [cmd (build "http://host/video" {})]
      (is (= ["0:v:0" "0:a:0?"] (map-args cmd))))))

(deftest audio-map-uses-resolved-stream-index
  (testing "a resolved audio-stream-index maps that absolute stream instead of 0:a:0"
    (let [cmd (build "http://host/video" {:audio-stream-index 3})]
      (is (= ["0:v:0" "0:3?"] (map-args cmd))))))

(deftest sn-always-present-regardless-of-subtitle-burn-in
  (testing "-sn keeps subtitle tracks out of the output mux even when burning one in via -vf"
    (let [cmd (build "http://host/video" {:subtitle-burn-in {:stream-index 2}})]
      (is (some #{"-sn"} cmd)))))

(deftest subtitle-burn-in-adds-vf-with-escaped-source-url
  (testing "burns the resolved subtitle stream into the picture via the subtitles filter"
    (let [cmd (build "http://host:8096/Videos/x/stream?static=true&api_key=abc"
                     {:subtitle-burn-in {:stream-index 2}})
          vf  (args-after cmd "-vf")]
      (is (re-find #"^subtitles=filename='.*':si=2$" vf))
      (is (not (re-find #"host:8096" vf)) "the raw (unescaped) colon must not survive"))))

(deftest no-subtitle-burn-in-omits-vf-when-nothing-else-needs-it
  (testing "no overlay, no profile normalisation, no subtitle -> no -vf flag at all"
    (let [cmd (build "http://host/video" {})]
      (is (not (some #{"-vf"} cmd))))))

(deftest subtitle-and-overlay-chain-together
  (testing "subtitle burn-in and bumper overlay both land in the same -vf, subtitle first"
    (let [cmd (build "http://host/video" {:subtitle-burn-in {:stream-index 1}
                                          :overlay-text "Coming up next"})
          vf  (args-after cmd "-vf")]
      (is (re-find #"^subtitles=.*,drawtext=" vf)))))
