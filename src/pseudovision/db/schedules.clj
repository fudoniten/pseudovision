(ns pseudovision.db.schedules
  (:require [honey.sql         :as sql]
            [honey.sql.helpers :as h]
            [pseudovision.db.core :as db]))

;; ---------------------------------------------------------------------------
;; Schedules
;; ---------------------------------------------------------------------------

(defn list-schedules [ds]
  (db/query ds (-> (h/select :*)
                   (h/from :schedules)
                   (h/order-by :name)
                   sql/format)))

(defn get-schedule [ds id]
  (db/query-one ds (-> (h/select :*)
                       (h/from :schedules)
                       (h/where [:= :id id])
                       sql/format)))

(defn create-schedule! [ds attrs]
  (db/execute-one! ds (-> (h/insert-into :schedules)
                          (h/values [attrs])
                          sql/format)))

(defn update-schedule! [ds id attrs]
  (db/execute-one! ds (-> (h/update :schedules)
                          (h/set attrs)
                          (h/where [:= :id id])
                          sql/format)))

(defn delete-schedule! [ds id]
  (db/execute-one! ds (-> (h/delete-from :schedules)
                          (h/where [:= :id id])
                          sql/format)))

;; ---------------------------------------------------------------------------
;; Schedule slots
;; ---------------------------------------------------------------------------

(defn list-slots [ds schedule-id]
  (db/query ds (-> (h/select :*)
                   (h/from :schedule-slots)
                   (h/where [:= :schedule-id schedule-id])
                   (h/order-by :slot-index)
                   sql/format)))

(defn get-slot [ds id]
  (db/query-one ds (-> (h/select :*)
                       (h/from :schedule-slots)
                       (h/where [:= :id id])
                       sql/format)))

(defn create-slot! [ds attrs]
  (db/execute-one! ds (-> (h/insert-into :schedule-slots)
                          (h/values [attrs])
                          sql/format)))

(defn update-slot! [ds id attrs]
  (db/execute-one! ds (-> (h/update :schedule-slots)
                          (h/set attrs)
                          (h/where [:= :id id])
                          sql/format)))

(defn delete-slot! [ds id]
  (db/execute-one! ds (-> (h/delete-from :schedule-slots)
                          (h/where [:= :id id])
                          sql/format)))

(defn reorder-slots!
  "Updates the slot_index for a sequence of slot-ids.
   slot-ids is an ordered vector; index is assigned by position."
  [ds schedule-id slot-ids]
  (doseq [[idx slot-id] (map-indexed vector slot-ids)]
    (update-slot! ds slot-id {:slot-index idx})))
