# Pseudovision Scheduling System - Architecture & Design

**Status:** 📋 Design Complete, Implementation Pending  
**Last Updated:** 2026-04-18  
**Design Session:** Architecture discussion with requirements analysis

---

## Overview

Pseudovision's scheduling system is designed to support sophisticated IPTV channel programming with:
- Tag-based content selection
- Mixed fixed/sequential scheduling
- Intelligent gap filling with filler content
- Special event overrides
- Semi-sequential playback patterns
- Integration with external schedulers (tunarr-scheduler)

This document captures the complete architecture, design decisions, and implementation plan.

---

## Building Blocks

### 1. **Media Tags** (Foundation for Content Selection)

**Purpose:** Enable flexible content queries like `(#child-friendly #daytime #mystery #channel-name)`

**Schema:**
```sql
CREATE TABLE media_tags (
    id            SERIAL PRIMARY KEY,
    media_item_id INTEGER NOT NULL REFERENCES media_items(id) ON DELETE CASCADE,
    tag           TEXT NOT NULL,
    source        TEXT DEFAULT 'manual',  -- 'manual', 'jellyfin', 'tunabrain', 'tunarr-scheduler'
    created_at    TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (media_item_id, tag)
);

CREATE INDEX ix_media_tags_tag ON media_tags(tag);
CREATE INDEX ix_media_tags_item ON media_tags(media_item_id);
```

**API:**
- `POST /api/media-items/:id/tags` - Bulk add tags `{tags: ["mystery", "drama"]}`
- `GET /api/media-items/:id/tags` - List tags for item
- `GET /api/tags` - List all unique tags with counts
- `DELETE /api/media-items/:id/tags/:tag` - Remove specific tag

**Design Principles:**
- ✅ One-way sync from external tools (tunarr-scheduler can push tags)
- ✅ Manual tag editing supported via API/UI
- ✅ Source tracking for debugging
- ✅ Agnostic to caller - no tight coupling

**Tag Query Strategy:**
- **Phase 1:** Simple array intersection (media must have ALL required_tags, NONE of excluded_tags)
- **Phase 2:** Could extend to JSONB query DSL if needed:
  ```json
  {"all": ["mystery"], "any": ["1990s", "2000s"], "none": ["explicit"]}
  ```

---

### 2. **Collections** (Content Libraries)

**Current Implementation:** ✅ Already exists
- Links to Jellyfin collections or custom groups
- Queried via `media_items.collection_id`

**Future Enhancement:** Tag-based "virtual collections"
- Instead of static collections, define dynamic queries
- Example: "All media with tags (#child-friendly AND #mystery)"

---

### 3. **Schedules** (The Template)

**Current Implementation:** ✅ Already exists
- Named templates containing ordered slots
- Reusable across channels
- Settings:
  - `shuffle_slots` - Randomize slot order
  - `random_start_point` - Start at random position
  - `keep_multi_part_together` - Keep multi-part episodes together
  - `treat_collections_as_shows` - Group collection items
  - `fixed_start_time_behavior` - "skip" or "play" when fixed time is past

---

### 4. **Schedule Slots** (Time Blocks)

**Current Implementation:** ✅ Schema exists, engine not implemented

**Proposed Enhancement:**
```sql
ALTER TABLE schedule_slots
  -- Tag-based content selection
  ADD COLUMN required_tags TEXT[],         -- ALL tags must match
  ADD COLUMN excluded_tags TEXT[],         -- NONE of these tags
  
  -- Time-based tag injection (for daytime/nighttime variants)
  ADD COLUMN daytime_tags TEXT[],          -- Additional tags during daytime hours
  ADD COLUMN nighttime_tags TEXT[],        -- Additional tags during nighttime hours
  
  -- Semi-sequential playback
  ADD COLUMN semi_seq_batch_size INTEGER,  -- Play N items sequentially before jumping
  ADD COLUMN semi_seq_jump_mode TEXT,      -- 'random' | 'forward'
  
  -- Gap filling control
  ADD COLUMN disable_auto_gap_fill BOOLEAN DEFAULT FALSE;
```

**Slot Types:**

**Anchor Types:**
- `'fixed'` - Starts at wall-clock time (e.g., 8:00 PM daily)
  - Requires: `start_time` (INTERVAL like '20:00:00')
  - Creates "appointment TV"
  
