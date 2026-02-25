(ns pseudovision.scheduling.core
  "Playout build engine.

   Entry points:
     (build! db opts playout)   — full rebuild from scratch
     (rebuild! db opts playout) — incremental: extend the timeline forward

   The engine walks the schedule's slots in order, advancing a cursor
   through the content collections and emitting playout_events rows.

   Slot fill modes:
     :once  — emit one item then advance to next slot
     :count — emit exactly N items then advance
     :block — fill a fixed duration, pad tail with filler, then advance
     :flood — fill until the next fixed-anchor slot, then advance

   Slot anchors:
     :fixed      — slot starts at a specific wall-clock time of day
     :sequential — slot starts immediately when the previous one ends"
  (:require [next.jdbc                   :as jdbc]
            [pseudovision.db.media       :as media-db]
            [pseudovision.db.playouts    :as playout-db]
            [pseudovision.db.collections :as col-db]
            [pseudovision.scheduling.cursor      :as cursor]
            [pseudovision.scheduling.enumerators :as enum]
            [pseudovision.scheduling.filler      :as filler]
            [pseudovision.util.time :as t]
            [taoensso.timbre        :as log])
  (:import [java.time Duration Instant ZoneId]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- collection-key [slot]
  (str "collection:" (or (:schedule_slots/collection-id slot)
                         (str "item:" (:schedule_slots/media-item-id slot)))))

(defn- load-items
  "Returns the ordered seq of playable media items for a slot."
  [db slot]
  (cond
    (:schedule_slots/collection-id slot)
    (let [coll (media-db/get-collection db (:schedule_slots/collection-id slot))]
      (col-db/resolve-collection db coll))

    (:schedule_slots/media-item-id slot)
    [(media-db/get-media-item db (:schedule_slots/media-item-id slot))]

    :else []))

(defn- item-duration [item]
  (or (some-> (:media_versions/duration item))
      (Duration/ofSeconds 0)))

(defn- next-fixed-start
  "Returns the next wall-clock Instant when `slot` would fire on or after `after`."
  [slot after zone-id]
  (let [tod     (:schedule_slots/start-time slot)    ; java.time.Duration = offset from midnight
        zdt     (.atZone after (ZoneId/of zone-id))
        midnight (.toInstant (.toLocalDate zdt)
                              (ZoneId/of zone-id))
        candidate (.plus midnight tod)]
    (if (.isAfter candidate after)
      candidate
      (.plusSeconds candidate (* 24 3600)))))

;; ---------------------------------------------------------------------------
;; Event emission for each fill mode
;; ---------------------------------------------------------------------------

(defn- emit-once
  "Emit one item, return [events cursor]."
  [db cursor slot playout-id _opts]
  (let [items   (load-items db slot)
        ckey    (collection-key slot)
        order   (keyword (or (:schedule_slots/playback-order slot) "chronological"))
        e       (cursor/get-enumerator cursor ckey items order)
        [item e'] (enum/next-item e)
        dur     (item-duration item)
        from    (:next-start cursor)
        to      (t/add-duration from dur)
        event   {:playout-id    playout-id
                 :media-item-id (:media_items/id item)
                 :kind          "content"
                 :start-at      from
                 :finish-at     to
                 :guide-group   (:next-guide-group cursor)
                 :slot-id       (:schedule_slots/id slot)
                 :is-manual     false}
        cursor' (-> cursor
                    (assoc :next-start to)
                    (cursor/save-enumerator ckey e')
                    (cursor/bump-guide-group))]
    [[event] cursor']))

(defn- emit-count
  "Emit exactly item-count items, return [events cursor]."
  [db cursor slot playout-id _opts]
  (let [items  (load-items db slot)
        ckey   (collection-key slot)
        order  (keyword (or (:schedule_slots/playback-order slot) "chronological"))
        n      (or (:schedule_slots/item-count slot) 1)
        guide  (:next-guide-group cursor)]
    (loop [i      0
           from   (:next-start cursor)
           e      (cursor/get-enumerator cursor ckey items order)
           events []]
      (if (>= i n)
        (let [cursor' (-> cursor
                          (assoc :next-start from)
                          (cursor/save-enumerator ckey e)
                          (cursor/bump-guide-group))]
          [events cursor'])
        (let [[item e'] (enum/next-item e)
              dur       (item-duration item)
              to        (t/add-duration from dur)]
          (recur (inc i) to e'
                 (conj events {:playout-id    playout-id
                               :media-item-id (:media_items/id item)
                               :kind          "content"
                               :start-at      from
                               :finish-at     to
                               :guide-group   guide
                               :slot-id       (:schedule_slots/id slot)
                               :is-manual     false})))))))

(defn- emit-block
  "Fill a fixed-duration block.  Pads the tail with filler if configured.
   Returns [events cursor]."
  [db cursor slot channel playout-id opts]
  (let [items      (load-items db slot)
        ckey       (collection-key slot)
        order      (keyword (or (:schedule_slots/playback-order slot) "chronological"))
        block-dur  (:schedule_slots/block-duration slot)
        from       (:next-start cursor)
        block-end  (t/add-duration from block-dur)
        guide      (:next-guide-group cursor)]
    (loop [cursor-time from
           e           (cursor/get-enumerator cursor ckey items order)
           events      []]
      (let [remaining (t/duration-between cursor-time block-end)]
        (cond
          ;; Block finished
          (or (.isNegative remaining) (.isZero remaining))
          (let [c' (-> cursor
                       (assoc :next-start block-end)
                       (cursor/save-enumerator ckey e)
                       (cursor/bump-guide-group))]
            [events c'])

          ;; No more items; pad the rest
          (empty? (:items e))
          (let [c' (-> cursor
                       (assoc :next-start block-end)
                       (cursor/save-enumerator ckey e)
                       (cursor/bump-guide-group))]
            [events c'])

          :else
          (let [[item e'] (enum/next-item e)
                dur       (item-duration item)
                to        (t/add-duration cursor-time dur)]
            (if (.isAfter to block-end)
              ;; Item overflows the block — respect tail_mode
              (let [tail-events
                    (case (:schedule_slots/tail-mode slot "none")
                      "filler" []   ;; TODO: inject tail filler
                      "offline" []  ;; TODO: inject offline segment
                      [])]          ;; "none": just leave the gap
                (let [c' (-> cursor
                             (assoc :next-start block-end)
                             (cursor/save-enumerator ckey e)
                             (cursor/bump-guide-group))]
                  [(into events tail-events) c']))
              (recur to e'
                     (conj events {:playout-id    playout-id
                                   :media-item-id (:media_items/id item)
                                   :kind          "content"
                                   :start-at      cursor-time
                                   :finish-at     to
                                   :guide-group   guide
                                   :slot-id       (:schedule_slots/id slot)
                                   :is-manual     false})))))))))

(defn- emit-flood
  "Fill from now until the next fixed-anchor slot.
   Returns [events cursor]."
  [db cursor slot channel playout-id {:keys [flood-end] :as opts}]
  ;; Flood fills from :next-start up to flood-end (the next fixed-anchor time).
  (let [items  (load-items db slot)
        ckey   (collection-key slot)
        order  (keyword (or (:schedule_slots/playback-order slot) "chronological"))
        guide  (:next-guide-group cursor)
        end    (or flood-end
                   (t/add-duration (:next-start cursor) (t/hours->duration 2)))]
    (loop [cursor-time (:next-start cursor)
           e           (cursor/get-enumerator cursor ckey items order)
           events      []]
      (let [remaining (t/duration-between cursor-time end)]
        (if (or (.isNegative remaining) (.isZero remaining) (empty? (:items e)))
          (let [c' (-> cursor
                       (assoc :next-start end)
                       (cursor/save-enumerator ckey e)
                       (cursor/bump-guide-group))]
            [events c'])
          (let [[item e'] (enum/next-item e)
                dur       (item-duration item)
                to        (t/add-duration cursor-time dur)]
            (if (.isAfter to end)
              (let [c' (-> cursor
                           (assoc :next-start end)
                           (cursor/save-enumerator ckey e)
                           (cursor/bump-guide-group))]
                [events c'])
              (recur to e'
                     (conj events {:playout-id    playout-id
                                   :media-item-id (:media_items/id item)
                                   :kind          "content"
                                   :start-at      cursor-time
                                   :finish-at     to
                                   :guide-group   guide
                                   :slot-id       (:schedule_slots/id slot)
                                   :is-manual     false})))))))))

;; ---------------------------------------------------------------------------
;; Slot dispatch
;; ---------------------------------------------------------------------------

(defn- next-slot-start
  "If the next slot is fixed-anchor, returns its next fire time;
   otherwise returns nil."
  [slots slot-idx now zone-id]
  (let [next-idx (inc slot-idx)
        next     (when (< next-idx (count slots)) (nth slots next-idx))]
    (when (= "fixed" (some-> next :schedule_slots/anchor))
      (next-fixed-start next now zone-id))))

(defn- process-slot
  "Processes one slot, advancing cursor and collecting events.
   Returns [events cursor]."
  [db cursor slot channel playout-id slots opts]
  (let [zone-id (get opts :zone-id "UTC")
        fill    (keyword (or (:schedule_slots/fill-mode slot) "once"))]
    (case fill
      :once  (emit-once  db cursor slot playout-id opts)
      :count (emit-count db cursor slot playout-id opts)
      :block (emit-block db cursor slot channel playout-id opts)
      :flood (let [flood-end (next-slot-start
                               slots
                               (:schedule_slots/slot-index slot)
                               (:next-start cursor)
                               zone-id)]
               (emit-flood db cursor slot channel playout-id
                           (assoc opts :flood-end flood-end)))
      (do (log/warn "Unknown fill mode, skipping slot" {:fill fill})
          [[] cursor]))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn build!
  "Builds the playout from scratch, replacing all non-manual events.
   Runs inside a transaction; saves the updated cursor on success."
  [db opts playout]
  (let [channel-id  (:playouts/channel-id playout)
        schedule-id (:playouts/schedule-id playout)
        playout-id  (:playouts/id playout)
        channel     (when channel-id
                      (pseudovision.db.channels/get-channel db channel-id))
        schedule    (when schedule-id
                      (pseudovision.db.schedules/get-schedule db schedule-id))
        slots       (when schedule-id
                      (pseudovision.db.schedules/list-slots db schedule-id))
        now         (t/now)
        horizon     (t/add-duration now (t/hours->duration
                                          (get opts :lookahead-hours 72)))]
    (if (or (nil? schedule) (empty? slots))
      (do (log/warn "No schedule or slots; nothing to build"
                    {:playout-id playout-id})
          :no-schedule)
      (let [saved-cursor (cursor/<-json (:playouts/cursor playout))
            initial-cur  (or saved-cursor (cursor/init now))]
        (jdbc/with-transaction [tx db]
          ;; Remove all auto-generated events from the future horizon
          (playout-db/delete-non-manual-events-after! tx playout-id now)

          (loop [cursor   initial-cur
                 slot-idx 0
                 events   []]
            (let [slot  (nth slots slot-idx nil)
                  start (:next-start cursor)]
              (if (or (nil? slot) (.isAfter start horizon))
                ;; Done; flush events and save cursor
                (do
                  (playout-db/bulk-insert-events! tx events)
                  (playout-db/update-playout! tx playout-id
                    {:cursor         (cursor/->json cursor)
                     :last-built-at  now
                     :build-success  true
                     :build-message  nil})
                  (log/info "Build complete" {:playout-id playout-id
                                              :events     (count events)}))
                ;; Process this slot
                (let [[new-events cursor'] (process-slot
                                             tx cursor slot channel
                                             playout-id slots opts)
                      cursor''  (cursor/advance-slot cursor' (count slots))]
                  (recur cursor'' (mod (inc slot-idx) (count slots))
                         (into events new-events)))))))))))

(defn rebuild!
  "Alias for build!; triggers a full rebuild from the saved cursor position."
  [db opts playout]
  (build! db opts playout))
