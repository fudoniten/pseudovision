(ns pseudovision.http.api.daily-slots
  "Ingestion endpoint for the expanded DailySlot[] stream produced by
    Tunarr Scheduler's deterministic expander. Resolves media_id +
    media_selection_strategy into concrete playout events."
  (:require [clojure.string                :as str]
            [pseudovision.db.playouts      :as playout-db]
            [pseudovision.db.channels      :as channels-db]
            [pseudovision.db.core          :as db-core]
            [pseudovision.util.sql         :as sql-util]
            [honey.sql.helpers :as h]
            [honey.sql :as sql]
            [taoensso.timbre :as log])
  (:import [java.time Instant LocalDateTime OffsetDateTime ZoneId]))

;; ---------------------------------------------------------------------------
;; Media resolution
;; ---------------------------------------------------------------------------

(defn- parse-media-id
  "media_id strings follow 'type:identifier' convention.
   Returns [type identifier] or nil if unparseable."
  [s]
  (when (seq s)
    (let [idx (.indexOf s ":")]
      (when (and (>= idx 0) (< idx (dec (count s))))
        [(subs s 0 idx) (subs s (inc idx))]))))

(defn- resolve-show-episodes
  "Returns ordered episodes for a show identified by id or remote_key."
  [ds show-id-or-key]
  (let [;; Try to resolve by internal id or remote_key
        show (or (db-core/query-one ds
                    (-> (h/select :mi.id)
                        (h/from [:media-items :mi])
                        (h/where [:= :mi.id (try (Long/parseLong show-id-or-key)
                                                (catch Exception _ -1))])
                        sql/format))
                 (db-core/query-one ds
                    (-> (h/select :mi.id)
                        (h/from [:media-items :mi])
                        (h/where [:= :mi.remote-key show-id-or-key])
                        sql/format)))]
    (if show
      (db-core/query ds
        (-> (h/select :mi.* [:mv.duration :duration])
            (h/from [:media-items :mi])
            (h/left-join [:media-versions :mv] [:= :mv.media-item-id :mi.id])
            (h/where [:and [:= :mi.parent-id (:media-items/id show)]
                           [:= :mi.kind (sql-util/->pg-enum "media_item_kind" "episode")]
                           [:= :mi.state (sql-util/->pg-enum "media_item_state" "normal")]])
            (h/order-by :mi.position :mi.id)
            sql/format))
      [])))

(defn- resolve-movie
  "Returns a movie item by id or remote_key."
  [ds movie-id-or-key]
  (or (db-core/query-one ds
         (-> (h/select :mi.* [:mv.duration :duration])
             (h/from [:media-items :mi])
             (h/left-join [:media-versions :mv] [:= :mv.media-item-id :mi.id])
              (h/where [:and [:= :mi.id (try (Long/parseLong movie-id-or-key)
                                             (catch Exception _ -1))]
                             [:= :mi.kind (sql-util/->pg-enum "media_item_kind" "movie")]])
              sql/format))
      (db-core/query-one ds
         (-> (h/select :mi.* [:mv.duration :duration])
             (h/from [:media-items :mi])
             (h/left-join [:media-versions :mv] [:= :mv.media-item-id :mi.id])
             (h/where [:and [:= :mi.remote-key movie-id-or-key]
                            [:= :mi.kind (sql-util/->pg-enum "media_item_kind" "movie")]])
             sql/format))))

(defn- resolve-by-tag
  "Returns items tagged with `tag`, ordered by id."
  [ds tag]
  (db-core/query ds
    (-> (h/select :mi.* [:mv.duration :duration])
        (h/from [:media-items :mi])
        (h/join [:metadata :m] [:= :m.media-item-id :mi.id])
        (h/join [:metadata-tags :mt] [:= :mt.metadata-id :m.id])
        (h/left-join [:media-versions :mv] [:= :mv.media-item-id :mi.id])
        (h/where [:and [:= :mt.name tag]
                       [:= :mi.state (sql-util/->pg-enum "media_item_state" "normal")]])
        (h/order-by :mi.id)
        sql/format)))