- `'sequential'` - Starts when previous slot ends
  - Continuous flow programming

**Fill Modes:**
- `'once'` - Play exactly 1 item
- `'count'` - Play exactly N items (set `item_count`)
- `'block'` - Fill specific duration (set `block_duration`)
  - If content doesn't fit exactly, use `tail_mode`:
    - 'none' - Leave gap
    - 'filler' - Use tail_filler_id
    - 'offline' - Show offline slate
- `'flood'` - Fill until next fixed slot or horizon

**Playback Orders:**
- `'chronological'` - Play in order (by release date, episode number, etc.)
- `'random'` - Pick randomly each time (can repeat)
- `'shuffle'` - Shuffle once, play in that order, reshuffle when done
- `'random_rotation'` - Random but cycle through all before repeating
- `'marathon'` - Special marathon mode with grouping
- `'semi_sequential'` ⭐ **NEW** - Play N sequentially, jump, repeat

**Content Selection Priority:**
1. Specific `media_item_id` (if set) - highest priority
2. Tag query (`required_tags` + `excluded_tags`) - medium priority
3. Collection (`collection_id`) - lowest priority

Only one should be set per slot, checked in that order.

---

### 5. **Playouts** (The Instance)

**Current Implementation:** ✅ Exists
- One playout per channel
- Links schedule to channel
- Contains generation cursor (JSONB state)

**Proposed Enhancement:**
```sql
ALTER TABLE playouts
  ADD COLUMN generation_horizon INTERVAL DEFAULT '14 days',  -- How far ahead to generate
  ADD COLUMN last_rebuild_at TIMESTAMPTZ,                    -- Track when last rebuilt
  ADD COLUMN auto_fill_gaps BOOLEAN DEFAULT TRUE;            -- Automatically fill gaps between fixed slots
```

**Rebuild Behavior:**
- **Daily rebuild (scheduled):** Generate events for days 8-14, keep days 1-7 untouched
- **Manual rebuild (config change):** Delete all future events (from NOW forward), regenerate
- **Never modify:** Past events or currently-airing events

---

### 6. **Playout Events** (The Timeline)

**Current Implementation:** ✅ Exists
- Concrete scheduled events generated by engine
- What's actually playing and when

**Fields Used:**
- `media_item_id` - What to play
- `start_at` / `finish_at` - When to play
- `kind` - 'content', 'pre', 'mid', 'post', 'tail', 'fallback', 'offline'
- `guide_group` - Group multi-part content in EPG
- `custom_title` - Override EPG title

---

### 7. **Schedule Overrides** ⭐ **NEW TABLE**

**Purpose:** Special events without modifying base schedule

**Schema:**
```sql
CREATE TABLE schedule_overrides (
    id                    SERIAL PRIMARY KEY,
    playout_id            INTEGER NOT NULL REFERENCES playouts(id) ON DELETE CASCADE,
    name                  TEXT NOT NULL,  -- "James Bond Marathon", "Shark Week"
    description           TEXT,
    
    -- When this override is active
    override_start        TIMESTAMPTZ NOT NULL,
    override_end          TIMESTAMPTZ NOT NULL,
    
    -- What to play during override
    override_schedule_id  INTEGER REFERENCES schedules(id),  -- Use different schedule
    
    -- OR specify directly:
    collection_id         INTEGER REFERENCES collections(id),
    required_tags         TEXT[],
    playback_order        playback_order DEFAULT 'chronological',
    
    -- Priority for overlapping overrides (higher wins)
    priority              INTEGER NOT NULL DEFAULT 0,
    
    -- Metadata
    created_at            TIMESTAMPTZ DEFAULT NOW(),
    created_by            TEXT
);

CREATE INDEX ix_schedule_overrides_playout ON schedule_overrides(playout_id);
CREATE INDEX ix_schedule_overrides_dates ON schedule_overrides(override_start, override_end);
```

**Example:** Weekend James Bond marathon
```sql
INSERT INTO schedule_overrides (playout_id, name, override_start, override_end, required_tags, playback_order)
VALUES (1, 'James Bond Weekend', '2026-04-19 00:00:00', '2026-04-21 00:00:00', 
        ARRAY['james-bond'], 'chronological');
```

**API:**
- `POST /api/playouts/:id/overrides` - Create override
- `GET /api/playouts/:id/overrides` - List overrides
- `DELETE /api/overrides/:id` - Delete override

