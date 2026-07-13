(ns pseudovision.scheduling.filler-integration-test
  "End-to-end filler test against a real PostgreSQL.

   Builds two channels that share an identical schedule, the same content and
   the same filler pool, and (crucially) the SAME playout seed, then asserts:

     - the whole build -> pack -> persist path works against real Postgres
       (enums, JSONB cursor, INTERVAL durations, the IN-of-enum recency query);
     - tail filler packs each gap nearly full;
     - filler varies across a channel's own breaks (in-build recency);
     - filler is spaced GLOBALLY across channels: because the two channels are
       byte-for-byte identical except for what the other channel already
       scheduled, any divergence in their filler is caused solely by global
       cross-channel recency.  Channel A is built first (no other filler
       exists); channel B is built second and must steer away from A.

   Gated on PSEUDOVISION_TEST_DB_URL — skips cleanly when unset, so it does not
   disturb the default `nix check` test run.  `nix run .#integration-test`
   brings up an ephemeral Postgres and runs it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [honey.sql.helpers :as h]
            [pseudovision.db.core :as db]
            [pseudovision.db.filler :as filler-db]
            [pseudovision.db.playouts :as playout-db]
            [pseudovision.scheduling.core :as sched]
            [pseudovision.util.sql :as sql-util]
            [pseudovision.util.time :as t])
  (:import [java.time Duration Instant]))

;; ---------------------------------------------------------------------------
;; Fixture: connect + migrate only when a test DB is configured
;; ---------------------------------------------------------------------------

(defonce ^:private ds-atom (atom nil))

(defn- env [k] (System/getenv k))

(defn- db-fixture [f]
  (if-let [url (env "PSEUDOVISION_TEST_DB_URL")]
    (let [ds (db/make-datasource {:jdbc-url url
                                  :username (env "PSEUDOVISION_TEST_DB_USER")
                                  :password (env "PSEUDOVISION_TEST_DB_PASS")})]
      (try
        (db/migrate! ds)
        (reset! ds-atom ds)
        (f)
        (finally
          (reset! ds-atom nil)
          (db/close-datasource! ds))))
    (do (println "[filler-integration] PSEUDOVISION_TEST_DB_URL not set; skipping")
        (f))))

(use-fixtures :once db-fixture)

;; ---------------------------------------------------------------------------
;; Seeding helpers
;; ---------------------------------------------------------------------------

(def ^:private seed-value 4242)

;; Varied filler pool (seconds): bumpers/promos/shorts from 30s to 5min.
(def ^:private filler-seconds
  [30 45 60 75 90 120 150 180 210 240 270 300])

(def ^:private content-seconds (* 21 60))   ; 21-minute "episode"
(def ^:private block-seconds   (* 30 60))    ; 30-minute block -> 9-minute gap

(defn- ins!
  "Inserts one row and returns its generated id."
  [ds table row]
  (:id (db/execute-one! ds (-> (h/insert-into table)
                               (h/values [row])
                               (h/returning :*)
                               sql/format))))

(defn- enum [type v] (sql-util/->pg-enum type v))
(defn- secs->interval [s] (sql-util/->pg-interval (str s " seconds")))

(defn- add-item! [ds lp-id coll-id secs]
  (let [mid (ins! ds :media-items {:kind            (enum "media_item_kind" "movie")
                                   :library-path-id lp-id})]
    (ins! ds :media-versions {:media-item-id mid :duration (secs->interval secs)})
    (ins! ds :collection-items {:collection-id coll-id :media-item-id mid})
    mid))

