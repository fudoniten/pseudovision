(ns pseudovision.db.channels
  (:require [honey.sql        :as sql]
            [honey.sql.helpers :as h]
            [pseudovision.db.core :as db]))

(defn list-channels [ds]
  (db/query ds (-> (h/select :*)
                   (h/from :channels)
                   (h/order-by :sort-number)
                   sql/format)))

(defn get-channel [ds id]
  (db/query-one ds (-> (h/select :*)
                       (h/from :channels)
                       (h/where [:= :id id])
                       sql/format)))

(defn get-channel-by-number [ds number]
  (db/query-one ds (-> (h/select :*)
                       (h/from :channels)
                       (h/where [:= :number number])
                       sql/format)))

(defn get-channel-by-uuid [ds uuid]
  (db/query-one ds (-> (h/select :*)
                       (h/from :channels)
                       (h/where [:= :uuid (str uuid)])
                       sql/format)))

(defn create-channel! [ds attrs]
  (db/execute-one! ds (-> (h/insert-into :channels)
                          (h/values [attrs])
                          sql/format)))

(defn update-channel! [ds id attrs]
  (db/execute-one! ds (-> (h/update :channels)
                          (h/set attrs)
                          (h/where [:= :id id])
                          sql/format)))

(defn delete-channel! [ds id]
  (db/execute-one! ds (-> (h/delete-from :channels)
                          (h/where [:= :id id])
                          sql/format)))

(defn list-channel-artwork [ds channel-id]
  (db/query ds (-> (h/select :*)
                   (h/from :channel-artwork)
                   (h/where [:= :channel-id channel-id])
                   sql/format)))