---

## Filler System Architecture

### **The Filler Hierarchy**

Pseudovision distinguishes three types of filler:

1. **Filler Collection** (10s - 30 mins)
   - Ads, bumpers, short clips, station IDs
   - Used for filling short gaps
   - Should vary to avoid repetition
   - Example content: 15s, 30s, 60s commercials

2. **Background Collection** (30+ mins)
   - Evergreen content for large gaps
   - Music videos, public domain content, documentaries
   - Plays in shuffle mode
   - Keeps channel "alive" during programming gaps

3. **Fallback Filler** (any duration)
   - Single media item that loops
   - Last resort when no collection available
   - Example: Channel branding video

4. **Generated Slate** (ultimate fallback)
   - FFmpeg-generated slate with channel info
   - Shows "Coming Up" with next scheduled content
   - No media required - always available

### **Gap Filling Decision Tree**

```
Gap Duration?
    │
    ├─ < 10 seconds
    │   └─> Black screen (silence)
    │
    ├─ 10 seconds to 30 minutes
    │   ├─> Has filler_collection_id?
    │   │   ├─ YES: Pack filler items
    │   │   │   ├─> Perfect fit or <20s remainder
    │   │   │   │   └─> Use packed items + "Up Next" slate for remainder
    │   │   │   ├─> 20s-2min remainder (within max_shift)
    │   │   │   │   └─> Shift next slot, pack more filler
    │   │   │   └─> >2min remainder
    │   │   │       └─> Use packed items + generated slate for remainder
    │   │   └─ NO: Generated slate (with "Coming Up" info)
    │   
    └─ 30+ minutes
        ├─> Has background_collection_id?
        │   ├─ YES: Play from background collection (shuffle mode)
        │   └─ NO: Try fallback_filler_id
        │       ├─ YES: Loop fallback filler
        │       └─ NO: Generated slate (offline mode)
```

### **Filler Packing Algorithm**

**Greedy Pack with Remainder Handling:**

```clojure
(defn pack-filler [items target-duration channel-config]
  (let [max-shift (:filler-max-time-shift channel-config 120)
        allow-overhang? (:filler-allow-overhang channel-config false)]
    
    ;; Shuffle to avoid playing same ads in same order
    (loop [remaining target-duration
           packed []
           available (shuffle items)]
      
      (let [fitting (filter #(<= (:duration %) remaining) available)]
        
        (cond
          ;; Perfect pack or very close
          (< remaining 1)
          {:items packed :remainder 0 :shift 0}
          
          ;; Can't fit anything, check remainder size
          (empty? fitting)
          (cond
            ;; Tiny remainder - show "Up Next" or black
            (< remaining 20)
            {:items packed 
             :remainder remaining 
             :shift 0
             :remainder-type (if (> remaining 5) :up-next-slate :black)}
            
            ;; Medium remainder - try shifting schedule
            (<= remaining max-shift)
            {:items packed
             :remainder remaining
             :shift remaining  ; Shift next slot by this amount
             :can-repack true} ; Caller should try packing with new duration
            
            ;; Large remainder - use slate
            :else
            {:items packed
             :remainder remaining
             :shift 0
             :remainder-type :generated-slate})
          
          ;; Pick best-fitting item and continue
          :else
          (let [best (apply min-key #(- remaining (:duration %)) fitting)]
            (recur (- remaining (:duration best))
                   (conj packed best)
                   (remove #{best} available))))))))
```

**Key Features:**
- ✅ Shuffles filler to avoid repetition
- ✅ Tries to fit as much as possible
- ✅ Suggests schedule shifts for better packing
- ✅ Different handling for small (<20s) vs large remainders
- ✅ "Up Next" slate for <20s gaps shows next scheduled content

---

### **Slot-Specific Filler (Deliberate Ads/Bumpers)**

Separate from automatic gap filling, slots can have deliberate filler:

```
Slot Filler Types:
├─ pre_filler_id      - Plays BEFORE content starts (station ID, bumper)
├─ mid_filler_id      - Plays DURING content (commercial breaks)
├─ post_filler_id     - Plays AFTER content ends (outro)
├─ tail_filler_id     - Pads END of block slots to hit exact duration
└─ fallback_filler_id - When slot's content is unavailable
```

These reference `filler_presets` table which can specify:
- Collection to pull from
- Number of items or duration
- Playback mode