(defn- last-aired-episode-index
  "Finds the index of the most recently aired episode for a show in a playout.
   Returns nil if no episode has aired yet."
  [ds playout-id show-id]
  (let [show (db-core/query-one ds
               (-> (h/select :mi.id)
                   (h/from [:media-items :mi])
                   (h/where [:and [:= :mi.id show-id]
                                  [:= :mi.kind (sql-util/->pg-enum "media_item_kind" "show")]])
                   sql/format))]
    (when show
      (let [last-event (db-core/query-one ds
                         (-> (h/select :pe.media-item-id)
                             (h/from [:playout-events :pe])
                             (h/join [:media-items :mi2] [:= :mi2.id :pe.media-item-id])
                             (h/where [:and [:= :pe.playout-id playout-id]
                                            [:= :mi2.parent-id show-id]
                                            [:= :mi2.kind (sql-util/->pg-enum "media_item_kind" "episode")]])
                             (h/order-by [:pe.start-at :desc])
                             (h/limit 1)
                             sql/format))]
        (when last-event
          ;; Find the position of this episode in the show's episode list
          (let [episodes (resolve-show-episodes ds (str (:media-items/id show)))
                ep-id  (:playout-events/media-item-id last-event)]
            (first (keep-indexed (fn [idx ep]
                                   (when (= (:media-items/id ep) ep-id)
                                     idx))
                                 episodes))))))))

(defn- pick-episode
  "Selects an episode from `episodes` based on `strategy`.
   `playout-id` and `show-id` are used for sequential tracking."
  [ds playout-id show-id episodes strategy]
  (let [n (count episodes)]
    (when (pos? n)
      (case strategy
        "sequential"
        (let [last-idx (last-aired-episode-index ds playout-id show-id)
              next-idx (if (nil? last-idx) 0 (mod (inc last-idx) n))]
          (nth episodes next-idx))

        "specific"
        ;; specific with a series ID is ambiguous; pick the first episode
        (first episodes)

        ;; "random" (default)
        (nth episodes (rand-int n))))))

