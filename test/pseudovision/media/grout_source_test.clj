(ns pseudovision.media.grout-source-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.db.core :as db]
            [pseudovision.media.grout :as grout]
            [pseudovision.media.grout-source :as sut])
  (:import [java.time Duration Instant]))

(def ^:private enabled-client (grout/client {:base-url "http://grout:8080"}))

;; ---------------------------------------------------------------------------
;; ingest-clip! guard rails
;; ---------------------------------------------------------------------------

(deftest ingest-skips-clip-without-path
  (with-redefs [db/query-one (fn [& _] (throw (Exception. "no DB expected")))]
    (is (nil? (sut/ingest-clip! nil 1 {:id "a" :duration-ms 5000})))
    (is (nil? (sut/ingest-clip! nil 1 {:id "a" :path "" :duration-ms 5000})))))

(deftest ingest-skips-clip-without-positive-duration
  (with-redefs [db/query-one (fn [& _] (throw (Exception. "no DB expected")))]
    (is (nil? (sut/ingest-clip! nil 1 {:id "a" :path "/data/a.mp4"})))
    (is (nil? (sut/ingest-clip! nil 1 {:id "a" :path "/data/a.mp4" :duration-ms 0})))
    (is (nil? (sut/ingest-clip! nil 1 {:id "a" :path "/data/a.mp4" :duration-ms -3})))))

(deftest ingest-returns-existing-item-without-insert
  (testing "an already-ingested clip is returned from the lookup, no transaction"
    (with-redefs [db/query-one (fn [_ _] {:media-items/id 42
                                          :duration (Duration/ofMillis 65000)})]
      (is (= {:media-items/id 42
              :media-versions/duration (Duration/ofMillis 65000)}
             (sut/ingest-clip! nil 1 {:id "a" :path "/data/a.mp4" :duration-ms 65000}))))))

(deftest ingest-existing-falls-back-to-clip-duration
  (testing "when the stored version has no duration, use the clip's duration-ms"
    (with-redefs [db/query-one (fn [_ _] {:media-items/id 42 :duration nil})]
      (is (= (Duration/ofMillis 65000)
             (:media-versions/duration
              (sut/ingest-clip! nil 1 {:id "a" :path "/data/a.mp4" :duration-ms 65000})))))))

;; ---------------------------------------------------------------------------
;; grout-filler-items
;; ---------------------------------------------------------------------------

(deftest grout-filler-items-empty-when-disabled
  (with-redefs [grout/find-filler (fn [& _] (throw (Exception. "should not query")))]
    (is (= [] (sut/grout-filler-items nil nil {} (Instant/now) (Instant/now) {})))))

(deftest grout-filler-items-derives-channel-and-window
  (let [captured (atom nil)
        from     (Instant/parse "2026-07-03T10:00:00Z")
        to       (Instant/parse "2026-07-03T10:01:30Z")   ; 90s gap
        clip     {:id "c1" :path "/data/c1.mp4" :duration-ms 65000}]
    (with-redefs [grout/find-filler        (fn [_ opts] (reset! captured opts) [clip])
                  sut/ensure-library-path! (fn [_ _] 7)
                  sut/ingest-clip!         (fn [_ _ c] {:media-items/id 1
                                                        :media-versions/duration
                                                        (Duration/ofMillis (:duration-ms c))})]
      (let [result (sut/grout-filler-items nil enabled-client
                                           {:channels/name "Britannia"}
                                           from to
                                           {:filler-presets/grout-tags ["daytime" "fun"]})]
        (is (= 1 (count result)))
        (is (= (Duration/ofMillis 65000) (:media-versions/duration (first result))))
        (let [opts @captured]
          (is (= "britannia"        (:channel opts)))
          (is (= ["daytime" "fun"]  (:tags opts)))
          (is (= 90000              (:max-ms opts)))
          (is (true?                (:random opts))))))))

(deftest grout-filler-items-generic-when-no-channel-name
  (let [captured (atom nil)]
    (with-redefs [grout/find-filler        (fn [_ opts] (reset! captured opts) [])
                  sut/ensure-library-path! (fn [_ _] 7)]
      (sut/grout-filler-items nil enabled-client {} (Instant/now) (Instant/now)
                              {:filler-presets/grout-tags ["ident"]})
      (is (not (contains? @captured :channel))))))

(deftest grout-filler-items-empty-when-grout-has-no-match
  (with-redefs [grout/find-filler        (fn [& _] [])
                sut/ensure-library-path! (fn [& _] (throw (Exception. "no ingest expected")))]
    (is (= [] (sut/grout-filler-items nil enabled-client
                                      {:channels/name "Britannia"}
                                      (Instant/now) (Instant/now)
                                      {:filler-presets/grout-tags ["x"]})))))

;; ---------------------------------------------------------------------------
;; content sync — program-tags
;; ---------------------------------------------------------------------------

(deftest program-tags-passthrough-and-channel-synthesis
  (testing "grout tags pass through (trimmed, deduped) + channel:<slug> synthesized"
    (is (= ["daytime" "fun" "channel:britannia"]
           (#'sut/program-tags {:tags ["daytime" " fun " "daytime"] :channel "Britannia"})))))

(deftest program-tags-no-channel-tag-when-blank
  (is (= ["a"] (#'sut/program-tags {:tags ["a"] :channel "  "})))
  (is (= []    (#'sut/program-tags {:tags [] :channel nil})))
  (is (= []    (#'sut/program-tags {}))))

(deftest program-tags-dedupes-existing-channel-tag
  (is (= ["channel:britannia"]
         (#'sut/program-tags {:tags ["channel:britannia"] :channel "britannia"}))))

;; ---------------------------------------------------------------------------
;; content sync — sync-program! guard rails + sync-programs! aggregation
;; ---------------------------------------------------------------------------

(deftest sync-program-skips-unusable-clips
  ;; Path/duration guards return :skipped before any DB work.
  (is (= :skipped (sut/sync-program! nil 1 {:id "a"})))
  (is (= :skipped (sut/sync-program! nil 1 {:id "a" :path "" :duration-ms 5})))
  (is (= :skipped (sut/sync-program! nil 1 {:id "a" :path "/x.mp4" :duration-ms 0})))
  (is (= :skipped (sut/sync-program! nil 1 {:id "a" :path "/x.mp4" :duration-ms -1}))))

(deftest sync-programs-disabled-returns-zeroes
  (with-redefs [grout/list-programs (fn [& _] (throw (Exception. "should not query")))]
    (is (= {:enabled false :total 0 :synced 0 :updated 0 :skipped 0 :errors 0}
           (sut/sync-programs! nil nil)))))

(deftest sync-programs-aggregates-outcomes
  (let [clips [{:id "1"} {:id "2"} {:id "3"} {:id "4"}]]
    (with-redefs [grout/enabled?                   (fn [_] true)
                  grout/list-programs              (fn [_] clips)
                  sut/ensure-content-library-path! (fn [_ _] 7)
                  sut/sync-program!                (fn [_ _ clip]
                                                     (case (:id clip)
                                                       "1" :synced
                                                       "2" :updated
                                                       "3" :skipped
                                                       "4" (throw (Exception. "boom"))))]
      (let [r (sut/sync-programs! nil {:base-url "http://grout:8080"})]
        (is (true? (:enabled r)))
        (is (= 4 (:total r)))
        (is (= 1 (:synced r)))
        (is (= 1 (:updated r)))
        (is (= 1 (:skipped r)))
        (is (= 1 (:errors r)) "a throwing sync-program! is counted as an error, not fatal")))))
