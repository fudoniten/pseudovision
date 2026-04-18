(ns pseudovision.scheduling.engine
  "Scheduling engine for generating playout timelines from schedules.
   
   Converts abstract schedules (slots with collections/tags) into concrete
   playout events (media items with start/finish times)."
  (:require [pseudovision.db.core :as db]
            [pseudovision.db.playouts :as db-playouts]
            [pseudovision.db.schedules :as db-schedules]
            [pseudovision.util.time :as t]
            [honey.sql.helpers :as h]
            [honey.sql :as sql]
            [taoensso.timbre :as log])
  (:import [java.util Random]))

;; ---------------------------------------------------------------------------
;; Content Selection
;; ---------------------------------------------------------------------------

(defn query-collection-items
  "Query all media items in a collection with their durations."
  [ds collection-id]
  (db/query ds (-> (h/select :mi.id :mi.duration :m.title :m.release-date)
                   (h/from [:media-items :mi])
                   (h/left-join [:metadata :m] [:= :m.media-item-id :mi.id])
                   (h/where [:= :mi.collection-id collection-id])
                   sql/format)))

(defn select-slot-content
  "Select content for a slot based on collection_id or media_item_id."
  [ds slot]
  (cond
    ;; Specific item takes priority
    (:schedule-slots/media-item-id slot)
    [(db/query-one ds (-> (h/select :mi.id :mi.duration :m.title)
                         (h/from [:media-items :mi])
                         (h/left-join [:metadata :m] [:= :m.media-item-id :mi.id])
                         (h/where [:= :mi.id (:schedule-slots/media-item-id slot)])
                         sql/format))]
    
    ;; Collection-based selection
    (:schedule-slots/collection-id slot)
    (query-collection-items ds (:schedule-slots/collection-id slot))
    
    ;; No content specified
    :else
    (do
      (log/error "Slot has no content source" {:slot-id (:schedule-slots/id slot)})
      [])))

;; ---------------------------------------------------------------------------
;; Playback Order
;; ---------------------------------------------------------------------------

(defn shuffle-with-seed
  "Shuffle items using a seeded random number generator for reproducibility."
  [items seed]
  (let [rng (Random. seed)
        indexed (map-indexed vector items)
        shuffled (loop [remaining indexed
                       result []]
                  (if (empty? remaining)
                    result
                    (let [idx (.nextInt rng (count remaining))
                          chosen (nth remaining idx)]
                      (recur (concat (take idx remaining) (drop (inc idx) remaining))
                             (conj result (second chosen))))))]
    shuffled))

(defn apply-playback-order-sequential
  "Return items in chronological order (by release-date, then title)."
  [items seed cursor]
  (let [sorted (sort-by (juxt :metadata/release-date :metadata/title) items)
        start-index (get cursor :sequential-index 0)]
    {:items (drop start-index sorted)
     :cursor-update {:sequential-index 0}}))  ; Will be updated as items are consumed

(defn apply-playback-order-random
  "Return items in random order (new random each call)."
  [items seed cursor]
  (let [call-count (get cursor :random-call-count 0)
        effective-seed (+ seed call-count)]
    {:items (shuffle-with-seed items effective-seed)
     :cursor-update {:random-call-count (inc call-count)}}))