**Difference:**
- **Gap filling:** Automatic, fills unscheduled time between slots
- **Slot filler:** Deliberate, part of the programming design

---

## Use Case Examples

### **Example 1: Daytime Kids / Evening Adults**

**Requirements:**
- 6 AM - 9 PM: Child-friendly content
- 9 PM - 6 AM: Adult content
- No explicit collections, just tags

**Schedule:**
```clojure
{:name "Family-Friendly Channel"
 :slots [
   {:slot-index 0
    :anchor "fixed"
    :start-time "06:00:00"
    :fill-mode "flood"
    :required-tags ["child-friendly" "channel-family"]
    :playback-order "random"}
   
   {:slot-index 1
    :anchor "fixed"
    :start-time "21:00:00"  ; 9 PM
    :fill-mode "flood"
    :required-tags ["channel-family"]  ; All content, not just child-friendly
    :excluded-tags ["child-only"]      ; But exclude kids shows
    :playback-order "random"}]}
```

**How it works:**
- At 6 AM: Start playing content tagged `#child-friendly #channel-family`
- Flood continues until 9 PM
- At 9 PM: Switch to content tagged `#channel-family` but NOT `#child-only`
- Flood continues until 6 AM next day
- Repeat daily

---

### **Example 2: Random TV + Fixed Primetime + Random Movies**

**Requirements:**
- 6 AM - 3 PM: Random TV shows
- 3 PM - 9 PM: Fixed sequence of specific episodes/movies
- 9 PM - 6 AM: Random movies
- Fill gaps with ads

**Schedule:**
```clojure
{:name "Mixed Programming"
 :slots [
   {:slot-index 0
    :anchor "fixed"
    :start-time "06:00:00"
    :fill-mode "flood"
    :collection-id 5  ; "TV Shows"
    :playback-order "random"}
   
   {:slot-index 1
    :anchor "fixed"
    :start-time "15:00:00"  ; 3 PM
    :fill-mode "block"
    :block-duration "6 hours"
    :collection-id 8  ; "Primetime Lineup" (specific episodes)
    :playback-order "chronological"
    :tail-mode "filler"}
   
   {:slot-index 2
    :anchor "sequential"  ; Starts after primetime block ends
    :fill-mode "flood"
    :collection-id 12  ; "Movies"
    :playback-order "random"}]}

;; Channel config
{:filler-collection-id 20  ; "Commercials and Ads"
 :filler-min-gap "10 seconds"
 :filler-max-time-shift "2 minutes"}
```

**How it works:**
- 6:00 AM: Random TV shows until 3 PM
- Auto-fills any gaps with commercials (if random show ends at 2:57 PM, fill 3min with ads)
- 3:00 PM: Specific primetime lineup for 6 hours
- If primetime ends at 8:55 PM, tail filler fills remaining 5 minutes
- 9:00 PM: Random movies until 6 AM
- Gaps between movies filled with commercials
- Daily repeat

---

### **Example 3: Semi-Sequential Binge Pattern**

**Requirements:**
- Play 5 episodes of a show sequentially
- Jump to random point in series
- Repeat (gives "connectivity" without strict sequence)

**Schedule:**
```clojure
{:name "Semi-Sequential Sitcoms"
 :slots [
   {:slot-index 0
    :anchor "sequential"
    :fill-mode "flood"
    :collection-id 15  ; "Classic Sitcoms"
    :playback-order "semi_sequential"
    :semi-seq-batch-size 5
    :semi-seq-jump-mode "random"}]}
```

**Deterministic Algorithm:**
```clojure
(defn semi-sequential-position [items batch-size seed day-offset within-batch-index]
  "Deterministic position calculation for reproducible schedules."
  (let [total (count items)
        batch-number (quot day-offset (quot 24 (:avg-episode-hours)))  ; Rough batch count
        rng (java.util.Random. (+ seed batch-number))
        batch-start (.nextInt rng total)
        position (mod (+ batch-start within-batch-index) total)]
    (nth items position)))
```

**How it works:**
- Day 1: Episodes 47, 48, 49, 50, 51
- Day 2: Jump to episodes 123, 124, 125, 126, 127
- Day 3: Jump to episodes 8, 9, 10, 11, 12
- Deterministic: Same seed + same date = same episodes

---

### **Example 4: Special Event Override**

