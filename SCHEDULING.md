# Pseudovision Scheduling System - Architecture & Design

**Status:** ✅ Implemented (two tracks — see §0). Originally written 2026-04-18
as a pre-implementation design; this revision (2026-07-09) corrects it against
the actual shipped code, since the original had drifted significantly from
what was built. Where the two disagree, **the code is authoritative** —
sections below cite the real file/table/column names so drift is easier to
catch next time.

---

## 0. Two scheduling tracks

Pseudovision has **two independent ways a channel's timeline gets built**,
and the original version of this document conflated them. Keep them
separate:

1. **Local schedule engine** (`schedules` / `schedule_slots` / `playouts` →
   `playout_events`, built by `pseudovision.scheduling.core/build!`). A
   schedule is a named, reusable template of slots; a playout attaches one to
   a channel and the build engine walks it forward, advancing a JSONB cursor,
   to produce concrete `playout_events`. This is what §1–§6 below describe,
   and it's fully implemented.

2. **Tunarr Scheduler integration** (`POST /api/channels/:channel-id/daily-slots`,
   handled by `pseudovision.http.api.daily-slots`). Tunarr Scheduler owns a
   *frozen weekly Grid* + sparse *Overrides*, deterministically expands them
   into a `DailySlot[]` stream (concrete dated windows + a `media_id` +
   selection strategy), and pushes that stream here. Pseudovision's job is
   only to resolve each `media_id` to a concrete item and create
   `playout_events` — **no schedule/slot/override rows are involved in this
   path at all.** See §7 for the current, accurate description; the original
   §7 ("Integration with tunarr-scheduler") described a push-based
   schedule/slot/override-creation flow that was **never how the integration
   shipped** and has been rewritten below.

The `schedules`/`schedule_slots` API (track 1) remains available for
manually-authored, non-Tunarr channels; it is not being deprecated by track 2.

---

## Overview

Pseudovision's scheduling system supports:
- Tag-based content selection (via the dimension/tag model — see §1)
- Mixed fixed/sequential scheduling
- Intelligent gap filling with filler content
- Day-of-week-scoped slots (e.g. different programming Mon–Fri vs. weekends)
- Integration with Tunarr Scheduler's deterministic `DailySlot` stream (§7)

Two things proposed in the original design were **never built** and are
called out explicitly below rather than silently dropped: **schedule
overrides** (a `schedule_overrides` table for special events) and
**semi-sequential playback** (`playback_order = 'semi_sequential'`). Both are
addressed by other parts of the system today — see §1.7 and the playback
order list in §1.4 — so revisiting them as local features should be a
deliberate decision, not an oversight.

---

## 1. Building Blocks

### 1.1 Media Tags (via the dimension model)

**Storage:** `metadata_tags` (joined through `metadata` on `media_item_id`),
**not** a standalone `media_tags` table as originally drafted. This is
deliberate, not a naming accident: per `DIMENSION_CLEANUP.md`, all
categorization — genre, channel assignment, age-suitability, time-slot,
freshness, and free-form tags — lives in one place as prefixed strings
(`genre:mystery`, `channel:goldenreels`, `age-suitability:child`, ...) rather
than as separate hardcoded columns/tables. "Tags" in the scheduling sense are
just dimension values with no prefix, or any prefix a caller wants.

**API** (`pseudovision.http.api.tags`, real routes):
- `POST /api/media-items/:id/tags` — bulk add, `{tags: [...], source: "..."}`
- `GET /api/media-items/:id/tags` — list tags for an item
- `GET /api/tags` — list all unique tags with counts
- `DELETE /api/media-items/:id/tags/:tag` — remove one tag

This matches the original design's shape closely. One correction: the
`source` field is accepted by the API but not actually persisted or used for
anything downstream (no `source` column exists on `metadata_tags`) — the
"source tracking for debugging" design principle was aspirational and isn't
implemented.

**Tag query semantics — corrected.** The original design described tags as a
*third, alternative* content source ranked below collection/specific-item.
That's not how `schedule_slots.required_tags`/`excluded_tags` actually work:
they're an **AND-filter layered on top of** whichever source (collection or
specific item) is chosen, not an independent source of their own. See §1.4.