(defn apply-playback-order-shuffle
  "Return items in shuffled order (shuffle once, remember order)."
  [items seed cursor]
  (if-let [shuffled-order (get cursor :shuffled-order)]
    ;; Already shuffled - use existing order
    (let [start-index (get cursor :shuffle-index 0)
          reordered (map #(nth items %) shuffled-order)]
      {:items (drop start-index reordered)
       :cursor-update {:shuffle-index 0}})
    ;; First shuffle - create and save order
    (let [shuffled (shuffle-with-seed items seed)
          indices (map #(.indexOf items %) shuffled)]
      {:items shuffled
       :cursor-update {:shuffled-order indices
                      :shuffle-index 0}})))

(defn apply-playback-order-semi-sequential
  "Return items in semi-sequential order: N items in sequence, then jump randomly.
   
   Uses deterministic algorithm:
   - batch-size: How many items to play sequentially
   - batch-offset: Which batch we're on (increments after completing batch)
   - within-batch-index: Position within current batch (0 to batch-size-1)"
  [items batch-size seed cursor]
  (let [total (count items)
        batch-offset (get cursor :semi-seq-batch-offset 0)
        within-batch-index (get cursor :semi-seq-within-batch-index 0)
        
        ;; Use seed + batch-offset to deterministically pick batch start
        rng (Random. (long (+ seed batch-offset)))
        batch-start (.nextInt rng total)
        
        ;; Get batch items (wrapping around if needed)
        batch-items (map #(nth items (mod (+ batch-start %) total))
                        (range batch-size))
        
        ;; Return items starting from current within-batch position
        remaining-in-batch (drop within-batch-index batch-items)]
    
    {:items remaining-in-batch
     :cursor-update {:semi-seq-batch-offset batch-offset
                    :semi-seq-within-batch-index within-batch-index}}))

(defn apply-playback-order
  "Apply playback order to items, returning ordered items and cursor updates.
   
   Returns: {:items [...] :cursor-update {...}}"
  [items playback-order slot seed cursor]
  (case (keyword playback-order)
    :chronological
    (apply-playback-order-sequential items seed cursor)
    
    :random
    (apply-playback-order-random items seed cursor)
    
    :shuffle
    (apply-playback-order-shuffle items seed cursor)
    
    :semi_sequential
    (let [batch-size (or (:schedule-slots/semi-seq-batch-size slot) 5)]
      (apply-playback-order-semi-sequential items batch-size seed cursor))
    
    ;; marathon, random_rotation not yet implemented
    (do
      (log/warn "Playback order not implemented, using chronological" {:order playback-order})
      (apply-playback-order-sequential items seed cursor))))

;; ---------------------------------------------------------------------------
;; Fill Mode Execution
;; ---------------------------------------------------------------------------

(defn pack-items-to-count
  "Take exactly N items from the ordered list."
  [items count]
  (take count items))

(defn pack-items-to-duration
  "Pack items until target duration is reached.
   Returns items that fit completely within duration."
  [items target-duration-secs]
  (loop [remaining items
         packed []
         time-used 0]
    (if (empty? remaining)
      {:items packed :total-duration time-used}
      (let [item (first remaining)
            item-duration (:media-items/duration item)]
        (if (<= (+ time-used item-duration) target-duration-secs)
          (recur (rest remaining)
                 (conj packed item)
                 (+ time-used item-duration))
          ;; This item won't fit - stop
          {:items packed :total-duration time-used})))))

(defn pack-items-flood
  "Pack items from start-time until end-time, looping if necessary."
  [items start-time end-time]
  (let [total-duration (t/duration->seconds (t/duration-between start-time end-time))]
    (loop [remaining (cycle items)  ; Infinite cycle
           packed []
           time-used 0]
      (if (or (empty? remaining) (>= time-used total-duration))
        {:items packed :total-duration time-used}
        (let [item (first remaining)
              item-duration (:media-items/duration item)]
          (if (<= (+ time-used item-duration) total-duration)
            (recur (rest remaining)
                   (conj packed item)
                   (+ time-used item-duration))
            ;; This item won't fit - stop (don't cut items in flood mode)
            {:items packed :total-duration time-used}))))))

(defn execute-fill-mode
  "Execute slot fill mode to determine which items to play.
   
   Returns: {:items [...] :total-duration secs :cursor-update {...}}"
  [slot ordered-items current-time next-fixed-time horizon]
  (let [fill-mode (keyword (:schedule-slots/fill-mode slot))]
    (case fill-mode
      :once
      {:items (take 1 ordered-items)
       :total-duration (:media-items/duration (first ordered-items) 0)
       :cursor-update {}}
      
      :count
      (let [count (:schedule-slots/item-count slot 1)
            taken (pack-items-to-count ordered-items count)
            duration (reduce + (map :media-items/duration taken))]
        {:items taken
         :total-duration duration
         :cursor-update {}})
      
      :block
      (let [block-duration-secs (:schedule-slots/block-duration slot)  ; PostgreSQL interval as seconds
            packed (pack-items-to-duration ordered-items block-duration-secs)]
        {:items (:items packed)
         :total-duration (:total-duration packed)
         :cursor-update {}})
      
      :flood
      (let [end-time (or next-fixed-time horizon)
            packed (pack-items-flood ordered-items current-time end-time)]
        {:items (:items packed)
         :total-duration (:total-duration packed)
         :cursor-update {}})
      
      ;; Default: play once
      (do
        (log/warn "Unknown fill mode, using 'once'" {:fill-mode fill-mode})
        {:items (take 1 ordered-items)
         :total-duration (:media-items/duration (first ordered-items) 0)
         :cursor-update {}}))))

;; ---------------------------------------------------------------------------
;; Event Generation
;; ---------------------------------------------------------------------------

(defn generate-playout-events-for-items
  "Convert packed items into playout_event records with start/finish times."
  [items playout-id start-time]
  (loop [remaining items
         current-time start-time
         events []]
    (if (empty? remaining)
      events
      (let [item (first remaining)
            duration (:media-items/duration item)
            finish-time (t/add-seconds current-time duration)]
        (recur (rest remaining)
               finish-time
               (conj events
                     {:playout-id playout-id
                      :media-item-id (:media-items/id item)
                      :start-at current-time
                      :finish-at finish-time
                      :kind "content"
                      :in-point 0
                      :guide-group 0}))))))

;; ---------------------------------------------------------------------------
;; Core Scheduling Engine
;; ---------------------------------------------------------------------------

(defn generate-schedule!
  "Generate playout events for a playout from start-time to horizon.
   
   Args:
   - ds: Datasource
   - playout-id: ID of playout to generate for
   - start-time: When to start generating (Instant)
   - horizon: When to stop generating (Instant)
   
   Returns: Number of events generated"
  [ds playout-id start-time horizon]
  (log/info "Starting schedule generation" 
            {:playout-id playout-id 
             :start-time start-time 
             :horizon horizon})
  
  (let [playout (db-playouts/get-playout ds playout-id)
        schedule-id (:playouts/schedule-id playout)]
    
    (if-not schedule-id
      (do
        (log/warn "Playout has no schedule attached" {:playout-id playout-id})
        0)
      
      (let [schedule (db-schedules/get-schedule ds schedule-id)
            slots (db-schedules/list-slots ds schedule-id)
            seed (:playouts/seed playout)
            cursor (:playouts/cursor playout {})]
        
        (if (empty? slots)
          (do
            (log/warn "Schedule has no slots" {:schedule-id schedule-id})
            0)
          
          ;; Process slots sequentially
          ;; For MVP: Only sequential slots, no fixed-time support yet
          (loop [current-time start-time
                 slot-idx 0
                 all-events []
                 slot-cursor (get cursor (str "slot-" slot-idx) {})]
            
            (if (or (>= slot-idx (count slots))
                   (t/after? current-time horizon))
              ;; Done - insert events and save cursor
              (do
                (log/info "Generated events" 
                          {:count (count all-events)
                           :start (t/instant->str (:start-at (first all-events)))
                           :end (t/instant->str (:finish-at (last all-events)))})
                
                ;; Insert events into database
                (when (seq all-events)
                  (db-playouts/bulk-insert-events! ds all-events))
                
                ;; Save cursor state
                (let [new-cursor (assoc cursor (str "slot-" (dec slot-idx)) slot-cursor)]
                  (db-playouts/save-cursor! ds playout-id new-cursor))
                
                (count all-events))
              
              ;; Process next slot
              (let [slot (nth slots slot-idx)
                    
                    _ (log/debug "Processing slot" {:slot-idx slot-idx 
                                                    :fill-mode (:schedule-slots/fill-mode slot)
                                                    :collection-id (:schedule-slots/collection-id slot)})
                    
                    ;; Select content for this slot
                    available-items (select-slot-content ds slot)
                    
                    _ (when (empty? available-items)
                        (log/warn "Slot has no available items" {:slot-id (:schedule-slots/id slot)}))
                    
                    ;; Apply playback order
                    order-result (when (seq available-items)
                                  (apply-playback-order available-items 
                                                       (:schedule-slots/playback-order slot "chronological")
                                                       slot
                                                       seed
                                                       slot-cursor))
                    ordered-items (:items order-result)
                    
                    ;; Determine next fixed slot time (for flood mode)
                    next-fixed-time (when (< (inc slot-idx) (count slots))
                                     (let [next-slot (nth slots (inc slot-idx))]
                                       (when (= (:schedule-slots/anchor next-slot) "fixed")
                                         ;; TODO: Calculate next fixed time from start-time
                                         ;; For now, return nil (flood will use horizon)
                                         nil)))
                    
                    ;; Execute fill mode
                    fill-result (when (seq ordered-items)
                                 (execute-fill-mode slot 
                                                   ordered-items 
                                                   current-time 
                                                   next-fixed-time 
                                                   horizon))
                    
                    ;; Generate events for selected items
                    events (when fill-result
                            (generate-playout-events-for-items (:items fill-result)
                                                               playout-id
                                                               current-time))
                    
                    ;; Calculate next start time
                    slot-end (if (seq events)
                              (:finish-at (last events))
                              current-time)
                    
                    ;; Update slot cursor
                    updated-slot-cursor (merge slot-cursor 
                                              (:cursor-update order-result {})
                                              (:cursor-update fill-result {}))]
                
                (recur slot-end
                       (inc slot-idx)
                       (concat all-events events)
                       updated-slot-cursor))))))))

;; ---------------------------------------------------------------------------
;; Rebuild API
;; ---------------------------------------------------------------------------

(defn rebuild-from-now!
  "Delete all future events and regenerate from NOW.
   Used when configuration changes."
  [ds playout-id horizon-days]
  (let [now (t/now)
        horizon (t/add-days now horizon-days)]
    
    (log/info "Rebuilding playout from NOW" 
              {:playout-id playout-id :horizon-days horizon-days})
    
    ;; Delete all auto-generated events after NOW (preserves manual events)
    (db-playouts/delete-non-manual-events-after! ds playout-id now)
    
    ;; Generate new events
    (generate-schedule! ds playout-id now horizon)))

(defn rebuild-horizon!
  "Generate events for days beyond current horizon (daily rebuild).
   Does not modify existing events."
  [ds playout-id current-horizon-days new-horizon-days]
  (let [now (t/now)
        current-horizon (t/add-days now current-horizon-days)
        new-horizon (t/add-days now new-horizon-days)]
    
    (log/info "Extending playout horizon" 
              {:playout-id playout-id 
               :from-days current-horizon-days 
               :to-days new-horizon-days})
    
    ;; Generate events from current horizon to new horizon
    ;; Existing events remain untouched
    (generate-schedule! ds playout-id current-horizon new-horizon)))
)
