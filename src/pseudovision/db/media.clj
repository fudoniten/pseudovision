(ns pseudovision.db.media
  (:require [honey.sql         :as sql]
            [honey.sql.helpers :as h]
            [pseudovision.db.core :as db]))

;; ---------------------------------------------------------------------------
;; Media sources and libraries
;; ---------------------------------------------------------------------------

(defn list-media-sources [ds]
  (db/query ds (-> (h/select :*) (h/from :media-sources) sql/format)))

(defn get-media-source [ds id]
  (db/query-one ds (-> (h/select :*)
                       (h/from :media-sources)
                       (h/where [:= :id id])
                       sql/format)))

(defn create-media-source! [ds attrs]
  (db/execute-one! ds (-> (h/insert-into :media-sources)
                          (h/values [attrs])
                          sql/format)))

(defn list-libraries [ds]
  (db/query ds (-> (h/select :l.* :ms.name :ms.kind)
                   (h/from [:libraries :l])
                   (h/join [:media-sources :ms] [:= :l.media-source-id :ms.id])
                   (h/order-by :l.name)
                   sql/format)))

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
  (db/execute-one! ds (-> (h/insert-into :libraries)
                          (h/values [attrs])
                          sql/format)))

(defn list-library-paths [ds library-id]
  (db/query ds (-> (h/select :*)
                   (h/from :library-paths)
                   (h/where [:= :library-id library-id])
                   sql/format)))

(defn create-library-path! [ds attrs]
  (db/execute-one! ds (-> (h/insert-into :library-paths)
                          (h/values [attrs])
                          sql/format)))

;; ---------------------------------------------------------------------------
;; Media items
;; ---------------------------------------------------------------------------

(defn get-media-item [ds id]
  (db/query-one ds (-> (h/select :mi.* :m.title :m.year :m.release-date
                                  :m.plot :m.content-rating)
                       (h/from [:media-items :mi])
                       (h/left-join [:metadata :m] [:= :m.media-item-id :mi.id])
                       (h/where [:= :mi.id id])
                       sql/format)))

(defn list-items-for-library-path [ds library-path-id]
  (db/query ds (-> (h/select :*)
                   (h/from :media-items)
                   (h/where [:= :library-path-id library-path-id])
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
  (db/execute-one! ds (-> (h/insert-into :media-items)
                          (h/values [attrs])
                          (h/on-conflict :library-path-id :remote-key)
                          (h/do-update-set :state :remote-etag :position)
                          sql/format)))

;; ---------------------------------------------------------------------------
;; Collections
;; ---------------------------------------------------------------------------

(defn list-collections [ds]
  (db/query ds (-> (h/select :*)
                   (h/from :collections)
                   (h/order-by :name)
                   sql/format)))

(defn get-collection [ds id]
  (db/query-one ds (-> (h/select :*)
                       (h/from :collections)
                       (h/where [:= :id id])
                       sql/format)))

(defn create-collection! [ds attrs]
  (db/execute-one! ds (-> (h/insert-into :collections)
                          (h/values [attrs])
                          sql/format)))

(defn add-item-to-collection! [ds collection-id media-item-id]
  (db/execute-one! ds (-> (h/insert-into :collection-items)
                          (h/values [{:collection-id collection-id
                                      :media-item-id media-item-id}])
                          (h/on-conflict :collection-id :media-item-id)
                          (h/do-nothing)
                          sql/format)))

(defn remove-item-from-collection! [ds collection-id media-item-id]
  (db/execute-one! ds (-> (h/delete-from :collection-items)
                          (h/where [:and
                                    [:= :collection-id collection-id]
                                    [:= :media-item-id media-item-id]])
                          sql/format)))