(defn- truncate-all! [ds]
  (jdbc/execute!
   ds ["TRUNCATE media_sources, libraries, library_paths, media_items,
        media_versions, collections, collection_items, filler_presets,
        schedules, schedule_slots, channels, playouts, playout_events,
        playout_history, ffmpeg_profiles RESTART IDENTITY CASCADE"]))

(defn- seed!
  "Builds the whole fixture and returns the two playout ids {:a .. :b ..}.
   Both channels share one schedule, the same content/filler, and the same
   playout seed."
  [ds]
  (truncate-all! ds)
  (let [profile  (ins! ds :ffmpeg-profiles {:name "test" :config (sql-util/->jsonb {})})
        source   (ins! ds :media-sources {:name "src"})
        library  (ins! ds :libraries {:media-source-id source
                                      :kind (enum "library_kind" "movies")
                                      :name "lib"})
        lp       (ins! ds :library-paths {:library-id library :path "/tmp/itest"})
        content  (ins! ds :collections {:kind (enum "collection_kind" "manual")
                                        :name "content"})
        filler   (ins! ds :collections {:kind (enum "collection_kind" "manual")
                                        :name "filler"})
        _        (add-item! ds lp content content-seconds)
        _        (doseq [s filler-seconds] (add-item! ds lp filler s))
        preset   (:id (filler-db/create-filler-preset!
                       ds {:name "tail" :role "tail" :mode "duration"
                           :category "bumper" :collection-id filler}))
        schedule (ins! ds :schedules {:name "sched"})
        _        (ins! ds :schedule-slots
                       {:schedule-id    schedule
                        :slot-index     0
                        :anchor         (enum "slot_anchor" "sequential")
                        :fill-mode      (enum "slot_fill_mode" "block")
                        :block-duration (secs->interval block-seconds)
                        :tail-mode      "filler"
                        :collection-id  content
                        :tail-filler-id preset})
        chan-a   (ins! ds :channels {:number "90" :sort-number 90.0 :name "A"
                                     :ffmpeg-profile-id profile})
        chan-b   (ins! ds :channels {:number "91" :sort-number 91.0 :name "B"
                                     :ffmpeg-profile-id profile})]
    (doseq [ch [chan-a chan-b]]
      (playout-db/upsert-playout! ds ch schedule)
      (let [p (playout-db/get-playout-for-channel ds ch)]
        (playout-db/update-playout! ds (:playouts/id p) {:seed seed-value})))
    {:a (:playouts/id (playout-db/get-playout-for-channel ds chan-a))
     :b (:playouts/id (playout-db/get-playout-for-channel ds chan-b))}))

;; ---------------------------------------------------------------------------
;; Analysis helpers
;; ---------------------------------------------------------------------------

(defn- filler-events [events]
  (filter #(= "tail" (:playout-events/kind %)) events))

(defn- event-secs [e]
  (.getSeconds (Duration/between (:playout-events/start-at e)
                                 (:playout-events/finish-at e))))

(defn- breaks-by-block
  "guide-group -> set of filler media-item ids in that block."
  [events]
  (reduce-kv (fn [m g evs] (assoc m g (set (map :playout-events/media-item-id evs))))
             {}
             (group-by :playout-events/guide-group (filler-events events))))

(defn- filler-secs-by-block
  "guide-group -> total filler seconds in that block."
  [events]
  (reduce-kv (fn [m g evs] (assoc m g (reduce + 0 (map event-secs evs))))
             {}
             (group-by :playout-events/guide-group (filler-events events))))

;; ---------------------------------------------------------------------------
;; The test
;; ---------------------------------------------------------------------------

(deftest global-recency-spaces-filler-across-channels
  (if-not @ds-atom
    (is true "skipped (no test database configured)")
    (let [ds @ds-atom]
      ;; Pin wall-clock so both channels get identical block boundaries, making
      ;; their breaks align in time and isolating global recency as the only
      ;; difference between the two builds.
      (with-redefs [t/now (constantly (Instant/parse "2026-06-14T00:00:00Z"))]
        (let [{a :a b :b} (seed! ds)
              n1 (sched/build! ds {:lookahead-hours 8} (playout-db/get-playout ds a))
              ev-a (playout-db/list-events ds a)
              n2 (sched/build! ds {:lookahead-hours 8} (playout-db/get-playout ds b))
              ev-b (playout-db/list-events ds b)
              breaks-a (breaks-by-block ev-a)
              breaks-b (breaks-by-block ev-b)
              aligned  (sort (filter (set (keys breaks-b)) (keys breaks-a)))]

          (testing "the build->pack->persist path runs against real Postgres"
            (is (number? n1)) (is (number? n2))
            (is (pos? n1)) (is (pos? n2))
            (is (some #(= "content" (:playout-events/kind %)) ev-a) "content events persisted")
            (is (seq (filler-events ev-a)) "tail filler persisted on channel A")
            (is (seq (filler-events ev-b)) "tail filler persisted on channel B")
            (is (pos? (count aligned)) "channels share aligned blocks"))

          (testing "tail filler packs each gap nearly full (9-minute gap)"
            (let [fills (vals (filler-secs-by-block ev-a))]
              (is (every? #(<= (- block-seconds content-seconds 60) % (- block-seconds content-seconds))
                          fills)
                  (str "per-block filler should be ~540s: " (vec (sort fills))))))

          (testing "filler varies across a single channel's own breaks"
            (is (>= (count (distinct (vals breaks-a)))
                    (max 2 (quot (count breaks-a) 2)))
                "channel A's breaks should mostly differ from one another"))

          (testing "filler is spaced GLOBALLY across channels"
            ;; Identical seed + identical wall clock => the two channels would
            ;; produce identical filler if recency did nothing. So most aligned
            ;; breaks differing proves cross-channel global recency is working.
            (let [identical (count (filter #(= (breaks-a %) (breaks-b %)) aligned))]
              (is (< identical (* 0.4 (count aligned)))
                  (str "expected channel B to steer away from A; "
                       identical "/" (count aligned)
                       " aligned breaks were identical")))))))))

(defn- max-consecutive-gap-secs
  "Largest dead-air gap (seconds) between consecutive events, sorted by start."
  [events]
  (let [sorted (sort-by :playout-events/start-at events)]
    (->> (map (fn [x y] (.getSeconds (Duration/between (:playout-events/finish-at x)
                                                       (:playout-events/start-at y))))
              sorted (rest sorted))
         (reduce max 0))))

(deftest rebuild-from-now-resets-stale-cursor
  ;; Regression: rebuild-from-now! must wipe the future timeline AND restart the
  ;; build at now. Previously it resumed from the saved cursor (left ~8h ahead by
  ;; the first build), so the regenerated events began at the old horizon and
  ;; left a multi-hour gap between now and there.
  (if-not @ds-atom
    (is true "skipped (no test database configured)")
    (let [ds @ds-atom]
      (with-redefs [t/now (constantly (Instant/parse "2026-06-14T00:00:00Z"))]
        (let [{a :a} (seed! ds)
              t0      (Instant/parse "2026-06-14T00:00:00Z")
              ;; First build with an 8h horizon pushes the saved cursor ~8h ahead.
              _       (sched/build! ds {:lookahead-hours 8} (playout-db/get-playout ds a))
              max1    (reduce (fn [m e] (if (.isAfter (:playout-events/finish-at e) m)
                                          (:playout-events/finish-at e) m))
                              t0 (playout-db/list-events ds a))
              ;; Rebuild from now (resets the cursor) with a 1-day horizon.
              _       (sched/rebuild-from-now! ds a 1)
              ev2     (sort-by :playout-events/start-at (playout-db/list-events ds a))]
          (is (.isAfter max1 (t/add-duration t0 (t/hours->duration 6)))
              "first build extends ~8h ahead, advancing the saved cursor")
          (is (seq ev2))
          (is (not (.isAfter (:playout-events/start-at (first ev2))
                             (t/add-duration t0 (t/hours->duration 1))))
              "rebuilt timeline starts near now, not at the old horizon")
          (is (< (max-consecutive-gap-secs ev2) (* 2 block-seconds))
              "rebuild fills from now; no multi-hour gap left by a stale cursor"))))))

(deftest ensure-horizon-extends-without-wiping-near-term
  ;; Regression: the daily top-up (ensure-horizon! -> build! resume mode) must
  ;; EXTEND the timeline forward while leaving the already-scheduled near-term
  ;; events — and each show's rotation — untouched. Previously a resume build
  ;; deleted everything from now forward and refilled only from the old horizon,
  ;; wiping the near term and leaving a gap.
  (if-not @ds-atom
    (is true "skipped (no test database configured)")
    (let [ds @ds-atom]
      (with-redefs [t/now (constantly (Instant/parse "2026-06-14T00:00:00Z"))]
        (let [{a :a} (seed! ds)
              t0      (Instant/parse "2026-06-14T00:00:00Z")
              ;; First build: an 8h window; the saved cursor lands ~8h ahead.
              _       (sched/build! ds {:lookahead-hours 8} (playout-db/get-playout ds a))
              ev1     (playout-db/list-events ds a)
              ids1    (set (map :playout-events/id ev1))
              max1    (reduce (fn [m e] (if (.isAfter (:playout-events/finish-at e) m)
                                          (:playout-events/finish-at e) m))
                              t0 ev1)
              ;; Daily top-up: extend to a full day from now.
              n       (sched/ensure-horizon! ds a 1)
              ev2     (sort-by :playout-events/start-at (playout-db/list-events ds a))
              ids2    (set (map :playout-events/id ev2))
              max2    (reduce (fn [m e] (if (.isAfter (:playout-events/finish-at e) m)
                                          (:playout-events/finish-at e) m))
                              t0 ev2)]
          (is (pos? n) "extending to a wider horizon generates new events")
          (is (.isAfter max1 (t/add-duration t0 (t/hours->duration 6)))
              "first build reaches ~8h ahead")
          (is (every? ids2 ids1)
              "every near-term event from the first build survives the top-up")
          (is (.isAfter max2 (t/add-duration t0 (t/hours->duration 20)))
              "the timeline is extended out toward the new 24h horizon")
          (is (< (max-consecutive-gap-secs ev2) (* 2 block-seconds))
              "no multi-hour gap opens between the preserved near term and the extension"))))))