(defn- pick-item
  "Resolves a DailySlot's media_id to a concrete media-item row.
   Returns [item error-message] or [nil error]."
  [ds playout-id media-id strategy category-filters]
  (if-let [parsed (parse-media-id media-id)]
    (let [type (first parsed)
          id   (second parsed)
          items (case type
                  "series" (resolve-show-episodes ds id)
                  "movie"  (when-let [m (resolve-movie ds id)] [m])
                  "random" (resolve-by-tag ds id)
                  [])]
      ;; Apply category_filters (tag filters) if provided
      (let [filtered (if (seq category-filters)
                        (let [item-ids (map :media-items/id items)
                              ;; Bulk-fetch tags for these items
                              tag-rows (when (seq item-ids)
                                         (db-core/query ds
                                           (-> (h/select :m.media-item-id :mt.name)
                                               (h/from [:metadata-tags :mt])
                                               (h/join [:metadata :m] [:= :m.id :mt.metadata-id])
                                               (h/where [:in :m.media-item-id item-ids])
                                               sql/format)))
                              item-tags (group-by :metadata/media-item-id
                                                  (map (fn [r]
                                                         {:item-id (:metadata/media-item-id r)
                                                          :tag (:metadata-tags/name r)})
                                                       tag-rows))]
                          (filterv (fn [item]
                                     (let [tags (set (map :tag (get item-tags (:media-items/id item))))]
                                       (every? #(contains? tags %) category-filters)))
                                   items))
                        items)]
        (if (seq filtered)
          (case type
            "series" (if-let [ep (pick-episode ds playout-id
                                                (try (Long/parseLong id)
                                                     (catch Exception _ -1))
                                                filtered strategy)]
                       [ep nil]
                       [nil (str "No episode selected for series " id)])
            (let [pick (case strategy
                         "random" (nth filtered (rand-int (count filtered)))
                         "specific" (first filtered)
                         ;; sequential for non-series means pick first
                         (first filtered))]
              [pick nil]))
          [nil (str "No playable items found for media_id " media-id)])))
    [nil (str "Invalid media_id format: " media-id)]))

;; ---------------------------------------------------------------------------
;; Event creation
;; ---------------------------------------------------------------------------

(def ^:private default-slot-zone
  "Zone used to interpret naive (offset-less) DailySlot datetimes. The expander
   emits naive local wall-clock times; UTC matches the scheduler's default
   zone (see pseudovision.scheduling.core)."
  (ZoneId/of "UTC"))

(defn- ->instant
  "Parses a DailySlot datetime value into a java.time.Instant.

   Accepts:
     - a java.time.Instant (returned as-is)
     - a full ISO-8601 instant with offset/zone, e.g. \"2026-06-29T00:00:00Z\"
       or \"2026-06-29T00:00:00+02:00\"
     - a naive local ISO datetime, e.g. \"2026-06-29T00:00:00\", which is
       interpreted in `default-slot-zone` (UTC)

   Returns nil for nil, blank, or unparseable input."
  [x]
  (cond
    (nil? x)              nil
    (instance? Instant x) x
    (string? x)
    (let [s (str/trim x)]
      (when (seq s)
        (or
         ;; Full instant: has a trailing 'Z' or numeric offset.
         (try (Instant/parse s)               (catch Exception _ nil))
         ;; Offset datetime without instant-normalisation (defensive).
         (try (.toInstant (OffsetDateTime/parse s)) (catch Exception _ nil))
         ;; Naive local datetime ("YYYY-MM-DDTHH:MM:SS"): assume UTC.
         (try (.toInstant (.atZone (LocalDateTime/parse s) default-slot-zone))
              (catch Exception _ nil)))))
    :else x))

(defn- create-event-from-slot
  "Creates a playout_event map from a DailySlot. Returns [event error] or [nil nil]."
  [ds playout-id slot guide-group]
  (let [raw-start (:start-time slot)
        raw-end   (:end-time slot)
        start (->instant raw-start)
        end   (->instant raw-end)
        media-id (:media-id slot)
        strategy (or (:media-selection-strategy slot) "random")
        filters  (:category-filters slot)]
    (cond
      (str/blank? (str raw-start)) [nil "Missing start_time"]
      (nil? start) [nil (str "Invalid start_time: " raw-start)]
      (str/blank? (str raw-end))   [nil "Missing end_time"]
      (nil? end)   [nil (str "Invalid end_time: " raw-end)]
      (nil? media-id) [nil "Missing media_id"]
      :else
      (let [[item err] (pick-item ds playout-id media-id strategy filters)]
        (if item
          [{:playout-id     playout-id
            :media-item-id  (:media-items/id item)
            :kind           (sql-util/->pg-enum "event_kind" "content")
            :start-at       start
            :finish-at      end
            :guide-group    guide-group
            :slot-id        nil
            :is-manual      false
            :custom-title   (when (seq (:notes slot))
                              (str/join "; " (:notes slot)))}
           nil]
          [nil err])))))

;; ---------------------------------------------------------------------------
;; Public handler
;; ---------------------------------------------------------------------------

(defn ingest-daily-slots-handler
  "POST /api/channels/:channel-id/daily-slots

   Body: DailySlot[]

   For each slot:
     1. Resolves media_id -> concrete media_item_id
     2. Creates a playout_event spanning [start_time, end_time]
   Events are inserted in bulk. Existing events in the date range are cleared
   (non-manual only) so the daily-slot stream always wins."
  [{:keys [db]}]
  (fn [req]
    (let [raw-id     (get-in req [:parameters :path :channel-id])
          channel-id (try (Long/parseLong (str raw-id))
                         (catch Exception _ nil))
          ch         (when channel-id (channels-db/get-channel db channel-id))
          real-id    (if ch channel-id
                       (when-let [ch2 (channels-db/get-channel-by-number db (str raw-id))]
                         (:channels/id ch2)))
          playout    (when real-id (playout-db/get-playout-for-channel db real-id))]
      (if-not playout
        {:status 404 :body {:error "No playout for this channel"}}
        (let [slots     (get-in req [:parameters :body])
              playout-id (:playouts/id playout)
              ;; Find the overall time range so we can clear existing events
              starts    (keep #(->instant (:start-time %)) slots)
              ends      (keep #(->instant (:end-time %)) slots)
              from      (when (seq starts) (first (sort-by #(.getEpochSecond %) starts)))
              to        (when (seq ends)   (last (sort-by #(.getEpochSecond %) ends)))]
          (log/info "Ingesting daily slots"
                    {:channel-id real-id
                     :playout-id playout-id
                     :slot-count (count slots)
                     :from (str from)
                     :to   (str to)})
          ;; Clear existing non-manual events in the range
          (when (and from to)
            (playout-db/delete-events! db playout-id
                                       {:keep-manual? true
                                        :from from
                                        :to   to}))
          ;; Create events
          (let [results (doall (map-indexed
                                 (fn [idx slot]
                                   (let [gg idx
                                         [event err] (create-event-from-slot db playout-id slot gg)]
                                     (if event
                                       {:ok event}
                                       {:error err})))
                                 slots))
                ok-events (keep :ok results)
                errors    (keep :error results)]
            (when (seq ok-events)
              (playout-db/bulk-insert-events! db ok-events))
            (log/info "Daily slot ingestion complete"
                      {:channel-id real-id
                       :ingested (count ok-events)
                       :skipped  (count errors)
                       :errors   errors})
            {:status 200
             :body {:ingested   (count ok-events)
                    :skipped    (count errors)
                    :errors     (vec errors)
                    :channel_id real-id}}))))))
