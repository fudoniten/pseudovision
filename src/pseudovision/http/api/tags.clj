(ns pseudovision.http.api.tags
  "Tag management API for media items.

   Tags are stored in metadata_tags table and enable flexible content
   selection in schedule slots via required_tags/excluded_tags filters."
  (:require [pseudovision.db.core :as db-core]
            [honey.sql.helpers :as h]
            [honey.sql :as sql]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Tag CRUD
;; ---------------------------------------------------------------------------

(defn add-tags-handler
  "POST /api/media-items/:id/tags
   Body: {:tags [\"comedy\" \"short\"] :source \"manual\"}"
  [{:keys [db]}]
  (fn [req]
    (let [item-id (get-in req [:parameters :path :id])
          body    (get-in req [:parameters :body])
          tags    (:tags body)
          _source (or (:source body) "manual")]
      (if (empty? tags)
        {:status 400 :body {:error "No tags provided"}}
        (let [metadata (db-core/query-one db (-> (h/select :*)
                                                 (h/from :metadata)
                                                 (h/where [:= :media-item-id item-id])
                                                 sql/format))
              metadata-id (if metadata
                            (:metadata/id metadata)
                            (let [item (db-core/query-one db (-> (h/select :kind)
                                                                 (h/from :media-items)
                                                                 (h/where [:= :id item-id])
                                                                 sql/format))]
                              (when item
                                (:metadata/id
                                 (db-core/query-one db
                                  (-> (h/insert-into :metadata)
                                      (h/values [{:media-item-id item-id
                                                  :kind (:media-items/kind item)}])
                                      (h/returning :id)
                                      sql/format))))))]
          (if metadata-id
            (do
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
  "GET /api/media-items/:id/tags"
  [{:keys [db]}]
  (fn [req]
    (let [item-id (get-in req [:parameters :path :id])
          tags    (db-core/query db (-> (h/select :mt.name)
                                        (h/from [:metadata-tags :mt])
                                        (h/join [:metadata :m] [:= :m.id :mt.metadata-id])
                                        (h/where [:= :m.media-item-id item-id])
                                        (h/order-by :mt.name)
                                        sql/format))]
      {:status 200 :body (mapv :metadata-tags/name tags)})))

(defn delete-tag-handler
  "DELETE /api/media-items/:id/tags/:tag"
  [{:keys [db]}]
  (fn [req]
    (let [item-id (get-in req [:parameters :path :id])
          tag     (get-in req [:parameters :path :tag])]
      (db-core/execute-one! db
        (-> (h/delete-from :metadata-tags)
            (h/using :metadata)
            (h/where [:and
                      [:= :metadata-tags.metadata-id :metadata.id]
                      [:= :metadata.media-item-id item-id]
                      [:= :metadata-tags.name tag]])
            sql/format))
      (log/info "Deleted tag from media item" {:item-id item-id :tag tag})
      {:status 204 :body nil})))

(defn list-all-tags-handler
  "GET /api/tags — list all unique tags with usage counts."
  [{:keys [db]}]
  (fn [_req]
    (let [tags (db-core/query db (-> (h/select :name [:%count.* :count])
                                     (h/from :metadata-tags)
                                     (h/group-by :name)
                                     (h/order-by [:count :desc] :name)
                                     sql/format))]
      {:status 200 :body (mapv (fn [t]
                                 {:name  (:metadata-tags/name t)
                                  :count (:count t)})
                               tags)})))
