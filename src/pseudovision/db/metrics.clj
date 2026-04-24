(ns pseudovision.db.metrics
  (:require [honey.sql         :as sql]
            [honey.sql.helpers :as h]
            [pseudovision.db.core :as db]
            [pseudovision.util.time :as t]))

(defn get-media-item-duration-secs
  "Returns total duration in seconds for the first media_version of media-item-id,
   or nil if no version row exists."
  [ds media-item-id]
  (when-let [row (db/query-one ds
                   (-> (h/select :mv.duration)
                       (h/from [:media-versions :mv])
                       (h/where [:= :mv.media-item-id media-item-id])
                       (h/order-by :mv.id)
                       (h/limit 1)
                       sql/format))]
    (when-let [^java.time.Duration d (:mv/duration row)]
      (double (.getSeconds d)))))

(defn insert-channel-view!
  "Inserts a channel_views row when a stream session begins.
   Returns the inserted row (with :id)."
  [ds channel-id]
  (db/execute-one! ds
    (-> (h/insert-into :channel-views)
        (h/values [{:channel-id channel-id
                    :started-at (t/now)}])
        (h/returning :*)
        sql/format)))

(defn insert-media-item-view!
  "Inserts a media_item_views row when a trackable media item starts playing."
  [ds {:keys [media-item-id channel-id source-type
              start-position-secs total-duration-secs]}]
  (db/execute-one! ds
    (-> (h/insert-into :media-item-views)
        (h/values [{:media-item-id       media-item-id
                    :channel-id          channel-id
                    :source-type         (name source-type)
                    :start-position-secs start-position-secs
                    :total-duration-secs total-duration-secs
                    :started-at          (t/now)}])
        (h/returning :*)
        sql/format)))

(defn end-channel-view!
  "Sets ended_at on a channel_views row. No-ops if already ended."
  [ds view-id ended-at]
  (db/execute-one! ds
    (-> (h/update :channel-views)
        (h/set {:ended-at ended-at})
        (h/where [:and [:= :id view-id] [:= :ended-at nil]])
        sql/format)))

(defn end-media-item-view!
  "Sets ended_at and computes percent_watched in SQL.
   elapsed-secs is wall-clock seconds since stream start."
  [ds view-id ended-at elapsed-secs]
  (db/execute-one! ds
    ["UPDATE media_item_views
      SET ended_at = ?,
          percent_watched = CASE
            WHEN total_duration_secs IS NOT NULL AND total_duration_secs > 0
            THEN LEAST(100.0, (start_position_secs + ?) / total_duration_secs * 100.0)
            ELSE NULL
          END
      WHERE id = ?
        AND ended_at IS NULL"
     ended-at
     (double elapsed-secs)
     view-id]))

(defn list-channel-views
  "Returns channel_views joined to channel name/uuid.
   opts: {:channel-id int, :from Instant, :to Instant, :limit int}"
  [ds opts]
  (db/query ds
    (cond-> (-> (h/select :cv.* :c.name :c.uuid)
                (h/from [:channel-views :cv])
                (h/join [:channels :c] [:= :c.id :cv.channel-id])
                (h/order-by [:cv.started-at :desc])
                (h/limit (or (:limit opts) 200)))
      (:channel-id opts) (h/where [:= :cv.channel-id (:channel-id opts)])
      (:from opts)       (h/where [:>= :cv.started-at (:from opts)])
      (:to opts)         (h/where [:< :cv.started-at (:to opts)])
      true               sql/format)))

(defn list-media-item-views
  "Returns media_item_views joined to channel name/uuid and metadata title.
   opts: {:channel-id int, :media-item-id int, :from Instant, :to Instant, :limit int}"
  [ds opts]
  (db/query ds
    (cond-> (-> (h/select :mv.* [:m.title :title] :c.name :c.uuid)
                (h/from [:media-item-views :mv])
                (h/join [:channels :c] [:= :c.id :mv.channel-id])
                (h/left-join [:metadata :m] [:= :m.media-item-id :mv.media-item-id])
                (h/order-by [:mv.started-at :desc])
                (h/limit (or (:limit opts) 200)))
      (:channel-id opts)    (h/where [:= :mv.channel-id (:channel-id opts)])
      (:media-item-id opts) (h/where [:= :mv.media-item-id (:media-item-id opts)])
      (:from opts)          (h/where [:>= :mv.started-at (:from opts)])
      (:to opts)            (h/where [:< :mv.started-at (:to opts)])
      true                  sql/format)))