**Tag inheritance — implemented in one path, not the other.** The daily-slots
path (`daily-slots.clj`'s `pick-item`) unions an episode's own tags with its
parent show's tags before matching `category_filters` — an episode
effectively inherits its show's tags. The local schedule engine's
`matches-tag-filters?` (`scheduling/core.clj`) does **not** do this; it only
looks at the exact item's own tags. This is a real, currently-unaddressed
inconsistency between the two tracks, not a design choice — worth fixing if
the local engine ever needs to filter episodes by a show-level tag.

---

### 1.2 Collections

Unchanged from the original design and still accurate: collections link to
Jellyfin collections or custom groups via `media_items.collection_id`, and
`col-db/resolve-collection` expands a collection to its concrete items at
build time.

---

### 1.3 Schedules

Table `schedules` — named, reusable templates. Columns (real):
`fixed_start_time_behavior` ('skip' | 'play'), `shuffle_slots`,
`random_start_point`, `keep_multi_part_together`, `treat_collections_as_shows`.
Matches the original design.

---

### 1.4 Schedule Slots

Table `schedule_slots` — real columns, corrected from the original's
"proposed enhancement" (most of which shipped, in a slightly different shape
than drafted):

```sql
-- anchor / timing
anchor         slot_anchor     -- 'fixed' | 'sequential'
start_time     INTERVAL        -- required when anchor = 'fixed'
days_of_week   INTEGER         -- bitmask, default 127 (every day); see below

-- fill mode
fill_mode      slot_fill_mode  -- 'once' | 'count' | 'block' | 'flood'
item_count     INTEGER         -- fill_mode = 'count'
block_duration INTERVAL        -- fill_mode = 'block'
tail_mode      TEXT            -- 'none' | 'filler' | 'offline' (fill_mode = 'block' overflow)
discard_to_fill_attempts INTEGER  -- accepted by the API; not read anywhere in
                                  -- the build engine today (schema-only)

-- content source: exactly one of these two
collection_id  INTEGER
media_item_id  INTEGER
-- tags: an ADDITIONAL AND-filter over whichever source above is used —
-- not a third, independent content source (see §1.1 correction)
required_tags  TEXT[]
excluded_tags  TEXT[]

-- playback order
playback_order pb_order        -- see full enum below
marathon_group_by, marathon_shuffle_groups, marathon_shuffle_items, marathon_batch_size

-- filler overrides (NULL = inherit from channel; channel only defines fallback — see §2)
pre_filler_id, mid_filler_id, post_filler_id, tail_filler_id, fallback_filler_id
```

**`days_of_week` bitmask** (added after the original design was written,
migration `20260428001`): Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64;
default 127 = every day. This directly answers the original design's Open
Question #3 ("should schedules support different programming Monday vs.
weekend?") — yes, per-slot, and it's shipped.

**Playback orders — the real enum** (`CREATE TYPE playback_order`):
`chronological`, `random`, `shuffle`, `shuffle_in_order`,
`multi_episode_shuffle`, `season_episode`, `random_rotation`, `marathon`.

Two corrections against the original list:
- `shuffle_in_order`, `multi_episode_shuffle`, `season_episode` shipped but
  were never documented here.
- **`semi_sequential` was never implemented.** It's not in the enum, there's
  no `semi_seq_batch_size`/`semi_seq_jump_mode` column, and no code
  implements the "play N sequentially, jump, repeat" algorithm the original
  design specified in detail (Example 3, Decision 3, "Phase 3A"). If this is
  still wanted, it needs to be built from scratch — nothing here to build on
  top of.

**Content selection priority — corrected.** `scheduling/core.clj`'s
`load-items` picks a source as `collection_id` → else `media_item_id` → else
empty (exactly one of the first two should be set), then applies
`required_tags`/`excluded_tags` as a filter over *that* source's items. The
original design's "1. specific item, 2. tag query, 3. collection, checked in
that order" implied tags were a rankable alternative source; they aren't —
they narrow whichever source was picked.

---

### 1.5 Playouts

Table `playouts` — real columns differ from the original's proposed
enhancement:

```sql
seed                INTEGER      -- for reproducible shuffles
daily_rebuild_time  INTERVAL     -- time-of-day for the scheduled rebuild job
cursor              JSONB        -- opaque build-engine resume state
last_built_at       TIMESTAMPTZ
build_success       BOOLEAN
build_message       TEXT         -- populated on failure (e.g. "Build stalled: ...")
```

No `generation_horizon`, `auto_fill_gaps`, or `last_rebuild_at` columns as
originally proposed — horizon is a **call-time parameter**
(`:lookahead-hours`, default 72) to `build!`/`rebuild-horizon!`, not stored
per-playout, and gap-filling is always attempted (there's no per-playout
opt-out flag; a per-slot equivalent doesn't exist either).

**Real rebuild API** (also corrected — the original's
`POST /playout/rebuild?from=now|horizon` was never the actual route shape):
- `POST /api/channels/:channel-id/playout` — starts an **async job** (returns
  202 + a job record; poll `GET /api/jobs/:job-id`), not a synchronous call.
  Internally dispatches to `rebuild-from-now!` (config change: discards the
  saved cursor, regenerates from the currently-airing event's finish time
  forward so nothing already on screen gets cut off) or `rebuild-horizon!`
  (daily job: extends the timeline to a new horizon, keeping the existing
  cursor and everything already generated untouched).
- `DELETE /api/channels/:channel-id/playout` — clears the whole timeline and
  resets the cursor.
- `GET /api/channels/:channel-id/playout/events[/:id]` — cursor-paginated
  event listing.

**Build-stall safety net** (not in the original design at all): if a full
pass over every slot leaves the timeline cursor exactly where it started —
e.g. every slot resolves to no schedulable content — `build!` aborts rather
than looping forever, and records `build_success = false` with a message.
Worth knowing about since it changes what "the rebuild silently did nothing"
looks like operationally: check `build_message`, not just whether the job
returned 200.

---

### 1.6 Playout Events

Table `playout_events` — matches the original design closely. One correction:
`event_kind` enum is `content`, `pre`, `mid`, `post`, **`pad`** (padding to a
minute boundary — omitted from the original list), `tail`, `fallback`,
`offline`.

`is_manual` (not mentioned in the original) is worth calling out: user-injected
or user-edited events are flagged so rebuilds preserve them instead of
overwriting them — the mechanism behind "add a bumper" / "swap an episode"
without a full playout reset.

---

### 1.7 Schedule Overrides — not implemented

The original design's §7 (`schedule_overrides` table, priority-based special
events, "James Bond Weekend" example) **was never built.** There is no
`schedule_overrides` table, no `override_start`/`override_end` columns
anywhere, and no `/api/playouts/:id/overrides` route.

The reason this hasn't been revisited: the same problem — "special
programming for a bounded date range without touching the base schedule" —
is now solved **one layer up**, in Tunarr Scheduler's `Override` model (a
sparse, higher-precedence delta layered over its frozen `Grid`, expanded
deterministically alongside the base grid and pushed here as ordinary
`DailySlot`s — see §7). For any channel driven by the Tunarr Scheduler
integration, a local `schedule_overrides` table would duplicate that
mechanism. It would still be a gap for a *locally-authored, non-Tunarr*
channel that wants a temporary special event — if that's a real use case,
it's worth scoping as new work rather than assuming the original design here
already covers it.

