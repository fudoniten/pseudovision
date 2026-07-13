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
            [clojure.string :as str]
            [ring.mock.request :as mock]
            [cheshire.core     :as json]
            [pseudovision.http.core :as http]))

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
                 {:kind "smart"
                  :config {:query {:category "mystery"}}}))
          body (parse-json-body resp)]
      (is (= 400 (:status resp))
          "missing :name triggers reitit-malli request-body coercion failure")
      (is (re-find #"Request coercion failed" (:error body))
          "the body says coercion failed (the schema's :name :string is required)")))))

(deftest create-collection-rejects-nested-config-as-string
  (testing "POST with config as a JSON string (instead of an object) is
            still rejected — the schema is :map, not :string"
    (let [resp ((make-test-handler)
                (post-json
                 "/api/media/collections"
                 {:name "bad-shape"
                  :kind "smart"
                  :config "{\"query\":{\"category\":\"mystery\"}}"}))
          body (parse-json-body resp)]
      (is (= 400 (:status resp))
          "config-as-string is rejected (was previously silently coerced to {})"))))