**Requirements:**
- Regular schedule plays normally
- Weekend "James Bond Marathon" replaces everything
- Automatic activation/deactivation

**Base Schedule:**
```clojure
{:name "Regular Programming"
 :slots [{:anchor "sequential" :fill-mode "flood" :collection-id 10}]}
```

**Override:**
```sql
INSERT INTO schedule_overrides 
  (playout_id, name, override_start, override_end, required_tags, playback_order)
VALUES 
  (1, 'James Bond Weekend', 
   '2026-04-19 00:00:00', '2026-04-21 00:00:00',
   ARRAY['james-bond'], 'chronological');
```

**How it works:**
- Mon-Fri: Regular programming from base schedule
- Sat 12 AM - Sun 11:59 PM: All James Bond movies in chronological order
- Mon 12 AM: Back to regular programming
- Override automatically activates/deactivates based on timestamps

---

## Scheduling Engine Architecture

### **High-Level Algorithm**

```clojure
(defn generate-playout-events! [db playout-id start-time horizon]
  "Generate playout events from schedule for given time window."
  
  ;; 1. Check for active override
  (if-let [override (get-active-override db playout-id start-time)]
    (generate-from-override! db playout-id override start-time horizon)
    
    ;; 2. Load base schedule and slots
    (let [playout (get-playout db playout-id)
          schedule (get-schedule db (:schedule-id playout))
          slots (list-slots db (:schedule-id playout))
          cursor (:cursor playout)  ; JSONB state
          seed (:seed playout)]
      
      ;; 3. Process each slot in order
      (loop [current-time start-time
             slot-idx (:slot-index cursor 0)
             events []
             new-cursor cursor]
        
        (if (or (>= current-time horizon) (>= slot-idx (count slots)))
          ;; Done - insert events and save cursor
          (do
            (insert-playout-events! db playout-id events)
            (update-playout-cursor! db playout-id new-cursor)
            events)
          
          (let [slot (nth slots slot-idx)
                next-slot (when (< (inc slot-idx) (count slots))
                           (nth slots (inc slot-idx)))]
            
            ;; Calculate slot start time
            (let [slot-start (if (= (:anchor slot) "fixed")
                              (calculate-next-fixed-time (:start-time slot) current-time)
                              current-time)
                  
                  ;; Fill gap before slot (if auto-fill enabled)
                  gap-events (when (and (> slot-start current-time)
                                       (:auto-fill-gaps playout true)
                                       (not (:disable-auto-gap-fill slot)))
                              (fill-gap db playout slot-start current-time))
                  
                  ;; Generate content for this slot
                  slot-events (generate-slot-events db playout slot seed slot-start next-slot horizon)
                  
                  ;; Update time and cursor
                  slot-end (:finish-at (last slot-events))
                  next-cursor (update-cursor new-cursor slot slot-events)]
              
              (recur slot-end
                     (inc slot-idx)
                     (concat events gap-events slot-events)
                     next-cursor))))))))
```

### **Key Functions**

#### **Content Selection**
```clojure
(defn select-slot-content [db slot seed]
  "Query content for slot based on tags, collection, or specific item."
  (cond
    ;; Specific item takes priority
    (:media-item-id slot)
    [(get-media-item db (:media-item-id slot))]
    
    ;; Tag-based selection
    (seq (:required-tags slot))
    (query-media-by-tags db 
                        (:required-tags slot)
                        (:excluded-tags slot))
    
    ;; Collection-based selection
    (:collection-id slot)
    (query-collection db (:collection-id slot))
    
    ;; No content specified!
    :else
    (throw (ex-info "Slot has no content source" {:slot slot}))))
```

#### **Fill Mode Execution**
```clojure
(defn execute-fill-mode [items fill-mode slot current-time next-slot-time]
  (case fill-mode
    "once"
    (take 1 items)
    
    "count"
    (take (:item-count slot) items)
    
    "block"
    (let [duration (:block-duration slot)
          packed (pack-items-to-duration items duration)]
      (if (< (:total-duration packed) duration)
        ;; Add tail filler for remainder
        (concat (:items packed)
                (generate-tail-filler slot (- duration (:total-duration packed))))
        (:items packed)))
    
    "flood"
    (let [end-time (or next-slot-time horizon)]
      (pack-items-until items current-time end-time))))
```

