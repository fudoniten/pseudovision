(ns pseudovision.streaming.manager-test
  "Exercises the manager's ingest + discontinuity core with a SIMULATED encoder
   (plain files on disk), so it runs without FFmpeg. The process/loop lifecycle
   needs a hardware smoke test; the segment-ingest correctness is covered here."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [pseudovision.ffmpeg.hls :as hls]
            [pseudovision.streaming.manager :as mgr]
            [pseudovision.streaming.playlist :as pl]
            [pseudovision.streaming.segment-store :as store])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory "mgr-test" (make-array FileAttribute 0))))

(defn- write-scratch!
  "Simulates an FFmpeg encoder scratch dir: writes the named .ts files and an
   HLS playlist listing them."
  [scratch segs]
  (.mkdirs (io/file scratch))
  (doseq [{:keys [name]} segs]
    (spit (io/file scratch name) (str "DATA:" name)))
  (spit (io/file scratch "playlist.m3u8")
        (apply str "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n"
               (mapcat (fn [{:keys [name duration]}]
                         [(format "#EXTINF:%.3f,\n" (double duration)) name "\n"])
                       segs))))

(defn- test-manager []
  {:store (store/local-disk-store (temp-dir)) :registry (atom {})})

(deftest ingest-moves-segments-into-store-and-playlist
  (let [m       (test-manager)
        scratch (temp-dir)
        state   (atom {:uuid "u1" :playlist (pl/new-playlist)
                       :encoder {:scratch-dir scratch :ingested #{}}})]
    (write-scratch! scratch [{:name "segment-000.ts" :duration 2.0}
                             {:name "segment-001.ts" :duration 2.0}])
    (mgr/ingest-once! m state)
    (let [p (:playlist @state)]
      (testing "playlist gains monotonic, renamed segments"
        (is (= ["seg-0.ts" "seg-1.ts"] (mapv :name (:segments p))))
        (is (= [0 1] (mapv :seq (:segments p)))))
      (testing "segments are served from the store and removed from scratch"
        (is (store/segment-exists? (:store m) "u1" "seg-0.ts"))
        (is (not (.exists (io/file scratch "segment-000.ts")))))
      (testing "scratch names are tracked as ingested"
        (is (= #{"segment-000.ts" "segment-001.ts"} (:ingested (:encoder @state))))))))

(deftest ingest-is-idempotent
  (let [m       (test-manager)
        scratch (temp-dir)
        state   (atom {:uuid "u2" :playlist (pl/new-playlist)
                       :encoder {:scratch-dir scratch :ingested #{}}})]
    (write-scratch! scratch [{:name "segment-000.ts" :duration 2.0}])
    (mgr/ingest-once! m state)
    (mgr/ingest-once! m state)   ; second pass must not re-ingest
    (is (= 1 (count (:segments (:playlist @state)))))))

;; ---------------------------------------------------------------------------
;; Phase 2 — pre-roll + overlap handoff
;; ---------------------------------------------------------------------------

(deftest encoder-warm-detects-first-segment
  (let [warm?   @#'mgr/encoder-warm?
        scratch (temp-dir)]
    (.mkdirs (io/file scratch))
    (is (not (warm? {:scratch-dir scratch})) "no playlist yet -> not warm")
    (write-scratch! scratch [{:name "segment-000.ts" :duration 2.0}])
    (is (warm? {:scratch-dir scratch}) "a finalized segment -> warm")))

(deftest promote-splices-next-encoder-with-one-discontinuity
  (testing "current drains, next becomes current, exactly one discontinuity"
    (with-redefs [hls/stop-ffmpeg (fn [_] nil)]   ; encoders have no real process
      (let [promote!  @#'mgr/promote-next!
            m         (assoc (test-manager) :db nil)
            scratch-a (temp-dir)
            scratch-b (temp-dir)
            state     (atom {:uuid "u4" :playlist (pl/new-playlist)
                             :encoder      {:scratch-dir scratch-a :ingested #{} :media-view-id nil}
                             :next-encoder {:scratch-dir scratch-b :ingested #{} :media-view-id nil}})]
        (write-scratch! scratch-a [{:name "segment-000.ts" :duration 2.0}
                                   {:name "segment-001.ts" :duration 2.0}])
        (write-scratch! scratch-b [{:name "segment-000.ts" :duration 2.0}])
        (promote! m state)
        (testing "next encoder is now current; previous one drained and removed"
          (is (= scratch-b (:scratch-dir (:encoder @state))))
          (is (nil? (:next-encoder @state)))
          (is (not (.exists (io/file scratch-a "playlist.m3u8")))))
        (testing "splicing in the new encoder carries a single discontinuity"
          (mgr/ingest-once! m state)
          (let [segs (:segments (:playlist @state))]
            (is (= ["seg-0.ts" "seg-1.ts" "seg-2.ts"] (mapv :name segs)))
            (is (= [false false true] (mapv :discontinuity? segs)))))))))

(deftest transition-inserts-single-discontinuity
  (testing "after a simulated encoder swap, only the first new segment is tagged"
    (let [m        (test-manager)
          scratch-a (temp-dir)
          scratch-b (temp-dir)
          state    (atom {:uuid "u3" :playlist (pl/new-playlist)
                          :encoder {:scratch-dir scratch-a :ingested #{}}})]
      ;; Encoder A
      (write-scratch! scratch-a [{:name "segment-000.ts" :duration 2.0}
                                 {:name "segment-001.ts" :duration 2.0}])
      (mgr/ingest-once! m state)
      ;; Simulate the manager transition: mark discontinuity + swap to encoder B
      (swap! state #(-> %
                        (update :playlist pl/mark-discontinuity)
                        (assoc :encoder {:scratch-dir scratch-b :ingested #{}})))
      (write-scratch! scratch-b [{:name "segment-000.ts" :duration 2.0}
                                 {:name "segment-001.ts" :duration 2.0}])
      (mgr/ingest-once! m state)
      (let [segs (:segments (:playlist @state))]
        (is (= ["seg-0.ts" "seg-1.ts" "seg-2.ts" "seg-3.ts"] (mapv :name segs)))
        (is (= [false false true false] (mapv :discontinuity? segs))
            "exactly one discontinuity, at the A→B boundary")))))
