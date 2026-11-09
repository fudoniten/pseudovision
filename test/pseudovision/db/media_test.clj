(ns pseudovision.db.media-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.db.media :as sut]
            [pseudovision.db.core :as db]))

;; ---------------------------------------------------------------------------
;; list-media-items SQL generation
;; ---------------------------------------------------------------------------

(defn- capture-sql
  "Calls list-media-items with the given opts, returning the formatted SQL
   vector that would be sent to the database."
  [opts]
  (let [captured (atom nil)]
    (with-redefs [db/query (fn [_ sql] (reset! captured sql) [])]
      (sut/list-media-items nil 26 opts))
    @captured))

(deftest list-media-items-default-attrs-select
  (testing "default attrs produce a well-formed SELECT with each column spread"
    (let [[sql] (capture-sql {:limit 50 :offset 0})]
      ;; Each column must appear as its own select expression; the title is
      ;; aliased to `name`. Regression for a syntax error caused by passing the
      ;; column vector as a single arg to h/select instead of spreading it.
      (is (re-find #"(?i)SELECT\s+mi\.id,\s*m\.title\s+AS\s+name" sql)))))

(deftest list-media-items-joins-metadata-for-name
  (testing "requesting a metadata-backed attr adds the metadata LEFT JOIN"
    (let [[sql] (capture-sql {:attrs [:id :name] :limit 50 :offset 0})]
      (is (re-find #"(?i)LEFT JOIN metadata" sql)))))

(deftest list-media-items-search-adds-ilike-filter
  (testing "a search term joins metadata and adds a case-insensitive title filter"
    (let [[sql & params] (capture-sql {:search "blade" :limit 50 :offset 0})]
      (is (re-find #"(?i)LEFT JOIN metadata" sql))
      (is (re-find #"(?i)m\.title ILIKE \?" sql))
      (is (some #{"%blade%"} params)))))

(deftest list-media-items-search-does-not-double-join-metadata
  (testing "search + a metadata-backed attr joins metadata exactly once"
    (let [[sql] (capture-sql {:search "blade" :attrs [:id :name] :limit 50 :offset 0})
          joins (count (re-seq #"(?i)LEFT JOIN metadata" sql))]
      (is (= 1 joins) "metadata must be joined exactly once"))))

(deftest list-media-items-blank-search-is-ignored
  (testing "a blank/whitespace search term adds no filter or join"
    (let [[sql] (capture-sql {:search "   " :limit 50 :offset 0})]
      (is (not (re-find #"(?i)ILIKE" sql))))))

(deftest list-media-items-search-escapes-like-wildcards
  (testing "LIKE wildcards in the search term are escaped to literals"
    (let [[_ & params] (capture-sql {:search "50%_x" :limit 50 :offset 0})]
      (is (some #{"%50\\%\\_x%"} params)))))

;; ---------------------------------------------------------------------------
;; get-media-item / resolve-media-item-id reference matching
;; ---------------------------------------------------------------------------

(defn- capture-query-one
  "Invokes `f` with a stubbed db/query-one that records the formatted SQL
   vector, returning that vector."
  [f]
  (let [captured (atom nil)]
    (with-redefs [db/query-one (fn [_ sql] (reset! captured sql) nil)]
      (f))
    @captured))

(deftest count-media-items-applies-search-filter
  (testing "count uses the same search filter so the total reflects matches only"
    (let [[sql & params] (capture-query-one #(sut/count-media-items nil 26 {:search "blade"}))]
      (is (re-find #"(?i)SELECT COUNT" sql))
      (is (re-find #"(?i)m\.title ILIKE \?" sql))
      (is (some #{"%blade%"} params)))))

(deftest get-media-item-by-integer-id
  (testing "an integer ref matches either the internal id or remote_key"
    (let [[sql & params] (capture-query-one #(sut/get-media-item nil 42))]
      (is (re-find #"(?i)mi\.id = \?\)?\s*OR\s*\(?mi\.remote_key = \?" sql))
      (is (= [42 "42"] params)))))

(deftest get-media-item-by-numeric-string-id
  (testing "a numeric string ref also matches both columns"
    (let [[sql & params] (capture-query-one #(sut/get-media-item nil "42"))]
      (is (re-find #"(?i)mi\.id = \?\)?\s*OR\s*\(?mi\.remote_key = \?" sql))
      (is (= [42 "42"] params)))))

(deftest get-media-item-by-remote-key
  (testing "a non-numeric ref (e.g. a Jellyfin id) matches remote_key only"
    (let [jf-id "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
          [sql & params] (capture-query-one #(sut/get-media-item nil jf-id))]
      (is (re-find #"(?i)mi\.remote_key = \?" sql))
      (is (not (re-find #"(?i)mi\.id = \?" sql)))
      (is (= [jf-id] params)))))

(deftest get-media-item-out-of-range-numeric-ref
  (testing "an all-numeric ref too large for the SERIAL id matches remote_key only"
    (let [big "12345678901234567890123456789012"
          [sql & params] (capture-query-one #(sut/get-media-item nil big))]
      (is (not (re-find #"(?i)mi\.id = \?" sql)))
      (is (= [big] params)))))

(deftest resolve-media-item-id-uses-ref-match
  (testing "resolve-media-item-id selects mi.id matching id or remote_key"
    (let [[sql & params] (capture-query-one #(sut/resolve-media-item-id nil "abc"))]
      (is (re-find #"(?i)SELECT mi\.id" sql))
      (is (= ["abc"] params)))))

;; ---------------------------------------------------------------------------
;; list-media-streams-for-item
;; ---------------------------------------------------------------------------

(defn- capture-query-unqualified
  [f]
  (let [captured (atom nil)]
    (with-redefs [db/query-unqualified (fn [_ sql] (reset! captured sql) [])]
      (f))
    @captured))

(deftest list-media-streams-for-item-joins-versions-and-streams
  (testing "joins media_items -> media_versions -> media_streams and pins to the item's primary version"
    (let [[sql & params] (capture-query-unqualified #(sut/list-media-streams-for-item nil 42))]
      (is (re-find #"(?i)JOIN media_versions" sql))
      (is (re-find #"(?i)JOIN media_streams" sql))
      (is (re-find #"(?i)ORDER BY pv\.duration DESC, pv\.id DESC LIMIT" sql)
          "pins to the item's longest media version")
      (is (re-find #"(?i)ORDER BY ms\.stream_index" sql))
      (is (= [42 "42" 1] params)))))

;; ---------------------------------------------------------------------------
;; create-collection! / update-collection! — must hand db/execute-one! a
;; formatted [sql & params] vector, never the raw HoneySQL map. Regression
;; for a bug where the unformatted map was passed straight through: next.jdbc
;; has no built-in HoneySQL support, so `execute-one!` threw
;; "sql-params should be a vector containing a SQL string and any parameters"
;; on every call once an earlier, unrelated destructure crash stopped masking
;; it.
;; ---------------------------------------------------------------------------

(defn- capture-execute-one!
  "Invokes `f` with a stubbed db/execute-one! that records the sql-params
   argument it was given, returning a stub row so callers don't NPE."
  [f]
  (let [captured (atom nil)]
    (with-redefs [db/execute-one! (fn [_ sql-params] (reset! captured sql-params) {:id 1})]
      (f))
    @captured))

(deftest create-collection-formats-sql-before-execute
  (testing "db/execute-one! receives a formatted [sql & params] vector, not a raw HoneySQL map"
    (let [sql-params (capture-execute-one!
                       #(sut/create-collection! nil {:kind "manual" :name "test"}))]
      (is (vector? sql-params) "sql-params must be a vector, not a HoneySQL map")
      (is (string? (first sql-params)))
      (is (re-find #"(?i)INSERT INTO collections" (first sql-params)))
      (is (re-find #"(?i)RETURNING \*" (first sql-params))))))

(deftest update-collection-formats-sql-before-execute
  (testing "db/execute-one! receives a formatted [sql & params] vector, not a raw HoneySQL map"
    (let [sql-params (capture-execute-one!
                       #(sut/update-collection! nil 1 {:name "renamed"}))]
      (is (vector? sql-params) "sql-params must be a vector, not a HoneySQL map")
      (is (string? (first sql-params)))
      (is (re-find #"(?i)UPDATE collections" (first sql-params)))
      (is (re-find #"(?i)RETURNING \*" (first sql-params))))))

;; ---------------------------------------------------------------------------
;; resolve-playable-by-tag category matching
;; ---------------------------------------------------------------------------

(defn- capture-resolve-by-tag-sql [category]
  (let [captured (atom nil)]
    (with-redefs [db/query (fn [_ sql] (reset! captured sql) [])]
      (sut/resolve-playable-by-tag nil category))
    @captured))

(deftest resolve-playable-by-tag-ignores-separator-style
  (testing "a hyphenated category is matched against a separator-normalised tag name"
    (let [[sql & params] (capture-resolve-by-tag-sql "dark-comedy")]
      (is (re-find #"(?i)REGEXP_REPLACE\(LOWER\(et\.name\)" sql))
      (is (some #{"dark-comedy"} params))
      (is (some #{"genre:dark-comedy"} params))))
  (testing "an underscored category reduces to the same key"
    (let [[_ & params] (capture-resolve-by-tag-sql "Sci_Fi & Fantasy")]
      (is (some #{"sci-fi-and-fantasy"} params))
      (is (some #{"genre:sci-fi-and-fantasy"} params))
      ;; the verbatim lowercase form is still tried
      (is (some #{"sci_fi & fantasy"} params)))))

;; ---------------------------------------------------------------------------
;; Tag inheritance and primary versions
;; ---------------------------------------------------------------------------

(defn- capture-query [f]
  (let [captured (atom nil)]
    (with-redefs [db/query (fn [_ sql] (reset! captured sql) [])]
      (f))
    @captured))

(deftest channel-tag-resolver-matches-inherited-tags
  (testing "the channel tag is matched against each playable item's
            effective tags (own + season + show), not one level only"
    (let [[sql & params] (capture-query #(sut/resolve-playable-by-channel-tag nil "channel:Spectrum"))]
      (is (re-find #"FROM media_item_effective_tags AS et WHERE \(et\.media_item_id = play\.id\)" sql))
      (is (some #{"channel:spectrum"} params) "matched case-insensitively"))))

(deftest require-tags-match-inherited-tags
  (let [[sql & params] (capture-query #(sut/resolve-playable-by-tag nil "sitcom" :require-tags ["channel:spectrum"]))]
    (is (= 2 (count (re-seq #"media_item_effective_tags" sql)))
        "category and channel tag are both checked against effective tags")
    (is (some #{"channel:spectrum"} params))))

(deftest resolvers-use-one-version-per-item
  (testing "every playable resolver joins only the item's longest version, so
            a short preview version can't enter a pool as a second item"
    (doseq [[label f] [["by-tag"         #(sut/resolve-playable-by-tag nil "sitcom")]
                       ["by-channel-tag" #(sut/resolve-playable-by-channel-tag nil "channel:spectrum")]
                       ["show episodes"  #(sut/list-show-episodes-by-id nil 12)]]]
      (let [[sql] (capture-query f)]
        (is (re-find #"ON mvp?\.id = \(SELECT pv\.id FROM media_versions AS pv WHERE pv\.media_item_id = (play|mi)\.id ORDER BY pv\.duration DESC, pv\.id DESC LIMIT \?\)" sql)
            label)))))

(deftest effective-tags-by-item-groups-tags
  (let [captured (atom nil)]
    (with-redefs [db/query-unqualified (fn [_ sql]
                                         (reset! captured sql)
                                         [{:media-item-id 1 :name "channel:spectrum"}
                                          {:media-item-id 1 :name "genre:comedy"}
                                          {:media-item-id 2 :name "christmas"}])]
      (is (= {1 #{"channel:spectrum" "genre:comedy"} 2 #{"christmas"}}
             (sut/effective-tags-by-item nil [1 2 nil 1])))
      (is (re-find #"FROM media_item_effective_tags" (first @captured)))
      (is (= [1 2] (vec (rest @captured))) "ids deduplicated, nils dropped"))))
