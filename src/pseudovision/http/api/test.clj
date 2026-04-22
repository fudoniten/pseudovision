(ns pseudovision.http.api.test
  "Test utilities API endpoints.

   Provides endpoints for creating and managing test channels for streaming verification."
  (:require [pseudovision.dev.test-channel :as tc]
            [pseudovision.db.channels :as db-channels]
            [pseudovision.db.core :as db-core]
            [pseudovision.util.sql :as sql-util]
            [honey.sql :as h]
            [honey.sql.helpers :as hh]
            [taoensso.timbre :as log]
            [clojure.java.io :as io])
  (:import [java.util UUID]))

(defn create-test-channel-handler
  "POST /api/test/channels — creates a test channel for streaming verification."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [params (or (get-in req [:parameters :body]) {})
            opts (cond-> {:verbose false}
                   (:number params)        (assoc :number (:number params))
                   (:name params)          (assoc :name (:name params))
                   (:collection-id params) (assoc :collection-id (:collection-id params)))

            result (tc/create-test-channel! db opts)
            host (or (get-in req [:headers "host"]) "localhost:8080")
            scheme (if (get-in req [:headers "x-forwarded-proto"])
                     (get-in req [:headers "x-forwarded-proto"])
                     "http")]

        (log/info "Created test channel via API"
                  {:uuid (:uuid result)
                   :number (get-in result [:channel :channels/number])})

        {:status 201
         :body {:channel      (:channel result)
                :schedule     (:schedule result)
                :playout      (:playout result)
                :uuid         (:uuid result)
                :stream-url   (str scheme "://" host "/stream/" (:uuid result))
                :playlist-url (str scheme "://" host "/iptv/channels.m3u")
                :epg-url      (str scheme "://" host "/xmltv")}})

      (catch Exception e
        (log/error e "Failed to create test channel via API")
        {:status 500
         :body {:error (str "Failed to create test channel: " (.getMessage e))}}))))

(defn delete-test-channel-handler
  "DELETE /api/test/channels/:identifier — deletes a test channel by UUID or number."
  [{:keys [db]}]
  (fn [req]
    (let [identifier (get-in req [:parameters :path :identifier])]
      (try
        (if-let [deleted-channel (tc/delete-test-channel! db identifier {:verbose false})]
          (do
            (log/info "Deleted test channel via API"
                      {:identifier identifier
                       :channel-id (:channels/id deleted-channel)})
            {:status 200
             :body {:deleted true
                    :channel deleted-channel
                    :message (str "Deleted channel: " (:channels/name deleted-channel))}})

          {:status 404
           :body {:error      "Channel not found"
                  :identifier identifier}})

        (catch Exception e
          (log/error e "Failed to delete test channel via API" {:identifier identifier})
          {:status 500
           :body {:error (str "Failed to delete test channel: " (.getMessage e))}})))))

(defn list-test-channels-handler
  "GET /api/test/channels — lists channels with 'Test' in the name or the 'Testing' group."
  [{:keys [db]}]
  (fn [_req]
    (try
      (let [test-channels (db-core/query
                           db
                           ["SELECT * FROM channels
                             WHERE name LIKE '%Test%'
                                OR group_name = 'Testing'
                             ORDER BY sort_number"])]

        {:status 200
         :body {:channels test-channels
                :count (count test-channels)}})

      (catch Exception e
        (log/error e "Failed to list test channels via API")
        {:status 500
         :body {:error (str "Failed to list test channels: " (.getMessage e))}}))))

(defn create-test-collection-handler
  "POST /api/test/collection — creates a manual collection with every media item."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [params (or (get-in req [:parameters :body]) {})
            coll-name (or (:name params) "Test Collection (All Media)")

            collection (db-core/execute-one!
                        db
                        (h/format
                          {:insert-into :collections
                           :values [{:kind   (sql-util/->pg-enum "collection_kind" "manual")
                                     :name   coll-name
                                     :config (sql-util/->jsonb {})}]
                           :returning [:*]}))

            collection-id (:id collection)

            _ (db-core/execute!
               db
               [(str "INSERT INTO collection_items (collection_id, media_item_id) "
                     "SELECT ?::int, id FROM media_items "
                     "ON CONFLICT (collection_id, media_item_id) DO NOTHING")
                collection-id])

            count-result (db-core/query-one
                          db
                          (h/format
                            {:select [[[:count :*] :count]]
                             :from :collection-items
                             :where [:= :collection-id collection-id]}))

            item-count (:count count-result)]

        (log/info "Created test collection with all media"
                  {:collection-id collection-id
                   :name coll-name
                   :item-count item-count})

        {:status 201
         :body {:collection collection
                :item-count item-count
                :message    (str "Created collection with " item-count " items")}})

      (catch Exception e
        (log/error e "Failed to create test collection via API")
        {:status 500
         :body {:error (str "Failed to create test collection: " (.getMessage e))}}))))