---

## 2. Filler System Architecture

The original design's three-tier **channel-level** hierarchy (a
`filler_collection_id` for short gaps, a `background_collection_id` for long
gaps, a `fallback_filler_id` as last resort) was not built as drafted. The
`channels` table has exactly one filler-related column:

```sql
fallback_filler_id  INTEGER REFERENCES filler_presets(id)
```

What *is* richer than the original design: **slot-level** filler is a
five-role system (`pre_filler_id`, `mid_filler_id`, `post_filler_id`,
`tail_filler_id`, `fallback_filler_id` on `schedule_slots`, each nullable —
null inherits the channel's `fallback_filler_id` for the `fallback` role
specifically; the other four roles have no channel-level fallback to inherit
from and are simply absent if unset). This maps onto `event_kind`'s `pre` /
`mid` / `post` / `tail` / `fallback` values. `filler_presets` (a real,
implemented table, distinct from `filler_collection_id`/
`background_collection_id`) specifies a collection or item to draw from, a
`mode` (`duration` or `count`), and role.

### Role resolution (`scheduling.filler/resolve-filler-preset`)

For a given role: slot override, if set → else, for the `fallback` role only,
the channel's `fallback_filler_id` → else no filler for that role (a gap, or
in `tail_mode = 'offline'`, an explicit offline segment).

### Gap filling — corrected from the original decision tree

