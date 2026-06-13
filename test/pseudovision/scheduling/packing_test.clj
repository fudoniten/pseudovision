(ns pseudovision.scheduling.packing-test
  "Unit tests for the filler bin-packer plus a simulated 'sitcom day' harness
   that measures break variety the way a real channel would experience it."
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.scheduling.packing :as pack])
  (:import [java.time Duration]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- item [id secs]
  {:media-items/id id :media-versions/duration (Duration/ofSeconds secs)})

;; A varied filler library: bumpers, promos and shorts from 30s to 5min.
(def ^:private library
  (mapv (fn [[id s]] (item id s))
        [[:b30 30] [:b45 45] [:b60 60] [:b75 75] [:b90 90]
         [:p120 120] [:p150 150] [:p180 180] [:p210 210] [:p240 240]
         [:s300 300] [:s270 270] [:s200 200] [:s100 100] [:s50 50]
         [:m135 135] [:m165 165] [:m225 225] [:m285 285] [:m55 55]]))

(def ^:private gap-9m (Duration/ofMinutes 9))   ; the recurring sitcom break

(defn- total-secs [items]
  (.getSeconds (pack/duration-of items)))

;; ---------------------------------------------------------------------------
;; Core guarantees
;; ---------------------------------------------------------------------------

(deftest never-exceeds-target
  (testing "the packed playlist never runs longer than the gap"
    (doseq [seed (range 50)]
      (let [pl (pack/pack gap-9m library :seed seed)]
        (is (<= (total-secs pl) 540)
            (str "seed " seed " overfilled: " (total-secs pl)))))))

(deftest deterministic-for-same-seed
  (testing "same inputs + seed reproduce the same playlist (rebuild stability)"
    (let [a (pack/pack gap-9m library :seed 12345)
          b (pack/pack gap-9m library :seed 12345)]
      (is (= (mapv :media-items/id a) (mapv :media-items/id b))))))

(deftest different-seeds-give-different-breaks
  (testing "varying the seed yields a variety of distinct playlists"
    (let [playlists (for [seed (range 40)]
                      (mapv :media-items/id (pack/pack gap-9m library :seed seed)))]
      (is (> (count (distinct playlists)) 20)
          "expected many distinct breaks across 40 seeds"))))

(deftest fills-the-gap-tightly
  (testing "leftover is smaller than the shortest library item on average"
    (let [shortest  30
          leftovers (for [seed (range 50)]
                      (- 540 (total-secs (pack/pack gap-9m library :seed seed))))
          avg       (/ (reduce + leftovers) (double (count leftovers)))]
      (is (every? #(< % shortest) leftovers)
          "every break should be filled to within one short of the gap")
      (is (< avg 20) (str "average leftover too high: " avg "s")))))

(deftest no-repeats-within-a-break-by-default
  (testing "an item never appears twice in the same playlist by default"
    (doseq [seed (range 30)]
      (let [ids (mapv :media-items/id (pack/pack gap-9m library :seed seed))]
        (is (= (count ids) (count (distinct ids))))))))

(deftest allow-repeats-permits-reuse
  (testing "with :allow-repeats? a tiny library can still fill a large gap"
    (let [tiny [(item :only 60)]
          ids  (mapv :media-items/id
                     (pack/pack (Duration/ofMinutes 5) tiny
                                :seed 1 :allow-repeats? true))]
      (is (= 5 (count ids)) "five 60s plays fill 5 minutes")
      (is (every? #{:only} ids)))))

;; ---------------------------------------------------------------------------
;; Recency steers selection
;; ---------------------------------------------------------------------------

(deftest recency-penalty-avoids-recent-items
  (testing "heavily-penalised items are dropped when alternatives exist"
    ;; Penalise every 'p*' and 's*' item hard; the packer should lean on the
    ;; un-penalised items instead.
    (let [recency (into {} (for [it library
                                 :let [id (:media-items/id it)]
                                 :when (#{\p \s} (first (name id)))]
                             [id 1000]))
          counts  (frequencies
                   (for [seed (range 60)
                         id   (mapv :media-items/id
                                    (pack/pack gap-9m library :seed seed :recency recency))]
                     (first (name id))))]
      ;; Penalised families should be far rarer than the un-penalised ones.
      (is (< (+ (get counts \p 0) (get counts \s 0))
             (+ (get counts \b 0) (get counts \m 0)))
          (str "recency did not steer away from penalised items: " counts)))))

;; ---------------------------------------------------------------------------
;; Degenerate inputs
;; ---------------------------------------------------------------------------

(deftest empty-and-impossible-inputs
  (testing "empty pool, zero target, and all-too-long items return no playlist"
    (is (= [] (pack/pack gap-9m [] :seed 1)))
    (is (= [] (pack/pack Duration/ZERO library :seed 1)))
    (is (= [] (pack/pack (Duration/ofSeconds 10) library :seed 1))
        "nothing fits a 10s gap when the shortest item is 30s")))

;; ---------------------------------------------------------------------------
;; Simulated sitcom day — the scenario that motivated this
;; ---------------------------------------------------------------------------

(defn- update-recency
  "Sliding-window recency model used only by the simulation: an item's penalty
   is how many of the last `window` breaks it appeared in."
  [history window]
  (let [recent (take-last window history)]
    (frequencies (apply concat recent))))

(defn simulate-day
  "Plays `n` identical gaps back-to-back, updating recency between breaks the
   way the scheduler will.  Returns a vector of playlists (each a vec of ids)."
  [n & {:keys [window] :or {window 6}}]
  (loop [i 0, history [], out []]
    (if (= i n)
      out
      (let [recency (update-recency history window)
            ids     (mapv :media-items/id
                          (pack/pack gap-9m library :seed i :recency recency))]
        (recur (inc i) (conj history ids) (conj out ids))))))

(def ^:private secs-by-id
  (into {} (map (juxt :media-items/id
                      #(.getSeconds ^Duration (:media-versions/duration %)))
                library)))

(defn- break-secs [ids]
  (reduce + 0 (map secs-by-id ids)))

(deftest sitcom-day-is-varied-and-well-filled
  (testing "48 consecutive 9-minute breaks rotate content instead of repeating"
    (let [day             (simulate-day 48)
          leftovers       (map #(- 540 (break-secs %)) day)
          leads           (map first day)
          distinct-breaks (count (distinct day))
          lead-freq       (frequencies leads)
          top-lead        (apply max (vals lead-freq))]
      ;; Variety: most breaks should be distinct...
      (is (>= distinct-breaks 36)
          (str "only " distinct-breaks "/48 breaks were distinct"))
      ;; ...and no single item should headline more than ~30% of breaks.
      (is (<= top-lead 15)
          (str "one item led " top-lead "/48 breaks: " lead-freq))
      ;; Fill quality stays good throughout.
      (is (every? #(< % 30) leftovers)
          (str "some breaks left too much dead air: " (vec leftovers))))))