(defn add-test-artwork-handler
  "POST /api/test/channels/:identifier/artwork — generates a simple test logo."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [identifier (get-in req [:parameters :path :identifier])
            channel (or (try (db-channels/get-channel-by-uuid
                              db (UUID/fromString identifier))
                            (catch Exception _ nil))
                       (db-channels/get-channel-by-number db identifier))]

        (if-not channel
          {:status 404 :body {:error "Channel not found"}}

          (let [channel-id (:channels/id channel)
                channel-uuid (:channels/uuid channel)
                channel-name (:channels/name channel)
                logo-base64 (slurp (io/resource "test-logo.png.b64"))
                logo-path logo-base64

                existing-artwork (first (db-core/query
                                         db
                                         ["SELECT * FROM channel_artwork WHERE channel_id = ? AND kind = 'logo'"
                                          channel-id]))

                artwork (if existing-artwork
                          (db-core/execute-one!
                           db
                           (-> (hh/update :channel-artwork)
                               (hh/set {:path logo-path
                                        :original-content-type "image/png"})
                               (hh/where [:= :id (:channel-artwork/id existing-artwork)])
                               (hh/returning :*)
                               h/format))
                          (db-core/execute-one!
                           db
                           (-> (hh/insert-into :channel-artwork)
                               (hh/values [{:channel-id channel-id
                                            :kind (sql-util/->pg-enum "artwork_kind" "logo")
                                            :path logo-path
                                            :original-content-type "image/png"}])
                               (hh/returning :*)
                               h/format)))

                host (or (get-in req [:headers "host"]) "localhost:8080")
                scheme (if (get-in req [:headers "x-forwarded-proto"])
                         (get-in req [:headers "x-forwarded-proto"])
                         "http")
                logo-url (str scheme "://" host "/logos/" channel-uuid)]

            (log/info "Created test artwork for channel"
                      {:channel-id channel-id
                       :channel-name channel-name
                       :path logo-path})

            {:status 201
             :body {:artwork  artwork
                    :logo-url logo-url
                    :message  (str "Created logo at " logo-path)}})))

      (catch Exception e
        (log/error e "Failed to create test artwork via API")
        {:status 500
         :body {:error (str "Failed to create test artwork: " (.getMessage e))}}))))

(defn test-info-handler
  "GET /api/test/info — metadata and usage examples for the test API."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [collections (db-core/query db ["SELECT * FROM collections ORDER BY id"])
            default-collection (first collections)
            host (or (get-in req [:headers "host"]) "localhost:8080")
            scheme (if (get-in req [:headers "x-forwarded-proto"])
                     (get-in req [:headers "x-forwarded-proto"])
                     "http")
            base-url (str scheme "://" host)]

        {:status 200
         :body {:collections        collections
                :default-collection default-collection
                :endpoints {:create      (str base-url "/api/test/channels")
                            :list        (str base-url "/api/test/channels")
                            :delete      (str base-url "/api/test/channels/:identifier")
                            :add-artwork (str base-url "/api/test/channels/:identifier/artwork")
                            :info        (str base-url "/api/test/info")}
                :usage {:create      {:method "POST"
                                      :url    (str base-url "/api/test/channels")
                                      :body   {:number "999"
                                               :name   "My Test Channel"
                                               :collection-id (when default-collection
                                                                (:collections/id default-collection))}}
                        :delete      {:method "DELETE"
                                      :url    (str base-url "/api/test/channels/999")}
                        :add-artwork {:method "POST"
                                      :url    (str base-url "/api/test/channels/999/artwork")
                                      :body   {}}
                        :list        {:method "GET"
                                      :url    (str base-url "/api/test/channels")}}}})

      (catch Exception e
        (log/error e "Failed to get test info via API")
        {:status 500
         :body {:error (str "Failed to get test info: " (.getMessage e))}}))))
