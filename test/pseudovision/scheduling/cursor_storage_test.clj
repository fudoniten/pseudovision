(ns pseudovision.scheduling.cursor-storage-test
  "Enumerator positions survive a round trip through the playouts.cursor JSONB
   column. The column reader turns every JSON object key into a kebab-case
   keyword, which mangled \"collection:7\" into :collection:-7, so no saved
   position was ever found and every build restarted every series."
  (:require [clojure.test :refer [deftest is testing]]
            [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [pseudovision.scheduling.cursor :as cursor]
            [pseudovision.scheduling.enumerators :as enum])
  (:import [java.time Instant]))

(def ^:private pool (mapv (fn [id] {:media-items/id id}) (range 1 11)))

(defn- as-column-reader-returns
  "What next.jdbc hands back for the stored cursor (see db.core's PGobject
   column reader)."
  [pg]
  (json/parse-string (.getValue pg) csk/->kebab-case-keyword))

(defn- drawn [n]
  (loop [e (enum/make-enumerator pool :chronological {}), i 0]
    (if (= i n) e (recur (second (enum/next-item e)) (inc i)))))

(deftest positions-survive-the-jsonb-column
  (let [cur  (-> (cursor/init (Instant/parse "2026-11-13T00:00:00Z"))
                 (cursor/save-enumerator "collection:7" (drawn 4))
                 (cursor/save-enumerator "channel-catalog:12" (drawn 2))
                 (cursor/save-enumerator "filler:tail:42" (drawn 1)))
        back (cursor/<-json (as-column-reader-returns (cursor/->json cur)))]
    (is (= #{"collection:7" "channel-catalog:12" "filler:tail:42"}
           (set (keys (:enumerator-states back)))))
    (is (= 5 (:media-items/id (first (enum/next-item
                                      (cursor/get-enumerator back "collection:7" pool :chronological)))))
        "the series resumes at its fifth episode")))

(deftest cursors-stored-the-old-way-are-still-read
  (testing "a map of states keyed by mangled keywords (the old format)"
    (let [old  {:next-start "2026-11-13T00:00:00Z"
                :enumerator-states {:collection:-7 {:index 4 :seed 0 :playback-order "chronological"}
                                    :filler:tail:-42 {:index 1 :seed 0 :playback-order "random"}}}
          back (cursor/<-json old)]
      (is (= #{"collection:7" "filler:tail:42"} (set (keys (:enumerator-states back))))))))
