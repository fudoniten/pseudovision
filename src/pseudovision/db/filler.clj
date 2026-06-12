(ns pseudovision.db.filler
  (:require [honey.sql :as sql]
            [honey.sql.helpers :as h]
            [pseudovision.db.core :as db]
            [pseudovision.db.collections :as col-db]))

(defn get-filler-preset
  "Fetches a filler_presets row by id. Returns nil if not found or id is nil."
  [ds id]
  (when id
    (db/query-one ds (-> (h/select :*)
                         (h/from :filler-presets)
                         (h/where [:= :id id])
                         sql/format))))

(defn load-filler-items
  "Returns the ordered seq of media items for a filler preset's content source."
  [ds preset]
  (cond
    (:filler-presets/collection-id preset)
    (let [coll (db/query-one ds (-> (h/select :*)
                                    (h/from :collections)
                                    (h/where [:= :id (:filler-presets/collection-id preset)])
                                    sql/format))]
      (if coll (col-db/resolve-collection ds coll) []))

    (:filler-presets/media-item-id preset)
    (let [item (db/query-one ds (-> (h/select :mi.* :mv.duration)
                                    (h/from [:media-items :mi])
                                    (h/left-join [:media-versions :mv]
                                                 [:= :mv.media-item-id :mi.id])
                                    (h/where [:= :mi.id (:filler-presets/media-item-id preset)])
                                    sql/format))]
      (if item [item] []))

    :else []))
