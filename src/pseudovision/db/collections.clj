(ns pseudovision.db.collections
  "Higher-level collection resolution used by the scheduling engine.
   Handles the different collection kinds and returns a seq of media-item maps."
  (:require [honey.sql         :as sql]
            [honey.sql.helpers :as h]
            [pseudovision.db.core :as db]
            [taoensso.timbre   :as log]))

(defmulti resolve-collection
  "Returns an ordered seq of media-item maps for a collection.
   Dispatch on :kind (keyword, e.g. :manual, :smart, :playlist …)."
  (fn [_ds collection] (keyword (:collections/kind collection))))

(defmethod resolve-collection :manual [ds collection]
  (db/query ds (-> (h/select :mi.*)
                   (h/from [:media-items :mi])
                   (h/join [:collection-items :ci] [:= :ci.media-item-id :mi.id])
                   (h/where [:= :ci.collection-id (:collections/id collection)])
                   (h/order-by [[:coalesce :ci.custom-order :mi.id]])
                   sql/format)))

(defmethod resolve-collection :smart [ds collection]
  ;; TODO: implement Lucene/search query parsing
  (log/warn "Smart collection resolution not yet implemented; returning empty"
            {:id (:collections/id collection)})
  [])

(defmethod resolve-collection :playlist [ds collection]
  ;; Playlist items are stored in config JSONB; each item references
  ;; another collection.  Flatten them in index order.
  (let [items (get-in collection [:collections/config "items"] [])]
    (mapcat (fn [{content-id "content_id" content-kind "content_kind"}]
              (let [child (db/query-one ds
                            (-> (h/select :*)
                                (h/from :collections)
                                (h/where [:= :id content-id])
                                sql/format))]
                (if child
                  (resolve-collection ds child)
                  (do (log/warn "Playlist item references missing collection"
                                {:id content-id})
                      []))))
            items)))

(defmethod resolve-collection :multi [ds collection]
  (let [members (get-in collection [:collections/config "members"] [])]
    (mapcat (fn [{coll-id "collection_id"}]
              (let [child (db/query-one ds
                            (-> (h/select :*)
                                (h/from :collections)
                                (h/where [:= :id coll-id])
                                sql/format))]
                (if child (resolve-collection ds child) [])))
            members)))

(defmethod resolve-collection :trakt [ds collection]
  ;; Trakt items are resolved via the trakt_list_items junction table.
  (db/query ds (-> (h/select :mi.*)
                   (h/from [:media-items :mi])
                   (h/join [:trakt-list-items :tl] [:= :tl.media-item-id :mi.id])
                   (h/where [:= :tl.collection-id (:collections/id collection)])
                   (h/order-by :mi.id)
                   sql/format)))

(defmethod resolve-collection :rerun [ds collection]
  ;; TODO: implement first-run / rerun filtering
  (log/warn "Rerun collection resolution not yet implemented; returning empty"
            {:id (:collections/id collection)})
  [])

(defmethod resolve-collection :default [_ds collection]
  (log/error "Unknown collection kind" {:kind (:collections/kind collection)})
  [])