#### **Playback Order**
```clojure
(defn apply-playback-order [items order slot seed cursor]
  (case order
    "chronological"
    (sort-by (juxt :release-date :title) items)
    
    "random"
    (shuffle-with-seed items seed)
    
    "shuffle"
    (if-let [shuffled (:shuffled-order cursor)]
      (reorder items shuffled)
      (let [shuffled (shuffle-with-seed items seed)]
        (update-cursor! cursor :shuffled-order shuffled)
        shuffled))
    
    "random_rotation"
    (let [used (:used-ids cursor #{})
          unused (remove #(contains? used (:id %)) items)]
      (if (seq unused)
        (shuffle-with-seed unused seed)
        ;; All used - reset and start over
        (do (update-cursor! cursor :used-ids #{})
            (shuffle-with-seed items seed))))
    
    "semi_sequential"
    (semi-sequential-select items 
                           (:semi-seq-batch-size slot)
                           seed
                           (:batch-offset cursor)
                           (:within-batch-index cursor))))
```

---

## Implementation Phases

### **Phase 1: Foundation (2-3 days)**

**1A. Tag System**
- Create `media_tags` table
- Add tag CRUD APIs
- Update `schedule_slots` with `required_tags` / `excluded_tags`
- Implement tag-based media queries

**1B. Filler Collections**
- Add filler/background collection columns to `channels`
- Add filler configuration fields (thresholds, behavior)
- Implement filler packing algorithm
- Create "Up Next" slate variant

**Deliverable:** Can tag media, query by tags, configure filler collections

---

### **Phase 2: Basic Scheduling Engine (3-4 days)**

**2A. Core Engine**
- Implement slot iterator
- Content selection (tags > collection > specific item)
- Fill mode execution (once, count, block, flood)
- Cursor management for stateful iteration

**2B. Fixed Slot Support**
- Calculate next occurrence of fixed-time slots
- Handle `fixed_start_time_behavior` (skip vs play)
- Timezone support for wall-clock times

**2C. Gap Filling**
- Detect gaps between fixed slots
- Apply filler hierarchy (filler → background → fallback → slate)
- Insert gap-fill events with `kind` = 'pre', 'tail', 'fallback'

**Deliverable:** Can generate playout timeline from schedule

---

### **Phase 3: Advanced Features (2-3 days)**

**3A. Semi-Sequential Playback**
- Implement deterministic batch selection
- Add `semi_sequential` to `playback_order` enum
- Cursor tracking for batch position

**3B. Schedule Overrides**
- Create `schedule_overrides` table
- Check for active overrides before base schedule
- Priority-based conflict resolution

**3C. Daily Rebuild**
- Background job at `daily_rebuild_time`
- Generate events for horizon window (keep existing events untouched)
- Manual rebuild API for config changes

**Deliverable:** Full scheduling system with overrides and auto-rebuild

---

### **Phase 4: Marathon Mode & Advanced Orders (1-2 days)**

**4A. Marathon Playback**
- Group items by `marathon_group_by` field
- Shuffle groups if `marathon_shuffle_groups`
- Play `marathon_batch_size` from each group before switching

**4B. Random Rotation**
- Track used items in cursor
- Ensure all items play before repeating
- Reset when exhausted

**Deliverable:** All playback modes fully functional

---

## Integration with tunarr-scheduler

### **Architecture**

```
┌─────────────────────┐
│  tunarr-scheduler   │  - Owns tag catalog
│  (External Tool)    │  - Manages tag normalization
└──────────┬──────────┘  - Builds scheduling logic
           │
           │ HTTP API
           │
┌──────────▼──────────┐
│   Pseudovision      │  - Stores tags (media_tags table)
│   (IPTV Server)     │  - Executes schedules
└─────────────────────┘  - Generates playout events
                         - Streams content
```

### **Integration Points**

**1. Tag Sync** (tunarr-scheduler → Pseudovision)
```clojure
;; In tunarr-scheduler
(defn sync-tags-to-pseudovision! [catalog pseudovision-api]
  (doseq [media (get-media catalog)]
    (let [tags (get-media-tags catalog (:id media))]
      (http/post (str pseudovision-api "/api/media-items/" (:id media) "/tags")
                 {:body {:tags (vec tags)
                         :source "tunarr-scheduler"}}))))
```

