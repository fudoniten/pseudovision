(ns pseudovision.http.api.collections-test
  "Regression tests for the create-collection path. The 2026-07-13
  incident: a POST to /api/media/collections with a populated config
  (`config: {query: {category: ..., channel-tag: ...}}`) silently
  stored `config: {}` in the DB because the `CollectionCreate` schema
  had `[:config [:map]]` — a closed map with no entries, which reitit-
  malli's request-body coercion interpreted as \"drop every key in
  :config\". Every smart collection on the live cluster had this
  shape, so the random pools backing the playout picker were empty
  and the closest-runtime fallback was repeatedly picking the same
  handful of items."
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [cheshire.core     :as json]
            [honey.sql         :as sql]
            [pseudovision.db.core     :as db-core]
            [pseudovision.db.media    :as db-media]
            [pseudovision.http.core   :as http]))

(defn- make-test-handler []
  (http/make-handler {:db nil :ffmpeg {} :media {} :scheduling {}}))

(defn- parse-json-body [resp]
  (let [parsed (some-> resp :body (json/parse-string true))]
    (if (sequential? parsed) (vec parsed) parsed)))

(defn- post-json [path body]
  (-> (mock/request :post path)
      (mock/content-type "application/json")
      (mock/body (json/generate-string body))))

;; We capture the attrs map the handler hands to db/create-collection! so
;; we can assert that the :config keys survive request-body coercion.
(def ^:dynamic *captured-create-args* nil)

(deftest create-smart-collection-with-config-keeps-config-keys
  (testing "POST /api/media/collections with a populated :config
            passes the inner keys through to create-collection! (the
            2026-07-13 incident: :config was being stripped to {} here)"
    (binding [*captured-create-args* (atom nil)]
      (with-redefs [pseudovision.db.media/create-collection!
                    (fn [_ds attrs]
                      (reset! *captured-create-args* attrs)
                      {:collections/id 42
                       :collections/kind "smart"
                       :collections/name (:name attrs)
                       :collections/config (:config attrs)})]
        (let [resp ((make-test-handler)
                    (post-json
                     "/api/media/collections"
                     {:name "auto:test:enigma"
                      :kind "smart"
                      :config {:query {:category "mystery"
                                       :channel-tag "channel:enigma"}}}))
              body (parse-json-body resp)
              captured @*captured-create-args*]
          (is (= 201 (:status resp)))
          (is (some? captured) "create-collection! was called")
          (is (map? (:config captured))
              "config arrives at db layer as a map, not nil")
          (is (= "mystery"   (get-in captured [:config :query :category]))
              "config.query.category survives request-body coercion")
          (is (= "channel:enigma"
                 (get-in captured [:config :query :channel-tag]))
              "config.query.channel-tag survives request-body coercion")
          (is (= "mystery"   (get-in body [:config :query :category]))
              "response body echoes the full config (not {})"))))))

(deftest create-collection-without-config-still-works
  (testing "POST /api/media/collections with no :config key still succeeds
            (back-compat: the old endpoint accepted kind-only bodies)"
    (binding [*captured-create-args* (atom nil)]
      (with-redefs [pseudovision.db.media/create-collection!
                    (fn [_ds attrs]
                      (reset! *captured-create-args* attrs)
                      {:collections/id 43
                       :collections/kind "manual"
                       :collections/name (:name attrs)
                       :collections/config (:config attrs {})})]
        (let [resp ((make-test-handler)
                    (post-json
                     "/api/media/collections"
                     {:name "manual-list"
                      :kind "manual"}))
              body (parse-json-body resp)]
          (is (= 201 (:status resp)))
          (is (= {} (:config body))
              "absent config serializes as {} in the response"))))))

