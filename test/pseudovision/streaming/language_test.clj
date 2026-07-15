(ns pseudovision.streaming.language-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.streaming.language :as sut]))

(defn- stream [kind lang & {:keys [index default? forced?]
                             :or   {index 0 default? false forced? false}}]
  {:kind kind :language lang :stream-index index :is-default default? :is-forced forced?})

;; ---------------------------------------------------------------------------
;; resolve-audio
;; ---------------------------------------------------------------------------

(deftest resolve-audio-no-preference-lets-ffmpeg-pick
  (is (nil? (sut/resolve-audio [(stream "audio" "eng" :index 0)] nil)))
  (is (nil? (sut/resolve-audio [(stream "audio" "eng" :index 0)] ""))))

(deftest resolve-audio-matches-preferred-language
  (let [streams [(stream "audio" "eng" :index 1) (stream "audio" "spa" :index 2)]]
    (is (= {:stream-index 2 :matched-preference? true}
           (sut/resolve-audio streams "es")))))

(deftest resolve-audio-matches-across-2-and-3-letter-codes
  (let [streams [(stream "audio" "eng" :index 1)]]
    (is (= {:stream-index 1 :matched-preference? true}
           (sut/resolve-audio streams "en")))))

(deftest resolve-audio-matches-bibliographic-vs-terminological-codes
  (testing "French tagged with the bibliographic code 'fre' matches a preference of 'fr'"
    (let [streams [(stream "audio" "fre" :index 3)]]
      (is (= {:stream-index 3 :matched-preference? true}
             (sut/resolve-audio streams "fr"))))))

(deftest resolve-audio-falls-back-to-default-track-when-preference-missing
  (let [streams [(stream "audio" "eng" :index 0)
                 (stream "audio" "spa" :index 1 :default? true)]]
    (is (= {:stream-index 1 :matched-preference? false}
           (sut/resolve-audio streams "jpn")))))

(deftest resolve-audio-falls-back-to-first-track-when-no-default
  (let [streams [(stream "audio" "eng" :index 0) (stream "audio" "spa" :index 1)]]
    (is (= {:stream-index 0 :matched-preference? false}
           (sut/resolve-audio streams "jpn")))))

(deftest resolve-audio-nil-when-item-has-no-audio-at-all
  (is (nil? (sut/resolve-audio [(stream "video" nil :index 0)] "eng"))))

;; ---------------------------------------------------------------------------
;; resolve-subtitle-burn-in
;; ---------------------------------------------------------------------------

(defn- subs-opts [overrides]
  (merge {:preferred-subtitle-language nil
          :subtitle-default-enabled?  false
          :audio-matched-preference?  true
          :subtitle-enabled?          true}
         overrides))

(deftest subtitle-burn-in-off-when-channel-disabled
  (let [streams [(stream "subtitle" "eng" :index 5)]]
    (is (nil? (sut/resolve-subtitle-burn-in
               streams (subs-opts {:subtitle-enabled? false
                                    :subtitle-default-enabled? true
                                    :audio-matched-preference? false}))))))

(deftest subtitle-burn-in-on-by-default-uses-preferred-language
  (let [streams [(stream "subtitle" "eng" :index 4) (stream "subtitle" "spa" :index 5)]]
    (is (= {:kind "subtitle" :language "spa" :stream-index 5 :is-default false :is-forced false}
           (sut/resolve-subtitle-burn-in
            streams (subs-opts {:subtitle-default-enabled? true
                                 :preferred-subtitle-language "es"}))))))

(deftest subtitle-burn-in-on-by-default-falls-back-to-en
  (let [streams [(stream "subtitle" "eng" :index 4)]]
    (is (= 4 (:stream-index
              (sut/resolve-subtitle-burn-in
               streams (subs-opts {:subtitle-default-enabled? true
                                    :preferred-subtitle-language "fr"})))))))

(deftest subtitle-burn-in-off-when-neither-preferred-nor-en-present
  (let [streams [(stream "subtitle" "jpn" :index 4)]]
    (is (nil? (sut/resolve-subtitle-burn-in
               streams (subs-opts {:subtitle-default-enabled? true
                                    :preferred-subtitle-language "fr"}))))))

(deftest subtitle-burn-in-not-forced-when-audio-preference-was-honoured
  (let [streams [(stream "subtitle" "eng" :index 4)]]
    (is (nil? (sut/resolve-subtitle-burn-in
               streams (subs-opts {:subtitle-default-enabled? false
                                    :audio-matched-preference? true}))))))

(deftest subtitle-burn-in-forced-on-when-preferred-audio-unavailable
  (testing "the safety-net fallback fires even when 'on by default' is false"
    (let [streams [(stream "subtitle" "eng" :index 4)]]
      (is (= 4 (:stream-index
                (sut/resolve-subtitle-burn-in
                 streams (subs-opts {:subtitle-default-enabled? false
                                      :audio-matched-preference? false}))))))))

(deftest subtitle-burn-in-not-forced-when-no-audio-preference-was-configured
  (testing "nil matched-preference? (no audio preference set at all) does not trigger the fallback"
    (let [streams [(stream "subtitle" "eng" :index 4)]]
      (is (nil? (sut/resolve-subtitle-burn-in
                 streams (subs-opts {:subtitle-default-enabled? false
                                      :audio-matched-preference? nil})))))))