**2. Schedule Creation** (tunarr-scheduler → Pseudovision)
```clojure
;; In tunarr-scheduler
(defn create-channel-schedule! [pseudovision-api channel-spec]
  ;; Create schedule
  (let [schedule (http/post (str pseudovision-api "/api/schedules")
                           {:body {:name (:name channel-spec)
                                  :shuffle-slots false}})
        schedule-id (:id schedule)]
    
    ;; Create slots
    (doseq [slot (:slots channel-spec)]
      (http/post (str pseudovision-api "/api/schedules/" schedule-id "/slots")
                {:body slot}))
    
    ;; Attach to playout
    (http/post (str pseudovision-api "/api/channels/" (:channel-id channel-spec) "/playout")
              {:body {:schedule-id schedule-id
                     :seed (rand-int 1000000)}})
    
    ;; Trigger rebuild
    (http/post (str pseudovision-api "/api/channels/" (:channel-id channel-spec) "/playout/rebuild"))))
```

**3. Override Creation** (tunarr-scheduler → Pseudovision)
```clojure
;; Create "Shark Week" override
(http/post (str pseudovision-api "/api/playouts/" playout-id "/overrides")
          {:body {:name "Shark Week"
                 :override-start "2026-08-01T00:00:00Z"
                 :override-end "2026-08-08T00:00:00Z"
                 :required-tags ["shark" "documentary"]
                 :playback-order "chronological"}})
```

---

## Design Decisions & Rationale

### **Decision 1: Normalized Tag Table**
**Choice:** Separate `media_tags` table  
**Why:**
- Clean separation of concerns
- Easy tag management (rename, delete, count)
- Performant with proper indexes
- Can add JSONB cache layer later if needed

**Alternative Considered:** JSONB array in metadata table  
**Rejected Because:** Harder to manage tags, list unique tags, count usage

---

### **Decision 2: Array Intersection for Tag Queries**
**Choice:** Simple PostgreSQL array operators  
**Why:**
- Native PostgreSQL support
- Fast with GIN indexes
- Simple to understand and debug
- Extensible (can add complex queries later)

**SQL Example:**
```sql
-- Find all media with tags [mystery, drama] but not [explicit]
SELECT mi.* FROM media_items mi
WHERE EXISTS (
  SELECT 1 FROM media_tags mt
  WHERE mt.media_item_id = mi.id
    AND mt.tag = ANY(ARRAY['mystery', 'drama'])
  GROUP BY mt.media_item_id
  HAVING COUNT(DISTINCT mt.tag) = 2  -- Must have both
)
AND NOT EXISTS (
  SELECT 1 FROM media_tags mt2
  WHERE mt2.media_item_id = mi.id
    AND mt2.tag = ANY(ARRAY['explicit'])
);
```

**Future:** Can add JSONB query DSL without changing table structure

---

### **Decision 3: Deterministic Semi-Sequential**
**Choice:** Stateless algorithm using seed + date  
**Why:**
- Reproducible schedules (same seed = same output)
- No state in database (easier to rebuild)
- Works with daily rebuild model
- Can "preview" future schedule without generating it

**Alternative Considered:** Stateful cursor tracking  
**Rejected Because:** Complicates rebuilds, harder to debug

---

### **Decision 4: Schedule Overrides Table**
**Choice:** Separate table with date ranges and priority  
**Why:**
- Clean separation from base schedule
- Automatic activation based on dates
- Can overlap (priority resolves conflicts)
- Easy to list/manage special events

**Alternative Considered:** Conditional slots in base schedule  
**Rejected Because:** Clutters base schedule, harder to manage

---

### **Decision 5: Auto Gap Filling (Default On)**
**Choice:** `playout.auto_fill_gaps = true` by default  
**Why:**
- User-friendly - no manual gap management
- Mimics traditional TV (dead air is bad)
- Can be disabled per-playout or per-slot if needed

**Implementation:**
- Engine detects gaps between fixed slots
- Applies filler hierarchy automatically
- Inserts filler events with appropriate `kind`

---

### **Decision 6: Daily Rebuild Strategy**
**Choice:** Generate 8-14 days ahead, keep 1-7 days untouched  
**Why:**
- EPG clients typically want 7-14 days of data
- Generating too far ahead wastes resources
- Keeping near-term events stable prevents "schedule drift"
- Manual rebuild handles config changes

**Rebuild Triggers:**
- **Daily scheduled:** Background job at `daily_rebuild_time`
- **Manual:** API call to `/api/playouts/:id/rebuild`
  - Config change: Delete from NOW forward
  - Daily job: Only generate missing days 8-14