(deftest create-smart-collection-with-deeper-config-tree
  (testing "A smart collection config with multiple sibling keys
            (not just :query) all survive the coercion"
    (binding [*captured-create-args* (atom nil)]
      (with-redefs [pseudovision.db.media/create-collection!
                    (fn [_ds attrs]
                      (reset! *captured-create-args* attrs)
                      {:collections/id 44
                       :collections/kind "smart"
                       :collections/name (:name attrs)
                       :collections/config (:config attrs)})]
        (let [resp ((make-test-handler)
                    (post-json
                     "/api/media/collections"
                     {:name "auto:multi:enigma"
                      :kind "smart"
                      :config {:query      {:category "mystery"
                                            :channel-tag "channel:enigma"}
                               :match      "all"
                               :order-by   "random"
                               :media-type "movie"
                               :foo        "bar"
                               :nested     {:a {:b {:c 42}}}}}))
              captured @*captured-create-args*]
          (is (= 201 (:status resp)))
          ;; Every key in the source :config must be present at db entry.
          (is (= "all"    (get-in captured [:config :match]))
              "sibling :match key survives")
          (is (= "random" (get-in captured [:config :order-by]))
              "sibling :order-by key survives")
          (is (= "movie"  (get-in captured [:config :media-type]))
              "sibling :media-type key survives")
          (is (= "bar"    (get-in captured [:config :foo]))
              "arbitrary user-added key survives")
          (is (= 42       (get-in captured [:config :nested :a :b :c]))
              "deeply nested value survives"))))))

