(ns pseudovision.db.playouts
  (:require [honey.sql         :as sql]
            [honey.sql.helpers :as h]
            [next.jdbc         :as jdbc]
            [pseudovision.db.core :as db]))

;; ---------------------------------------------------------------------------
;; Playouts
;; ---------------------------------------------------------------------------

(defn get-playout [ds id]
  (db/query-one ds (-> (h/select :*)
                       (h/from :playouts)
                       (h/where [:= :id id])
                       sql/format)))

(defn get-playout-for-channel [ds channel-id]
  (db/query-one ds (-> (h/select :*)
                       (h/from :playouts)
                       (h/where [:= :channel-id channel-id])
                       sql/format)))

(defn upsert-playout!
  "Creates the playout row for channel-id if it doesn't exist, or returns the
   existing one. Uses INSERT … ON CONFLICT DO NOTHING."
  [ds channel-id schedule-id]
  (jdbc/execute-one! ds
    (-> (h/insert-into :playouts)
        (h/values [{:channel-id  channel-id
                    :schedule-id schedule-id
                    :seed        (rand-int Integer/MAX_VALUE)}])
        (h/on-conflict :channel-id)
        (h/do-nothing)
        sql/format)
    {:return-keys true}))

(defn update-playout! [ds id attrs]
  (db/execute-one! ds (-> (h/update :playouts)
                          (h/set attrs)
                          (h/where [:= :id id])
                          sql/format)))

(defn save-cursor! [ds playout-id cursor]
  (update-playout! ds playout-id {:cursor (cheshire.core/generate-string cursor)}))

;; ---------------------------------------------------------------------------
;; Playout events
;; ---------------------------------------------------------------------------

(defn list-events
  "Returns all events for a playout in chronological order."
  [ds playout-id]
  (db/query ds (-> (h/select :*)
                   (h/from :playout-events)
                   (h/where [:= :playout-id playout-id])
                   (h/order-by :start-at)
                   sql/format)))

(defn list-events-in-window
  "Returns events overlapping [from, to) across all playouts."
  [ds from to]
  (db/query ds (-> (h/select :pe.* :c.uuid :c.name :c.number)
                   (h/from [:playout-events :pe])
                   (h/join [:playouts :p] [:= :pe.playout-id :p.id])
                   (h/join [:channels  :c] [:= :p.channel-id :c.id])
                   (h/where [:and
                             [:< :pe.start-at  to]
                             [:> :pe.finish-at from]])
                   (h/order-by :c.sort-number :pe.start-at)
                   sql/format)))

(defn get-current-event
  "Returns the event currently playing on a playout, if any."
  [ds playout-id now]
  (db/query-one ds (-> (h/select :*)
                       (h/from :playout-events)
                       (h/where [:and
                                 [:= :playout-id playout-id]
                                 [:<= :start-at  now]
                                 [:>  :finish-at now]])
                       sql/format)))

(defn get-upcoming-events
  "Returns the next N events for a playout starting from `after`."
  [ds playout-id after limit]
  (db/query ds (-> (h/select :*)
                   (h/from :playout-events)
                   (h/where [:and
                             [:= :playout-id playout-id]
                             [:>= :start-at  after]])
                   (h/order-by :start-at)
                   (h/limit limit)
                   sql/format)))

(defn create-event! [ds attrs]
  (db/execute-one! ds (-> (h/insert-into :playout-events)
                          (h/values [attrs])
                          sql/format)))

(defn bulk-insert-events!
  "Inserts a batch of event maps in one statement."
  [ds events]
  (when (seq events)
    (jdbc/execute! ds
      (-> (h/insert-into :playout-events)
          (h/values events)
          sql/format))))

(defn update-event! [ds id attrs]
  (db/execute-one! ds (-> (h/update :playout-events)
                          (h/set attrs)
                          (h/where [:= :id id])
                          sql/format)))

(defn delete-event! [ds id]
  (db/execute-one! ds (-> (h/delete-from :playout-events)
                          (h/where [:= :id id])
                          sql/format)))

(defn delete-non-manual-events-after!
  "Deletes all auto-generated events for a playout starting from `from`.
   Preserves is_manual = TRUE events."
  [ds playout-id from]
  (jdbc/execute! ds
    (-> (h/delete-from :playout-events)
        (h/where [:and
                  [:= :playout-id playout-id]
                  [:>= :start-at  from]
                  [:= :is-manual  false]])
        sql/format)))

;; ---------------------------------------------------------------------------
;; Playout history
;; ---------------------------------------------------------------------------

(defn get-collection-history
  "Returns the history record for a specific collection key within a playout."
  [ds playout-id collection-key]
  (db/query-one ds (-> (h/select :*)
                       (h/from :playout-history)
                       (h/where [:and
                                 [:= :playout-id     playout-id]
                                 [:= :collection-key collection-key]])
                       (h/order-by [:aired-at :desc])
                       (h/limit 1)
                       sql/format)))

(defn upsert-history! [ds attrs]
  (jdbc/execute-one! ds
    (-> (h/insert-into :playout-history)
        (h/values [attrs])
        sql/format)
    {:return-keys true}))

(defn prune-history!
  "Deletes history rows whose events have already finished."
  [ds before]
  (jdbc/execute! ds
    (-> (h/delete-from :playout-history)
        (h/where [:<  :event-finish-at before])
        sql/format)))

;; ---------------------------------------------------------------------------
;; Playout gaps
;; ---------------------------------------------------------------------------

(defn replace-gaps!
  "Replaces all gap rows for a playout with a fresh set."
  [ds playout-id gaps]
  (jdbc/with-transaction [tx ds]
    (jdbc/execute! tx
      (-> (h/delete-from :playout-gaps)
          (h/where [:= :playout-id playout-id])
          sql/format))
    (when (seq gaps)
      (jdbc/execute! tx
        (-> (h/insert-into :playout-gaps)
            (h/values (map #(assoc % :playout-id playout-id) gaps))
            sql/format)))))