---

## Filler Design Details

### **Three-Tier Filler System**

**Why three types?**
1. **Filler collection** - High-frequency, short content (ads vary each time)
2. **Background collection** - Low-frequency, long content (don't want same backgrounds repeating)
3. **Fallback filler** - Emergency fallback (reliable, always available)

**Configuration per channel:**
```sql
channels.filler_collection_id      -- Ads, 15-60s clips
channels.background_collection_id  -- Movies, shows, 30min-2hr content
channels.fallback_filler_id        -- Single looping video
```

### **Packing Strategy: Greedy with Schedule Shift**

**Why greedy instead of optimal?**
- Fast: O(n log n) vs O(n × duration)
- Good enough: Gets within 95% of optimal
- Predictable: Easy to understand behavior
- Flexible: Can shuffle to avoid repetition

**Why allow schedule shifts?**
- Mimics real TV (shows start 1-2 minutes late all the time)
- Better filler utilization (fewer gaps)
- Configurable max_shift prevents abuse

**Why shuffle filler?**
- Prevents same ad order every time
- Better user experience
- Still deterministic (seeded shuffle)

### **Remainder Handling**

| Remainder | Action | Rationale |
|-----------|--------|-----------|
| 0-1s | Nothing (perfect fit) | Imperceptible |
| 1-5s | Black screen | Too short for content |
| 5-20s | "Up Next" slate | Shows what's coming, feels intentional |
| 20s-2min | Shift schedule, repack | Get better fit |
| 2min+ | Generated slate | Gap too large, show channel info |

---

## Technical Implementation Notes

### **Cursor State (JSONB)**

```json
{
  "slot_index": 3,
  "next_start": "2026-04-19T08:00:00Z",
  
  "shuffle_state": {
    "slot_2_shuffled_order": [45, 23, 67, 12, 89],
    "slot_2_current_index": 2
  },
  
  "rotation_state": {
    "slot_3_used_ids": [123, 456, 789]
  },
  
  "semi_seq_state": {
    "slot_4_batch_offset": 5,
    "slot_4_within_batch": 3
  }
}
```

### **Event Generation Performance**

**Optimization strategies:**
- Generate events in batches (don't query DB for each event)
- Preload collections/tags once per slot
- Use prepared statements for event insertion
- Limit horizon to 14-30 days max

**Expected performance:**
- 1000 events/second generation rate
- Full 14-day schedule for 10 channels: ~5 seconds
- Daily rebuild (days 8-14 only): ~2 seconds

---

## API Summary

**Tags:**
- `POST /api/media-items/:id/tags` - Add tags
- `GET /api/media-items/:id/tags` - Get tags
- `GET /api/tags` - List all tags
- `DELETE /api/media-items/:id/tags/:tag` - Remove tag

**Schedules:** (Already exist)
- `POST /api/schedules` - Create schedule
- `GET /api/schedules` - List schedules
- `POST /api/schedules/:id/slots` - Add slot
- `GET /api/schedules/:id/slots` - List slots

**Overrides:** (New)
- `POST /api/playouts/:id/overrides` - Create special event
- `GET /api/playouts/:id/overrides` - List overrides
- `DELETE /api/overrides/:id` - Remove override

**Playouts:**
- `POST /api/channels/:id/playout/rebuild?from=now` - Rebuild from NOW (config change)
- `POST /api/channels/:id/playout/rebuild?from=horizon` - Rebuild days 8-14 only (daily job)

---

## Next Steps

1. **Phase 1A: Tag System** (1 day) ← Start here
2. **Phase 1B: Filler Collections** (1 day)
3. **Phase 2: Basic Scheduling Engine** (3-4 days)
4. **Phase 3: Advanced Features** (2-3 days)

**Total estimated time:** ~1.5-2 weeks for complete system

**Immediate action:** Implement Phase 1A (Tag System) to unblock tunarr-scheduler integration.

---

## Open Questions

1. **Filler avoidance:** Should system track recently-played filler to avoid showing same ad twice in a row?
2. **Time zones:** Should `start_time` in fixed slots respect channel timezone?
3. **Multi-day schedules:** Should schedules support different programming Monday vs weekend?
4. **Tag inheritance:** Should episodes inherit tags from their series?

These can be addressed during implementation.
