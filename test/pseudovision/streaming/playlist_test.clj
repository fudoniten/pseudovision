(ns pseudovision.streaming.playlist-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [pseudovision.streaming.playlist :as pl]))

(defn- add* [playlist & segs]
  (reduce (fn [p s] (first (pl/add-segment p s))) playlist segs))

(deftest empty-playlist-defaults
  (let [p (pl/new-playlist)]
    (is (= 0 (:next-seq p)))
    (is (= 0 (:disc-seq p)))
    (is (empty? (:segments p)))))

(deftest segments-get-monotonic-seq-no-initial-discontinuity
  (let [p (add* (pl/new-playlist)
                {:name "a.ts" :duration 2.0}
                {:name "b.ts" :duration 2.0})]
    (is (= [0 1] (mapv :seq (:segments p))))
    (is (= 2 (:next-seq p)))
    (is (every? (complement :discontinuity?) (:segments p))
        "the very first encoder output carries no discontinuity")))

(deftest mark-discontinuity-flags-only-next-segment
  (let [p (-> (pl/new-playlist)
              (add* {:name "a.ts" :duration 2.0})
              (pl/mark-discontinuity)
              (add* {:name "b.ts" :duration 2.0}
                    {:name "c.ts" :duration 2.0}))]
    (is (= [false true false] (mapv :discontinuity? (:segments p)))
        "discontinuity applies to exactly the first segment after the mark")))

(deftest window-eviction-advances-media-sequence
  (let [p (reduce (fn [p i] (first (pl/add-segment p {:name (str i ".ts") :duration 2.0})))
                  (pl/new-playlist {:window-size 10})
                  (range 12))]
    (is (= 10 (count (:segments p))))
    (is (= 2 (:seq (first (:segments p)))) "oldest two evicted")
    (is (str/includes? (pl/render p identity) "#EXT-X-MEDIA-SEQUENCE:2"))))

(deftest discontinuity-sequence-increments-only-when-boundary-evicted
  ;; window-size 2; sequence: a (no disc), b (disc), c, d
  (let [p0 (pl/new-playlist {:window-size 2})
        p1 (first (pl/add-segment p0 {:name "a.ts" :duration 2.0}))
        p2 (first (pl/add-segment (pl/mark-discontinuity p1) {:name "b.ts" :duration 2.0}))
        [p3 ev3] (pl/add-segment p2 {:name "c.ts" :duration 2.0})  ; evicts a (no disc)
        [p4 ev4] (pl/add-segment p3 {:name "d.ts" :duration 2.0})] ; evicts b (disc)
    (is (= 0 (:disc-seq p3)) "evicting a non-discontinuity segment leaves disc-seq")
    (is (= [{:name "a.ts"}] (mapv #(select-keys % [:name]) ev3)))
    (is (= 1 (:disc-seq p4)) "evicting the discontinuity boundary bumps disc-seq")
    (is (= [true] (mapv :discontinuity? ev4)))))

(deftest render-shape
  (let [p (-> (pl/new-playlist)
              (add* {:name "a.ts" :duration 2.0})
              (pl/mark-discontinuity)
              (add* {:name "b.ts" :duration 1.5}))
        out (pl/render p #(str "/seg/" %))]
    (is (str/starts-with? out "#EXTM3U"))
    (is (str/includes? out "#EXT-X-VERSION:3"))
    (is (str/includes? out "#EXT-X-TARGETDURATION:2"))
    (is (str/includes? out "#EXT-X-MEDIA-SEQUENCE:0"))
    (is (str/includes? out "#EXT-X-DISCONTINUITY-SEQUENCE:0"))
    (is (str/includes? out "#EXTINF:2.000,\n/seg/a.ts"))
    (is (str/includes? out "#EXT-X-DISCONTINUITY\n#EXTINF:1.500,\n/seg/b.ts")
        "discontinuity tag precedes the EXTINF of its segment")
    (is (not (str/includes? out "#EXT-X-ENDLIST")) "live playlist has no endlist")))

(deftest parse-round-trips-ffmpeg-style-playlist
  (let [content (str "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n"
                     "#EXT-X-MEDIA-SEQUENCE:0\n"
                     "#EXTINF:2.000000,\nsegment-000.ts\n"
                     "#EXTINF:1.960000,\nsegment-001.ts\n")
        parsed (pl/parse-media-playlist content)]
    (is (= ["segment-000.ts" "segment-001.ts"] (mapv :name parsed)))
    (is (= 2.0 (:duration (first parsed))))
    (is (= 1.96 (:duration (second parsed))))))