(deftest create-collection-rejects-missing-name
  (testing "POST /api/media/collections with no :name returns 400
            (the request body schema still requires :name)"
    (let [resp ((make-test-handler)
                (post-json
                 "/api/media/collections"
                 {:kind       "smart"
                  :config     {:query {:category "mystery"}}}))
          body (parse-json-body resp)]
      (is (= 400 (:status resp))
          "missing :name triggers reitit-malli request-body coercion failure")
      (is (re-find #"Request coercion failed" (:error body))
          "the body says coercion failed (the schema's :name :string is required)"))))

(deftest create-collection-rejects-nested-config-as-string
  (testing "POST with config as a JSON string (instead of an object) is
            still rejected — the schema is :map, not :string"
    (let [resp ((make-test-handler)
                (post-json
                 "/api/media/collections"
                 {:name   "bad-shape"
                  :kind   "smart"
                  :config "{\"query\":{\"category\":\"mystery\"}}"}))
          body (parse-json-body resp)]
      (is (= 400 (:status resp))
          "config-as-string is rejected (was previously silently coerced to {})")
      (is (re-find #"Request coercion failed" (:error body))
          "the body says coercion failed (the schema's :config is [:map], not :string)"))))

;; ---------------------------------------------------------------------------
;; SQL-shape regression: every INSERT/UPDATE that returns a row must end in
;; (h/returning :*) so the next.jdbc driver returns the full row including
;; the jsonb :config column. Without it, the driver falls back to
;; Statement.RETURN_GENERATED_KEYS which silently returns an empty/partial
;; row, and the response shows `config: {}` (the column DEFAULT). This is
;; the same class of bug as `upsert-media-item!` (fixed 2026-07-09 for the
;; ON CONFLICT DO UPDATE path). These tests capture the SQL that
;; `db-media/create-collection!` and `db-media/update-collection!` pass to
;; `db-core/execute-one!` and assert that the SQL contains `RETURNING *`.
;; ---------------------------------------------------------------------------

(defn- capture-execute-one-sql
  "Run `f` with `db-core/execute-one!` redefined to capture the first
   argument (a HoneySQL map) into a dynamic var, then format it via
   `sql/format` to get the SQL string the driver would actually see.
   Returns the SQL string."
  [f]
  (let [captured (atom nil)]
    (with-redefs [db-core/execute-one!
                  (fn [_ds honey-map _opts]
                    (reset! captured honey-map)
                    ;; Mimic the real builder-fn + return-keys so anything
                    ;; downstream that touches :id/:config behaves
                    ;; realistically, but the test only inspects the SQL.
                    {:id 0 :kind "smart" :name "stub" :config {}})]
      (f)
      (let [hm @captured]
        (assert (some? hm) "execute-one! was not called")
        (first (sql/format hm))))))

(deftest create-collection-insert-has-returning-star
  (testing "db-media/create-collection! emits a SQL string with
            'RETURNING *' so next.jdbc returns the full row (including
            :config). Without the (h/returning :*) clause the driver
            falls back to RETURN_GENERATED_KEYS and the response shows
            config: {} — the 2026-07-13 incident."
    (let [sql-str (capture-execute-one-sql
                   #(db-media/create-collection!
                     nil
                     {:name   "auto:test:enigma"
                      :kind   "smart"
                      :config {:query {:category "mystery"
                                       :channel-tag "channel:enigma"}}}))]
      (is (string? sql-str) "sql/format produced a string")
      (is (re-find #"(?i)\bRETURNING\s+\*" sql-str)
          "the INSERT contains RETURNING * — without it the jsonb :config
           column is silently absent from the result row, and the response
           shows config: {} (the column DEFAULT)."))))

(deftest update-collection-update-has-returning-star
  (testing "db-media/update-collection! emits a SQL string with
            'RETURNING *' for the same reason as create-collection!"
    (let [sql-str (capture-execute-one-sql
                   #(db-media/update-collection!
                     nil
                     42
                     {:config {:query {:category "drama"
                                       :channel-tag "channel:enigma"}}}))]
      (is (re-find #"(?i)\bRETURNING\s+\*" sql-str)
          "the UPDATE contains RETURNING * — same class of bug as
           create-collection! if missing."))))

;; ---------------------------------------------------------------------------
;; Response-coercion regression: the `Collection` response schema must allow
;; arbitrary :config keys, not just a closed `[:map]`. Verified 2026-07-14:
;; PR #139 restored the SQL path so collections are now STORED with the real
;; config (e.g. {"query": {"category": "mystery", "channel_tag": "..."}}),
;; but the API still returned `config: {}` for the same closed-map coercion
;; reason that PR #133 fixed on the request side. The fix widens the response
;; schema to `[:map {:closed false}]` (mirror of CollectionCreate). These
;; tests stub db-media/get-collection to return a row with a populated
;; config and assert the round-tripped response body preserves every key.
;; ---------------------------------------------------------------------------

(deftest get-collection-response-preserves-config-keys
  (testing "GET /api/media/collections/{id} returns the populated :config
            from the stored row — the response schema must not be a closed
            :map that silently strips every key under :config."
    (let [stored-row {:id 99
                      :kind "smart"
                      :name "live-probe-139a"
                      :use-custom-playback-order false
                      :config {:query {:category "mystery"
                                       :channel-tag "channel:enigma"}}}]
      (with-redefs [db-media/get-collection
                    (fn [_ds _id] stored-row)]
        (let [resp ((make-test-handler)
                    (mock/request :get "/api/media/collections/99"))
              body (parse-json-body resp)]
          (is (= 200 (:status resp)))
          (is (= "smart" (:kind body)))
          (is (= "live-probe-139a" (:name body)))
          (is (= {:category "mystery"
                  :channel-tag "channel:enigma"}
                 (get-in body [:config :query]))
              "the populated :query map under :config must round-trip
               through the response coercion — a closed-map response
               schema would silently return config: {}."))))))

(deftest get-collection-response-preserves-config-with-empty-query
  (testing "GET /api/media/collections/{id} where :config is itself a
            non-empty map (but not under :query) — same regression: a
            closed :map response schema drops every key."
    (let [stored-row {:id 100
                      :kind "manual"
                      :name "playlist-test"
                      :use-custom-playback-order true
                      :config {:items [1 2 3] :label "favorites"}}]
      (with-redefs [db-media/get-collection
                    (fn [_ds _id] stored-row)]
        (let [resp ((make-test-handler)
                    (mock/request :get "/api/media/collections/100"))
              body (parse-json-body resp)]
          (is (= 200 (:status resp)))
          (is (= [1 2 3] (get-in body [:config :items]))
              ":items under :config must round-trip.")
          (is (= "favorites" (get-in body [:config :label]))
              ":label under :config must round-trip."))))))