The original's size-tiered decision tree (< 10s black, 10s–30min packed
filler with an "Up Next" slate for small remainders, 30+min background
collection) doesn't match the real implementation. What's actually there
(`scheduling/filler.clj`, `scheduling/core.clj`'s `apply-filler`):

1. **Small tail gaps (≤ 15 seconds)** get first crack at a **bumper**:
   `fill-gap-with-bumper` picks an item from the channel's bumper collection
   whose duration matches the largest standard bucket (5/10/15s) that fits,
   with ±1s tolerance. Falls through to regular filler if no bumper collection
   or no matching-duration bumper exists.
2. **Everything else** resolves the role's preset (above) and, if the preset
   is `mode = "duration"` and the build has `:pack-filler? true` (the
   default), bin-packs the gap via `scheduling.packing/pack` — see below.
   Otherwise (or for `mode = "count"`), falls back to sequential enumerator
   fill (`filler/fill-gap`): draw items in enumerator order until the gap is
   filled or the next item would overflow it.
3. There is **no "Up Next" slate tied to remainder size** as the original
   design specified. A "Coming Up" overlay does exist, but at the streaming
   layer (`ffmpeg/hls.clj`, `streaming/manager.clj`) as part of the fallback
   stream source generally — it's not a scheduling-engine decision keyed to
   gap size.
4. `tail_mode = 'offline'` on a `block` slot produces an explicit `offline`
   event rather than any filler/slate.

### Filler packing — corrected from the original greedy pseudocode

The original's `pack-filler` pseudocode (fit the single best-matching item
repeatedly, special-case remainders under 20s/2min) is not what's
implemented. The real packer, `scheduling.packing/pack`, is a
**variety-optimizing randomized packer**, not a greedy one:

- Draws `k` (default 12) candidate playlists via seeded-random greedy fills,
  each weighted at each step toward less-recently-played items (a caller-
  supplied `recency` penalty map, not baked into the packer itself).
- Keeps the candidate that fills the gap most tightly (within a `tolerance`),
  then breaks ties by lowest total recency cost.
- **This directly answers the original design's Open Question #1** ("should
  the system track recently-played filler to avoid repeats?") — yes:
  `scheduling.core/pack-filler` seeds `recency` from a build-wide
  `filler-airings-atom` tracking every filler airing across **all channels**
  in the current build window (not just the current channel or gap), decaying
  linearly over a configurable window (default 4 hours). Same item back-to-back
  or across channels in the same window is actively discouraged, not just
  "possible future work."
- Deterministic given the same seed + recency state, so rebuilds are stable;
  the seed is `channel seed XOR gap start epoch second`, so different gaps
  vary independently.

### Slot-specific (deliberate) filler

This part of the original design is accurate as drafted: pre/mid/post/tail/
fallback filler on a slot is deliberate programming design (station IDs,
commercial breaks, outros, block padding), distinct from the automatic
gap-filling above, which only fires in the *absence* of a scheduled
programming gap plan. Difference preserved as originally stated.

---

## 3. Use Case Examples

Examples 1 and 2 from the original design (daytime/evening tag split; random
TV + fixed primetime block + random movies) are still representative of what
the real slot shape supports (`fixed`/`sequential` anchors,
`flood`/`block`/`count` fill modes, `collection_id`, `required_tags`/
`excluded_tags`, `playback_order`) and are left as illustrative examples,
not verbatim-tested code.

**Example 3 (semi-sequential binge pattern) and Example 4 (schedule
override) described features that were never built** — see §1.4 and §1.7.
Removed here rather than left as if they were working examples; if either
becomes real work, it deserves its own design pass grounded in what actually
shipped since (particularly the Tunarr Scheduler `Override` layer, which
covers Example 4's use case already — see §7).

---

## 4. Scheduling Engine Architecture

The original design's pseudocode (`generate-playout-events!`,
`select-slot-content`, `execute-fill-mode`, `apply-playback-order`) was
illustrative and doesn't correspond to the real function boundaries — not
wrong so much as a different shape than what got built. The actual engine
lives in `pseudovision.scheduling.core`:

- **`build!`** — the entry point. Loads the schedule's slots, resumes from
  the playout's saved cursor (or starts fresh on `:reset-cursor?`), then loops
  `process-slot` across slots until the horizon or a stall is hit (see the
  build-stall safety net in §1.5), inserting events and saving the cursor
  transactionally.
