(ns pseudovision.scheduling.cursor-rewind-test
  "A reset rebuild rewinds each series to the episode after the last one that
   aired, instead of restarting it at episode 1 or skipping what was built but
   never aired."
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [pseudovision.scheduling.cursor :as cursor]
            [pseudovision.scheduling.enumerators :as enum])
  (:import [java.time Instant]))

(def ^:private pool (mapv (fn [id] {:media-items/id id}) (range 1 51)))

(defn- saved-at [n order]
  ;; a cursor whose enumerator has drawn n items (the old horizon)
  (let [e (loop [e (enum/make-enumerator pool order {:seed 3}), i 0]
            (if (= i n) e (recur (second (enum/next-item e)) (inc i))))]
    (cursor/save-enumerator (cursor/init (Instant/now)) "collection:7" e)))

(defn- next-id [cur order]
  (:media-items/id (first (enum/next-item (cursor/get-enumerator cur "collection:7" pool order)))))

(deftest reset-rewinds-a-series-to-what-aired
  (let [cur     (saved-at 40 :chronological)          ; built through episode 40
        aired   {12 [(Instant/parse "2026-11-12T20:00:00Z")]
                 13 [(Instant/parse "2026-11-13T20:00:00Z")]
                 99 [(Instant/parse "2026-11-13T21:00:00Z")]} ; not in this pool
        rewound (-> cur cursor/mark-rewind (cursor/with-rewind-airings aired))]
    (is (= 41 (next-id cur :chronological)) "unmarked: resumes at the old edge")
    (is (= 14 (next-id rewound :chronological)) "marked: resumes after the last aired episode")
    (testing "nothing from the pool aired: the saved position is kept, never episode 1"
      (is (= 41 (next-id (-> cur cursor/mark-rewind (cursor/with-rewind-airings {}))
                         :chronological))))))

(deftest rewind-markers-are-not-saved
  (let [cur  (-> (saved-at 5 :chronological) cursor/mark-rewind
                 (cursor/with-rewind-airings {1 [(Instant/now)]}))
        back (json/parse-string (.getValue (cursor/->json cur)) true)]
    (is (not-any? :rewind (vals (:enumerator-states back))))
    (is (not-any? #(re-find #"rewind" (name %)) (keys back))
        "the airings used to rewind stay out of the stored cursor")))
