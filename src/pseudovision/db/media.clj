(ns pseudovision.db.media
  (:require [honey.sql         :as sql]
            [honey.sql.helpers :as h]
            [pseudovision.db.core :as db]
            [pseudovision.util.sql :as sql-util]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Media sources and libraries
;; ---------------------------------------------------------------------------

(defn count-media-sources
  "Counts total media sources."
  [ds]
  (let [result (db/query-one ds (-> (h/select [[:%count.* :count]])
                                    (h/from :media-sources)
                                    sql/format))]
    (or (:count result) 0)))

(defn list-media-sources
  "Lists media sources with optional pagination.
   
   opts:
   - :limit  - maximum number of sources to return
   - :offset - number of sources to skip"
  ([ds]
   (list-media-sources ds nil))
  ([ds opts]
   (db/query ds (-> (h/select :*)
                    (h/from :media-sources)
                    (cond->
                      (:limit opts)  (h/limit (:limit opts))
                      (:offset opts) (h/offset (:offset opts)))
                    sql/format))))

(defn get-media-source [ds id]
  (db/query-one ds (-> (h/select :*)
                       (h/from :media-sources)
                       (h/where [:= :id id])
                       sql/format)))

(defn create-media-source! [ds attrs]
  (let [prepared (cond-> attrs
                   (:kind attrs)                (update :kind #(sql-util/->pg-enum "media_source_kind" %))
                   (:connection-config attrs)   (update :connection-config sql-util/->jsonb)
                   (:path-replacements attrs)   (update :path-replacements sql-util/->jsonb))
        sql-map  (-> (h/insert-into :media-sources)
                     (h/values [prepared])
                     sql/format)]
    (log/info "Creating media source" {:attrs attrs :prepared prepared :sql sql-map})
    (let [result (db/execute-one! ds sql-map)]
      (log/info "Created media source"
                {:source-id (:media-sources/id result)
                 :name      (:name attrs)
                 :kind      (:kind attrs)})
      result)))

(defn update-media-source! [ds id attrs]
  (let [prepared (cond-> attrs
                   (:kind attrs)               (update :kind #(sql-util/->pg-enum "media_source_kind" %))
                   (:connection-config attrs)  (update :connection-config sql-util/->jsonb)
                   (:path-replacements attrs)  (update :path-replacements sql-util/->jsonb))
        result (db/execute-one! ds (-> (h/update :media-sources)
                                       (h/set prepared)
                                       (h/where [:= :id id])
                                       sql/format))]
    (log/info "Updated media source" {:source-id id})
    result))

(defn delete-media-source! [ds id]
  (let [result (db/execute-one! ds (-> (h/delete-from :media-sources)
                                       (h/where [:= :id id])
                                       sql/format))]
    (log/info "Deleted media source" {:source-id id})
    result))

(defn count-libraries
  "Counts total libraries."
  [ds]
  (let [result (db/query-one ds (-> (h/select [[:%count.* :count]])
                                    (h/from :libraries)
                                    sql/format))]
    (or (:count result) 0)))

(defn list-libraries
  "Lists libraries with optional pagination.
   
   opts:
   - :limit  - maximum number of libraries to return
   - :offset - number of libraries to skip"
  ([ds]
   (list-libraries ds nil))
  ([ds opts]
   (db/query ds (-> (h/select :*)
                    (h/from :libraries)
                    (h/order-by :name)
                    (cond->
                      (:limit opts)  (h/limit (:limit opts))
                      (:offset opts) (h/offset (:offset opts)))
                    sql/format))))

(defn list-libraries-for-source [ds source-id]
  (db/query ds (-> (h/select :*)
                   (h/from :libraries)
                   (h/where [:= :media-source-id source-id])
                   sql/format)))

(defn get-library [ds id]
  (db/query-one ds (-> (h/select :*)
                       (h/from :libraries)
                       (h/where [:= :id id])
                       sql/format)))

(defn create-library! [ds attrs]
  (let [result (db/execute-one! ds (-> (h/insert-into :libraries)
                                       (h/values [(update attrs :kind #(sql-util/->pg-enum "library_kind" %))])
                                       sql/format))]
    (log/info "Created library"
              {:library-id      (:libraries/id result)
               :name            (:name attrs)
               :kind            (:kind attrs)
               :media-source-id (:media-source-id attrs)
               :external-id     (:external-id attrs)})
    result))

(defn update-library! [ds id attrs]
  (let [result (db/execute-one! ds (-> (h/update :libraries)
                                       (h/set attrs)
                                       (h/where [:= :id id])
                                       sql/format))]
    (log/info "Updated library" {:library-id id})
    result))

(defn delete-library! [ds id]
  (let [result (db/execute-one! ds (-> (h/delete-from :libraries)
                                       (h/where [:= :id id])
                                       sql/format))]
    (log/info "Deleted library" {:library-id id})
    result))

(defn list-library-paths [ds library-id]
  (db/query ds (-> (h/select :*)
                   (h/from :library-paths)
                   (h/where [:= :library-id library-id])
                   sql/format)))

(defn create-library-path! [ds attrs]
  (let [result (db/execute-one! ds (-> (h/insert-into :library-paths)
                                       (h/values [attrs])
                                       (h/returning :*)
                                       sql/format))]
    (log/info "Created library path"
              {:library-path-id (:library-paths/id result)
               :library-id      (:library-id attrs)
               :path            (:path attrs)})
    result))

(defn delete-library-path! [ds id]
  (db/execute-one! ds (-> (h/delete-from :library-paths)
                          (h/where [:= :id id])
                          sql/format)))

;; ---------------------------------------------------------------------------
;; Media items
;; ---------------------------------------------------------------------------

(def ^:private item-attr->col
  "Maps attribute keyword → HoneySQL select expression."
  {:id              :mi.id
   :kind            :mi.kind
   :state           :mi.state
   :parent-id       :mi.parent-id
   :position        :mi.position
   :library-path-id :mi.library-path-id
   :remote-key      [:mi.remote_key :remote-key]
   :remote-etag     [:mi.remote_etag :remote-etag]
   :name            [:m.title :name]
   :year            :m.year
   :release-date    :m.release-date
   :plot            :m.plot
   :tagline         :m.tagline
   :content-rating  :m.content-rating})

(def ^:private metadata-attrs
  "Attributes that require a LEFT JOIN with the metadata table."
  #{:name :year :release-date :plot :tagline :content-rating})

(def ^:private default-item-attrs [:id :name])

(defn- build-media-items-base-query
  "Builds the base query for media items with filters, without pagination."
  [library-id opts]
  (let [base (-> (h/from [:media-items :mi])
                 (h/join [:library-paths :lp] [:= :lp.id :mi.library-path-id])
                 (h/where [:= :lp.library-id library-id]))
        with-type (cond-> base
                    (:type opts)
                    (h/where [:= :mi.kind (sql-util/->pg-enum "media_item_kind" (name (:type opts)))]))
        with-parent (cond-> with-type
                      (contains? opts :parent-id)
                      (h/where [:= :mi.parent-id (:parent-id opts)]))]
    with-parent))

(defn count-media-items
  "Counts total media items in a library with optional filtering.
   
   opts:
   - :type      - media_item_kind keyword or string to filter by (e.g. :movie)
   - :parent-id - when present in opts (even if nil), filters by parent_id"
  [ds library-id opts]
  (let [query (-> (build-media-items-base-query library-id opts)
                  (h/select [[:%count.* :count]])
                  sql/format)
        result (db/query-one ds query)]
    (or (:count result) 0)))

(defn list-media-items
  "List media items in a library with optional attribute selection and filtering.

   opts:
   - :attrs     - seq of attribute name strings/keywords to include
                  (default: [:id :name]). Special value: :child-count adds a
                  correlated subquery counting direct children of each item.
   - :type      - media_item_kind keyword or string to filter by (e.g. :movie)
   - :parent-id - when present in opts (even if nil), adds a WHERE clause on
                  parent_id; pass an integer to list children of that item.
   - :limit     - maximum number of items to return
   - :offset    - number of items to skip"
  [ds library-id opts]
  (let [attrs       (mapv keyword (or (seq (:attrs opts)) default-item-attrs))
        need-meta?  (some metadata-attrs attrs)
        need-count? (some #{:child-count} attrs)
        col-attrs   (remove #{:child-count} attrs)
        select-cols (cond-> (mapv item-attr->col (filter item-attr->col col-attrs))
                      need-count?
                      (conj [{:select [[:%count.*]]
                              :from   [[:media-items :ch]]
                              :where  [:= :ch.parent-id :mi.id]}
                             :child-count]))
        base        (-> (build-media-items-base-query library-id opts)
                        (h/select select-cols))
        with-meta   (cond-> base
                      need-meta?
                      (h/left-join [:metadata :m] [:= :m.media-item-id :mi.id]))
        with-order  (h/order-by with-meta :mi.id)
        with-pagination (cond-> with-order
                          (:limit opts)  (h/limit (:limit opts))
                          (:offset opts) (h/offset (:offset opts)))]
    (db/query ds (sql/format with-pagination))))

(defn get-media-item [ds id]
  (db/query-one ds (-> (h/select :mi.id :mi.kind :mi.state :mi.parent-id :mi.position
                                 [:mi.remote_key :remote-key]
                                 [:mi.remote_etag :remote-etag]
                                 [:m.title :name]
                                 :m.year
                                 [:m.release-date :release-date]
                                 :m.plot
                                 [:m.content-rating :content-rating])
                       (h/from [:media-items :mi])
                       (h/left-join [:metadata :m] [:= :m.media-item-id :mi.id])
                       (h/where [:= :mi.id id])
                       sql/format)))

(defn get-media-item-with-source
  "Returns a media item joined to its media source, including the source kind
   and connection_config needed to construct a playback URL."
  [ds id]
  (db/query-one ds (-> (h/select :mi.id :mi.kind :mi.state :mi.remote-key
                                 :m.title
                                 :ms.id   :ms.kind
                                 :ms.connection-config)
                       (h/from [:media-items :mi])
                       (h/join [:library-paths :lp] [:= :lp.id :mi.library-path-id])
                       (h/join [:libraries :l]      [:= :l.id :lp.library-id])
                       (h/join [:media-sources :ms]  [:= :ms.id :l.media-source-id])
                       (h/left-join [:metadata :m]   [:= :m.media-item-id :mi.id])
                       (h/where [:= :mi.id id])
                       sql/format)))

(defn list-items-for-library-path [ds library-path-id]
  (db/query ds (-> (h/select :*)
                   (h/from :media-items)
                   (h/where [:= :library-path-id library-path-id])
                   sql/format)))

(defn list-items-for-library [ds library-id]
  (db/query ds (-> (h/select :mi.*)
                   (h/from [:media-items :mi])
                   (h/join [:library-paths :lp] [:= :lp.id :mi.library-path-id])
                   (h/where [:= :lp.library-id library-id])
                   (h/order-by :mi.id)
                   sql/format)))

(defn list-items-for-collection
  "Returns all media items in a manual collection, in playback order."
  [ds collection-id]
  (db/query ds (-> (h/select :mi.*)
                   (h/from [:media-items :mi])
                   (h/join [:collection-items :ci] [:= :ci.media-item-id :mi.id])
                   (h/where [:= :ci.collection-id collection-id])
                   (h/order-by :ci.custom-order :mi.id)
                   sql/format)))

(defn get-item-by-path [ds path]
  (db/query-one ds (-> (h/select :mi.*)
                       (h/from [:media-items :mi])
                       (h/join [:media-versions  :mv] [:= :mv.media-item-id :mi.id])
                       (h/join [:media-files     :mf] [:= :mf.media-version-id :mv.id])
                       (h/where [:= :mf.path path])
                       sql/format)))

(defn upsert-media-item! [ds attrs]
  (let [prepared (cond-> attrs
                   (:kind attrs)  (update :kind #(sql-util/->pg-enum "media_item_kind" %))
                   (:state attrs) (update :state #(sql-util/->pg-enum "media_item_state" %)))]
    (if (:remote-key attrs)
      ;; For items with remote_key, use ON CONFLICT
      (let [sql-vec  (-> (h/insert-into :media-items)
                         (h/values [prepared])
                         (h/on-conflict :library-path-id :remote-key)
                         (h/do-update-set :state :remote-key :remote-etag :position)
                         sql/format)]
        (log/info "Media item upsert SQL (with remote-key)" {:sql (first sql-vec)})
        (let [result (db/execute-one! ds sql-vec)]
          (log/info "Upserted media item"
                    {:media-item-id   (:media-items/id result)
                     :kind            (:kind attrs)
                     :library-path-id (:library-path-id attrs)
                     :remote-key      (:remote-key attrs)
                     :parent-id       (:parent-id attrs)})
          result))
      ;; For items without remote_key, just insert
      (let [result (db/execute-one! ds (-> (h/insert-into :media-items)
                                           (h/values [prepared])
                                           sql/format))]
        (log/info "Inserted media item (no remote-key)"
                  {:media-item-id   (:media-items/id result)
                   :kind            (:kind attrs)
                   :library-path-id (:library-path-id attrs)
                   :parent-id       (:parent-id attrs)})
        result))))

;; ---------------------------------------------------------------------------
;; Collections
;; ---------------------------------------------------------------------------

(defn count-collections
  "Counts total collections."
  [ds]
  (let [result (db/query-one ds (-> (h/select [[:%count.* :count]])
                                    (h/from :collections)
                                    sql/format))]
    (or (:count result) 0)))

(defn list-collections
  "Lists collections with optional pagination.
   
   opts:
   - :limit  - maximum number of collections to return
   - :offset - number of collections to skip"
  ([ds]
   (list-collections ds nil))
  ([ds opts]
   (db/query ds (-> (h/select :*)
                    (h/from :collections)
                    (h/order-by :name)
                    (cond->
                      (:limit opts)  (h/limit (:limit opts))
                      (:offset opts) (h/offset (:offset opts)))
                    sql/format))))

(defn get-collection [ds id]
  (db/query-one ds (-> (h/select :*)
                       (h/from :collections)
                       (h/where [:= :id id])
                       sql/format)))

(defn create-collection! [ds attrs]
  (let [result (db/execute-one! ds (-> (h/insert-into :collections)
                                       (h/values [(cond-> attrs
                                                    (:kind attrs)   (update :kind #(sql-util/->pg-enum "collection_kind" %))
                                                    (:config attrs) (update :config sql-util/->jsonb))])
                                       sql/format))]
    (log/info "Created collection"
              {:collection-id (:collections/id result)
               :name          (:name attrs)
               :kind          (:kind attrs)})
    result))

(defn update-collection! [ds id attrs]
  (let [result (db/execute-one! ds (-> (h/update :collections)
                                       (h/set (cond-> attrs
                                                (:config attrs) (update :config sql-util/->jsonb)))
                                       (h/where [:= :id id])
                                       sql/format))]
    (log/info "Updated collection" {:collection-id id})
    result))

(defn delete-collection! [ds id]
  (db/execute-one! ds (-> (h/delete-from :collections)
                          (h/where [:= :id id])
                          sql/format)))

(defn list-items-in-collection
  "Returns collection_items rows for a manual collection, ordered by custom_order."
  [ds collection-id]
  (db/query ds (-> (h/select :ci.* [:mi.id :media-item-id] [:m.title :name])
                   (h/from [:collection-items :ci])
                   (h/join [:media-items :mi] [:= :mi.id :ci.media-item-id])
                   (h/left-join [:metadata :m] [:= :m.media-item-id :mi.id])
                   (h/where [:= :ci.collection-id collection-id])
                   (h/order-by [[:coalesce :ci.custom-order :mi.id]])
                   sql/format)))

(defn add-item-to-collection! [ds collection-id media-item-id]
  (let [result (db/execute-one! ds (-> (h/insert-into :collection-items)
                                       (h/values [{:collection-id collection-id
                                                   :media-item-id media-item-id}])
                                       (h/on-conflict :collection-id :media-item-id)
                                       (h/do-nothing)
                                       sql/format))]
    (log/info "Added item to collection"
              {:collection-id collection-id
               :media-item-id media-item-id})
    result))

(defn remove-item-from-collection! [ds collection-id media-item-id]
  (db/execute-one! ds (-> (h/delete-from :collection-items)
                          (h/where [:and
                                    [:= :collection-id collection-id]
                                    [:= :media-item-id media-item-id]])
                          sql/format)))