- **`process-slot`** — dispatches by `fill_mode` to one of four functions,
  each of which inlines its own content loading (`load-items`), enumerator
  based ordering (`scheduling.enumerators`, keyed per collection/media-item so
  position is tracked independently per source), and filler injection:
  - **`emit-once`** — one item, advance.
  - **`emit-count`** — exactly N items, with pre/mid/post filler around them.
  - **`emit-block`** — fill a fixed duration; on overflow, respects
    `tail_mode` (`filler` pads the remainder, `offline` emits an offline
    event, `none` trims the overflowing item to the block boundary and
    replays it in full at the next block start); on underflow (content
    exhausted early), post-roll then tail filler pad to the boundary.
  - **`emit-flood`** — fill from now until the next fixed-anchor slot (or a
    2-hour default if there isn't one); stops cleanly at the boundary, or
    fills the tail with fallback filler if content runs out first.
- **`rebuild-from-now!`** / **`rebuild-horizon!`** — the two async-job entry
  points described in §1.5.

Playback-order handling isn't a separate `apply-playback-order` function —
each fill-mode function pulls the next item from an enumerator
(`scheduling.enumerators/next-item`) that was constructed with the slot's
`playback_order` and a per-source cursor-tracked position, so ordering state
naturally persists across builds via the same JSONB cursor everything else
uses.

---

## 5. Design Decisions & Rationale

Decisions 1, 2, 5, and 6 from the original design are still accurate
rationale for what shipped (normalized tag storage, array-intersection tag
queries, auto gap-filling on by default, the daily-rebuild-window strategy) —
kept as-is below, with table names corrected. Decisions 3 and 4 described
features that were never built; marked accordingly rather than removed
outright, since the reasoning may still be useful if either is revisited.

### Decision 1: Normalized tag storage
**Choice:** tags live in `metadata_tags` (part of the broader dimension
model — see §1.1), not a JSONB array.
**Why:** clean tag management (rename/delete/count), GIN-indexable,
extensible without a schema change.

### Decision 2: Array intersection for tag queries
**Choice:** native PostgreSQL array operators over the tag set.
**Why:** fast with GIN indexes, simple, extensible.
Query shape as originally drafted is still representative, modulo the
`metadata_tags`/`metadata` join instead of a flat `media_tags` table.

### Decision 3: Deterministic semi-sequential — ❌ not implemented
The stateless seed+date algorithm described in the original design was never
built (see §1.4). The rationale given (reproducible without stored state,
works with the daily-rebuild model) is sound and would still apply if this
is revisited.

### Decision 4: Schedule overrides table — ❌ not implemented locally
See §1.7 — the same problem is solved at the Tunarr Scheduler layer today for
Tunarr-driven channels.

### Decision 5: Auto gap-filling, default on
Unchanged: `playout` behavior always attempts gap-filling; there's no stored
per-playout opt-out flag as the original draft proposed (see §1.5's
correction), so this is closer to "always on" than "on by default."

### Decision 6: Daily rebuild strategy
Unchanged in spirit: `rebuild-horizon!` extends the timeline to a new horizon
without touching what's already generated; `rebuild-from-now!` handles config
changes by discarding the cursor and regenerating from the in-progress
event's finish time forward (added precision the original design didn't
have: the reset explicitly avoids cutting off whatever's currently on air).

---

## 6. Open Questions from the original design — resolved or still open

1. **Filler avoidance (recently-played tracking):** ✅ resolved — implemented,
   see §2's packing section (`scheduling.packing/airing-penalties`, a
   build-wide, cross-channel recency penalty).
2. **Time zones for fixed `start_time`:** partially resolved. There's a
   single application-wide default zone (`pseudovision.util.time/default-zone`,
   read from the `TZ` env var, UTC fallback) that all fixed-time calculations
   use — not a per-channel timezone as the question implied. If multiple
   channels need different local wall-clock zones, that's still open.
3. **Multi-day schedules (different Monday vs. weekend programming):**
   ✅ resolved — the `days_of_week` bitmask (§1.4) does exactly this, per slot.
4. **Tag inheritance (episodes from their series):** partially resolved — see
   the §1.1 correction. Implemented in the Tunarr Scheduler `daily-slots`
   ingestion path, not in the local schedule engine's tag filter. Worth
   reconciling if the local engine ever needs it.

---

## 7. Integration with Tunarr Scheduler — corrected

The original design's integration section (push-based
`POST /api/schedules` → `POST /api/schedules/:id/slots` →
`POST /api/channels/:id/playout` → `POST /api/playouts/:id/overrides` flow)
describes an approach that **was never how this integration shipped.** The
real architecture is a layered, mostly-deterministic pipeline owned by Tunarr
Scheduler, with Pseudovision as a thin resolver at the bottom. Authoritative
detail lives in tunarr-scheduler's `SCHEDULING.md`/`ROADMAP.md` and
tunabrain's `docs/scheduling-grid-spec.md` / `docs/handoff-tunarr-pseudovision.md`
— this section is a Pseudovision-side summary, not the source of truth.

