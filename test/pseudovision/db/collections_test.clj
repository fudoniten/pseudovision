(ns pseudovision.db.collections-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.db.collections :as sut]
            [pseudovision.db.core :as db]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- coll
  "Minimal collection map."
  [kind config]
  {:collections/id 1 :collections/kind kind :collections/config config})

(defn- capture-sql!
  "Runs f with db/query stubbed to capture the SQL string and return []."
  [f]
  (let [captured (atom nil)]
    (with-redefs [db/query (fn [_ sql] (reset! captured sql) [])]
      (f))
    @captured))

;; ---------------------------------------------------------------------------
;; smart-tag-clause (tested indirectly via resolve-collection :smart)
;; ---------------------------------------------------------------------------

(deftest smart-no-filters-queries-all-items
  (testing "no tags → no extra WHERE clause beyond media-type"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {:query {:media-type "episode"}})))]
      (is (string? (first sql)))
      (is (.contains (first sql) "media_items"))
      (is (not (.contains (first sql) "metadata_tags"))))))

(deftest smart-include-tags-all-mode
  (testing "match=all emits one EXISTS per tag"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {:query {:include-tags ["comedy" "short"]
                                                                     :match        "all"}})))]
      (let [[sql-text & params] sql]
        ;; Both tags should appear as separate EXISTS subqueries. With
        ;; match=all, 'comedy' and 'short' each get their own EXISTS, so
        ;; count = 2 (the parent :and doesn't add a third).
        (is (= 2 (count (re-seq #"EXISTS" sql-text))))
        ;; Tag strings are bound parameters (not inlined) — see
        ;; exact-tag-exists-subq in db/media.clj.
        (is (some #(= "comedy"   (str %)) params))
        (is (some #(= "short"    (str %)) params))))))

(deftest smart-include-tags-any-mode
  (testing "match=any emits one EXISTS with OR"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {:query {:include-tags ["comedy" "short"]
                                                                     :match        "any"}})))]
      (let [[sql-text & params] sql]
        ;; Single EXISTS subquery covers both tags via an OR.
        (is (= 1 (count (re-seq #"EXISTS" sql-text))))
        (is (some #(= "comedy" (str %)) params))
        (is (some #(= "short"  (str %)) params))))))

(deftest smart-exclude-tags
  (testing "exclude-tags emits NOT EXISTS"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {:query {:exclude-tags ["explicit"]}})))]
      (let [[sql-text & params] sql]
        (is (.contains sql-text "NOT"))
        (is (.contains sql-text "EXISTS"))
        (is (some #(= "explicit" (str %)) params))))))

(deftest smart-include-and-exclude-tags
  (testing "include + exclude both appear"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {:query {:include-tags ["comedy"]
                                                                     :exclude-tags ["explicit"]}})))]
      (let [[sql-text & params] sql]
        (is (.contains sql-text "NOT"))
        (is (some #(= "comedy"   (str %)) params))
        (is (some #(= "explicit" (str %)) params))))))

(deftest smart-match-all-default
  (testing "match defaults to 'all' when not specified"
    (let [sql-default (capture-sql!
                        #(sut/resolve-collection nil (coll "smart" {:query {:include-tags ["a" "b"]}})))
          sql-explicit (capture-sql!
                         #(sut/resolve-collection nil (coll "smart" {:query {:include-tags ["a" "b"]
                                                                              :match        "all"}})))]
      (is (= (first sql-default) (first sql-explicit))))))

(deftest smart-order-by-title
  (testing "order-by=title uses title column"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {:query {:order-by "title"}})))]
      ;; HoneySQL turns `:mi.title` into the table-qualified `mi.title` in
      ;; the ORDER BY clause — check for the column name in context.
      (is (.contains (first sql) "ORDER BY")
          "an ORDER BY clause should be present")
      (is (re-find #"ORDER BY\s+mi\.title" (first sql))
          "title ordering should reference mi.title"))))

(deftest smart-order-by-random
  (testing "order-by=random uses RANDOM()"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {:query {:order-by "random"}})))]
      ;; HoneySQL renders `(case ... "random" [[:random]] ...)` as
      ;; `RANDOM()`. The keyword `random` is a SQL function call.
      (is (re-find #"(?i)ORDER BY\s+RANDOM\(\)" (first sql))
          "random ordering should use RANDOM()"))))

;; ---------------------------------------------------------------------------
;; smart-tag-clause unit tests (via private var)
;; ---------------------------------------------------------------------------

(deftest tag-clause-nil-when-no-tags
  (testing "returns nil when both lists are empty"
    (is (nil? (#'sut/smart-tag-clause "all" [] [])))))

(deftest tag-clause-all-returns-one-exists-per-tag
  (testing "match=all: three tags → three EXISTS clauses"
    (let [clause (#'sut/smart-tag-clause "all" ["a" "b" "c"] [])]
      (is (= :and (first clause)))
      (is (= 3 (count (rest clause))))
      (is (every? #(= :exists (first %)) (rest clause))))))

(deftest tag-clause-any-returns-single-exists
  (testing "match=any: multiple tags → one EXISTS"
    (let [clause (#'sut/smart-tag-clause "any" ["a" "b"] [])]
      (is (= :and (first clause)))
      (is (= 1 (count (rest clause))))
      (is (= :exists (first (second clause)))))))

(deftest tag-clause-exclude-returns-not-exists
  (testing "exclude-tags → [:not [:exists ...]]"
    (let [clause (#'sut/smart-tag-clause "all" [] ["nsfw"])]
      (is (= :and (first clause)))
      (let [[not-form] (rest clause)]
        (is (= :not (first not-form)))
        (is (= :exists (first (second not-form))))))))

(deftest tag-clause-include-and-exclude
  (testing "both include and exclude → two clauses"
    (let [clause (#'sut/smart-tag-clause "all" ["comedy"] ["explicit"])]
      (is (= :and (first clause)))
      (is (= 2 (count (rest clause)))))))

;; ---------------------------------------------------------------------------
;; smart :show-id / :category — the native-schedule content sources (a named
;; series strip, or a genre pool with parent-show tag inheritance) route
;; through pseudovision.db.media instead of the flat query above.
;; ---------------------------------------------------------------------------

(deftest smart-show-id-uses-season-aware-episode-query
  (testing "show-id short-circuits the flat query and joins seasons"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {:query {:show-id 42}})))]
      ;; `list-show-episodes-by-id` (in db/media.clj) is the show-id branch —
      ;; it joins [:media-items :season] aliased `season`, and filters on
      ;; `:mi.kind = 'episode'`. The `episode` literal is bound, not inlined,
      ;; but the alias name `season` and the column reference `season.parent_id`
      ;; appear in the SQL text.
      (let [s (first sql)]
        (is (.contains s "season")
            "show-id branch should reference the season alias")
        (is (.contains s "parent_id")
            "show-id branch should join on parent_id to reach seasons")
        (is (not (.contains s "metadata_tags"))
            "show-id branch does not need a metadata_tags join")))))

(deftest smart-category-uses-tag-inheritance-query
  (testing "category short-circuits the flat query and matches genre: prefix + episode/movie expansion"
    (let [sql (capture-sql!
                #(sut/resolve-collection nil (coll "smart" {:query {:category "sitcom"}})))]
      (let [[sql-text & params] sql]
        ;; The category is matched as the tag name OR with `genre:` prefix,
        ;; so the SQL contains `LOWER(t.name) = ?` four times (bare,
        ;; genre:bare, kebab-case, genre:kebab-case). It also joins
        ;; `metadata_tags` aliased `t`, and uses the show/season/play chain.
        (is (>= (count (re-seq #"LOWER\(t\.name\)" sql-text)) 4)
            "category branch should emit 4 LOWER(t.name) = ? clauses (bare + genre: + kebab)")
        (is (some #(= "sitcom" (str %)) params)
            "category parameter should bind 'sitcom'")
        (is (.contains sql-text "metadata_tags")
            "category branch should reference the metadata_tags table")
        (is (>= (count (re-seq #"media_items" sql-text)) 2)
            "category branch joins the show/season/play chain")))))

(deftest smart-category-with-channel-tag-scopes-to-channel
  (testing "channel-tag adds an extra exact-match EXISTS clause"
    (let [sql       (capture-sql!
                      #(sut/resolve-collection nil (coll "smart" {:query {:category "sitcom"
                                                                           :channel-tag "channel:hua"}})))
          sql-unscoped (capture-sql!
                         #(sut/resolve-collection nil (coll "smart" {:query {:category "sitcom"}})))]
      (let [[sql-text & params]       sql
            [_unscoped-text & _]      sql-unscoped]
        ;; The scoped SQL must reference both the category AND the channel-tag
        ;; in its bound parameters, and have one more EXISTS than unscoped.
        (is (some #(= "sitcom"      (str %)) params)
            "category parameter should bind 'sitcom'")
        (is (some #(= "channel:hua" (str %)) params)
            "channel-tag parameter should bind 'channel:hua'")
        (is (> (count (re-seq #"EXISTS" sql-text))
               (count (re-seq #"EXISTS" (first sql-unscoped))))
            "scoped query should have one more EXISTS than unscoped")))))

;; ---------------------------------------------------------------------------
;; Regression test for live toontown symptom (2026-07-18):
;; The JSONB column reader in db/core.clj parses every JSON value with
;; csk/->kebab-case-keyword, so the config map arriving at resolve-collection
;; is {:query {:category "..." :channel-tag "..."}} — keyword keys, NOT string
;; keys. The pre-fix code did (get q :category) which returned nil, so the
;; :else branch fired and returned every media item in the database
;; (SELECT mi.* FROM media_items mi ...). On the live cluster this manifested
;; as slot 135 of channel 39 (toontown, collection_id=72 =
;; auto:category:adult_content:channel:toontown) airing The Fugitive, X-Files
;; "Ascension", Time Team, Smithsonian's Civil War, retro game ads, and
;; Cartoon Network bumpers — none of which carry channel:toontown.
;;
;; This test feeds resolve-collection a config map shaped EXACTLY like what
;; db/core's column-reader produces (keyword keys), then asserts the category
;; branch fires and emits a filter for both the category and the channel-tag.
;; ---------------------------------------------------------------------------

(deftest smart-resolves-category-with-channel-tag-from-jsonb-round-trip
  (testing "JSONB-round-trip config (keyword keys, as column reader produces) hits the category branch and emits both category and channel-tag filters"
    (let [coll   {:collections/id 72
                  :collections/kind "smart"
                  :collections/config {:query {:category "adult_content"
                                               :channel-tag "channel:toontown"}}}
          captured (atom [])]
      (with-redefs [db/query (fn [_ sql] (swap! captured conj sql) [])]
        (sut/resolve-collection nil coll))
      (let [sql      (first @captured)
            sql-text (or (first sql) "")
            params   (vec (rest sql))]
        (is (some? sql) "category branch should issue a SQL query")
        (is (.contains sql-text "LOWER(t.name)")
            "category branch should match the tag via LOWER(t.name) (case-insensitive)")
        (is (some #(= "adult_content" (str %)) params)
            "category parameter should bind 'adult_content'")
        (is (some #(= "channel:toontown" (str %)) params)
            "channel-tag parameter should bind 'channel:toontown' to scope the category to one channel")
        ;; The :else branch's query is `SELECT mi.*, mv.duration FROM
        ;; media_items mi LEFT JOIN media_versions mv ON mv.media_item_id =
        ;; mi.id ORDER BY mi.id` — it does NOT touch media_items twice, so a
        ;; count > 1 of media_items is a strong signal the category branch
        ;; ran (which joins top-level + season + play items).
        (is (> (count (re-seq #"media_items" sql-text)) 1)
            "category branch joins the show/season/play chain (more than one media_items reference)")))))

(deftest smart-show-id-from-jsonb-round-trip
  (testing "JSONB-round-trip config (keyword keys) hits the show-id branch"
    (let [coll {:collections/id 42
                :collections/kind "smart"
                :collections/config {:query {:show-id 7}}}
          captured (atom nil)]
      (with-redefs [db/query (fn [_ sql] (reset! captured sql) [])]
        (sut/resolve-collection nil coll))
      (let [s (first @captured)]
        (is (.contains s "season")
            "show-id branch should join the seasons table")
        (is (not (.contains s "metadata_tags"))
            "show-id branch does not need a metadata_tags join")))))

;; ---------------------------------------------------------------------------
;; :playlist and :multi regression — same JSONB-key mismatch class as :smart.
;; csk/->kebab-case-keyword kebabs underscores, so the JSON key "content_id"
;; arrives as :content-id (not :content_id, and not "content_id").
;; ---------------------------------------------------------------------------

(deftest playlist-resolves-from-jsonb-round-trip
  (testing ":playlist with keyword-key config hits the playlist branch and recurses"
    (let [child-rows (atom [{:collections/id 99 :collections/kind "manual"
                             :collections/config {}}])
          coll {:collections/id 73
                :collections/kind "playlist"
                :collections/config {:items [{:content-id 1 :content-kind "collection"}
                                             {:content-id 2 :content-kind "collection"}]}}
          seen-query-one (atom [])]
      (with-redefs [db/query-one (fn [_ sql]
                                   (swap! seen-query-one conj (first sql))
                                   (first @child-rows))
                    db/query      (fn [_ _] [])]
        ;; First iteration consumes the first child; second iteration gets nil.
        ;; Either way, the test asserts (a) db/query-one was called for each
        ;; item (proving the destructure worked) and (b) no exception thrown.
        (sut/resolve-collection nil coll))
      (is (= 2 (count @seen-query-one))
          "playlist should call db/query-one once per :items entry"))))

(deftest multi-resolves-from-jsonb-round-trip
  (testing ":multi with keyword-key config hits the multi branch and recurses"
    (let [coll {:collections/id 74
                :collections/kind "multi"
                :collections/config {:members [{:collection-id 1}
                                              {:collection-id 2}]}}
          seen-query-one (atom [])]
      (with-redefs [db/query-one (fn [_ sql]
                                   (swap! seen-query-one conj (first sql))
                                   nil)]
        (sut/resolve-collection nil coll))
      (is (= 2 (count @seen-query-one))
          "multi should call db/query-one once per :members entry"))))
