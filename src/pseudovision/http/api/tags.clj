(ns pseudovision.http.api.tags
  "Tag management API for media items.
   
   Tags are stored in metadata_tags table and enable flexible content
   selection in schedule slots via required_tags/excluded_tags filters."
  (:require [pseudovision.db.core :as db]
            [honey.sql.helpers :as h]
            [honey.sql :as sql]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Tag CRUD
;; ---------------------------------------------------------------------------

(defn add-tags-handler
  "Add tags to a media item.
   Body: {tags: ['comedy', 'short']}"
  [{:keys [db]}]
  (fn [req]
    (let [item-id (parse-long (get-in req [:path-params :id]))
          tags    (get-in req [:body-params :tags] [])
          source  (get-in req [:body-params :source] "manual")]
      (if (empty? tags)
        {:status 400 :body {:error "No tags provided"}}
        (let [;; First get or create metadata for this item
              metadata (db/query-one db (-> (h/select :*)
                                           (h/from :metadata)
                                           (h/where [:= :media-item-id item-id])
                                           sql/format))
              metadata-id (if metadata
                           (:metadata/id metadata)
                           ;; Create metadata if it doesn't exist
                           (let [item (db/query-one db (-> (h/select :kind)
                                                          (h/from :media-items)
                                                          (h/where [:= :id item-id])
                                                          sql/format))]
                             (if item
                               (:metadata/id
                                (db/query-one db
                                  (-> (h/insert-into :metadata)
                                      (h/values [{:media-item-id item-id
                                                 :kind (:media-items/kind item)}])
                                      (h/returning :id)
                                      sql/format)))
                               nil)))]
          (if metadata-id
            (do
              ;; Insert tags (check for duplicates first)
              (doseq [tag tags]
                (let [existing (db-core/query-one db
                                 (-> (h/select :id)
                                     (h/from :metadata-tags)
                                     (h/where [:and
                                               [:= :metadata-id metadata-id]
                                               [:= :name tag]])
                                     sql/format))]
                  (when-not existing
                    (try
                      (db-core/execute-one! db
                        (-> (h/insert-into :metadata-tags)
                            (h/values [{:metadata-id metadata-id
                                       :name tag}])
                            sql/format))
                      (catch Exception e
                        (log/warn "Failed to insert tag" {:tag tag :error (.getMessage e)}))))))
              (log/info "Added tags to media item" {:item-id item-id :tags tags})
              {:status 200 :body {:item-id item-id :tags-added tags}})
            {:status 404 :body {:error "Media item not found"}}))))))

(defn get-tags-handler
  "Get all tags for a media item."
  [{:keys [db]}]
  (fn [req]
    (let [item-id (parse-long (get-in req [:path-params :id]))
          tags    (db/query db (-> (h/select :mt.name)
                                  (h/from [:metadata-tags :mt])
                                  (h/join [:metadata :m] [:= :m.id :mt.metadata-id])
                                  (h/where [:= :m.media-item-id item-id])
                                  (h/order-by :mt.name)
                                  sql/format))]
      {:status 200 :body (mapv :metadata-tags/name tags)})))

(defn delete-tag-handler
  "Remove a specific tag from a media item."
  [{:keys [db]}]
  (fn [req]
    (let [item-id (parse-long (get-in req [:path-params :id]))
          tag     (get-in req [:path-params :tag])]
      (db/execute-one! db
        (-> (h/delete-from :metadata-tags)
            (h/using :metadata)
            (h/where [:and
                      [:= :metadata-tags.metadata-id :metadata.id]
                      [:= :metadata.media-item-id item-id]
                      [:= :metadata-tags.name tag]])
            sql/format))
      (log/info "Deleted tag from media item" {:item-id item-id :tag tag})
      {:status 204})))

(defn list-all-tags-handler
  "List all unique tags with usage counts."
  [{:keys [db]}]
  (fn [_req]
    (let [tags (db/query db (-> (h/select :name [:%count.* :count])
                               (h/from :metadata-tags)
                               (h/group-by :name)
                               (h/order-by [:count :desc] :name)
                               sql/format))]
      {:status 200 :body tags})))