```
Pseudovision                 Tunarr Scheduler (stateful)         Tunabrain (stateless LLM)
────────────                 ────────────────────────────        ─────────────────────────
GET /api/catalog/aggregate ─► CatalogProfile ───────────────────► propose-quarterly-grid
                                                                   (dayparting + strip-fill)
                              feasibility check ◄── Grid ◄──────────────┘
                                    │ shortfalls
                                    └─► repair-quarterly-grid ──► revised Grid
                              freeze + store Grid
                              (monthly) ───────────────────────► propose-monthly-overrides
                                                                  ◄── Override[] ──┘
                              store Overrides
                              (weekly) expand(grid, overrides, week) → DailySlot[]
                                    │  (no Tunabrain call — pure function)
POST /api/channels/:id/daily-slots ◄┘
  (daily-slots.clj resolves each
   DailySlot's media_id → concrete
   item, creates playout_events)
```

**What Pseudovision actually owns in this flow:**

1. **`GET /api/catalog/aggregate`** (`http/api/catalog.clj`,
   `db/catalog.clj`) — produces the `CatalogProfile` Tunarr Scheduler feeds
   to Tunabrain: per-show rollups, tag aggregates, and a runtime histogram.
   Sized to the *shape* of the library, never raw media.
2. **`POST /api/channels/:channel-id/daily-slots`** (`http/api/daily-slots.clj`)
   — the only ingestion point. Body is a `DailySlot[]`: each has a concrete
   `[start_time, end_time)` window, a `media_id` (`series:<id>` |
   `movie:<id>` | `random:<category>`), a `media_selection_strategy`, and
   optional `category_filters`. For each slot, `pick-item` resolves
   `media_id` to a concrete episode/movie/pool item (honoring
   `category_filters` scoped to the show/movie's own tags — this is the path
   where tag inheritance from §1.1/§6.4 lives), then creates a
   `playout_event`. Existing non-manual events in the batch's date range are
   cleared first, so a re-push always wins.

**Duration-aware selection (added since the original design, not present in
it at all):** a `random:<category>` slot doesn't pick blindly from the whole
category pool — `select-fitting-items` narrows candidates to items whose
runtime plausibly fits the slot (within a 15-minute tolerance) before the
existing rotation logic (`pick-from-pool`) picks among them, and the created
event's `finish_at` reflects the picked item's **actual** duration, not the
slot's nominal boundary. When an item still overflows its slot, later slots
in the same batch shift forward to start right after it rather than
overlapping (slots are processed in `start_time` order with a threaded
cursor that resets to nominal timing whenever a slot finishes on time or
there's a genuine gap). Shipped in
[pseudovision#119](https://github.com/fudoniten/pseudovision/pull/119); see
tunarr-scheduler's `DURATION_AWARE_SCHEDULING.md` for the upstream half of
this work (a per-category runtime histogram and a feasibility check that
catches a duration mismatch at grid-authoring time, before it ever reaches
this endpoint).

**What's explicitly NOT part of this flow, contrary to the original design:**
- No `POST /api/schedules`-style calls from Tunarr Scheduler — it never
  creates `schedules`/`schedule_slots` rows. Those tables are exclusively for
  locally-authored channels (§0, track 1).
- No `/api/playouts/:id/overrides` calls — Tunarr Scheduler's `Override`
  concept is expanded into ordinary `DailySlot`s before it ever reaches
  Pseudovision; Pseudovision has no override concept of its own in this path
  (see §1.7).
- Tag *sync* (Tunarr Scheduler pushing tags via
  `POST /api/media-items/:id/tags`) is real and unchanged from the original
  design's description — that part was accurate.

---

## 8. Where to look for more

- **This repo:** `scheduling/core.clj` (build engine), `scheduling/filler.clj`
  + `scheduling/packing.clj` (filler), `http/api/daily-slots.clj` (Tunarr
  Scheduler ingestion), `http/api/tags.clj`, `db/catalog.clj` (aggregate).
- **tunarr-scheduler:** `SCHEDULING.md` (layered grid design), `ROADMAP.md`
  (phased delivery status), `DURATION_AWARE_SCHEDULING.md` (in-progress
  duration-fit work referenced in §7).
- **tunabrain:** `docs/scheduling-grid-spec.md` (the cross-system contract
  spec), `docs/handoff-tunarr-pseudovision.md`.
