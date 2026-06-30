# Tunarr Scheduler ↔ Pseudovision Integration Spec

> This doc captures the **exact wire contracts** for the two endpoints the Tunarr
> Scheduler agent needs to call into Pseudovision.
>
> Pseudovision's middleware normalises **all JSON keys to kebab-case** in both
> directions (request decoding and response encoding), so every key below is
> kebab-case on the wire.

---

## 1. CatalogProfile — `GET /api/catalog/aggregate`

### Request

```
GET /api/catalog/aggregate?channel=<ref>&tag=<tag>
```

Query parameters (both optional):

| Param     | Type     | Description                                              |
|-----------|----------|----------------------------------------------------------|
| `channel` | `string` | Integer id, channel number, or exact channel name.       |
| `tag`     | `string` | Explicit tag filter, e.g. `"channel:comedy"`. Overrides  |
|           |          | the channel-name inference when both are supplied.       |

### Response `200` — `CatalogProfile`

```json
{
  "channel-scope": null,
  "total-items": 100,
  "total-episodes": 80,
  "movie-count": 20,
  "shows": [
    {
      "media-id": "series:42",
      "title": "Cheers",
      "genres": ["comedy"],
      "episode-count": 275,
      "available-episode-count": 275,
      "avg-runtime-minutes": 22.5,
      "tags": ["channel:comedy"]
    }
  ],
  "genres": [
    {"genre": "comedy", "show-count": 1, "episode-count": 275}
  ],
  "runtime-histogram": [
    {"label": "20-30min", "min-minutes": 20, "max-minutes": 30, "item-count": 10}
  ],
  "generated-at": "2026-06-24T12:00:00Z"
}
```

### Response schema (Malli)

```clojure
(def ShowProfile
  [:map
   [:media-id :string]
   [:title :string]
   [:genres {:optional true} [:vector :string]]
   [:episode-count :int]
   [:available-episode-count :int]
   [:avg-runtime-minutes {:optional true} [:maybe :double]]
   [:tags {:optional true} [:vector :string]]])

(def CatalogProfile
  [:map
   [:channel-scope {:optional true} [:maybe :string]]
   [:total-items :int]
   [:total-episodes :int]
   [:movie-count :int]
   [:shows [:vector ShowProfile]]
   [:genres [:vector [:map [:genre :string] [:show-count :int] [:episode-count :int]]]]
   [:runtime-histogram [:vector [:map [:label :string] [:min-minutes :int] [:max-minutes [:maybe :int]] [:item-count :int]]]]
   [:generated-at {:optional true} [:maybe :string]]])
```

### Behaviour notes for the TS client

* `channel` is resolved in order: **id → number → name**. If omitted, the
  profile is **unscoped** (full library).
* `media-id` uses the `remote_key` when available; otherwise falls back to the
  internal `id`. The prefix is always `series:` or `movie:`.
* If you pass `?tag=channel:comedy` and the scoped profile comes back empty
  (`total-items: 0`), the handler **automatically falls back to the full
  catalog** so you always get a usable profile.

---

## 2. DailySlot ingestion — `POST /api/channels/:channel-id/daily-slots`

### Request

```
POST /api/channels/:channel-id/daily-slots
```

Path parameters:

| Param        | Type   | Description                                              |
|--------------|--------|----------------------------------------------------------|
| `channel-id` | `int`  | Integer primary key **or** string channel number (e.g. `"2.1"`). |

Body — a **bare `DailySlot[]` JSON array** (not wrapped in an envelope):

```json
[
  {
    "start-time": "2026-06-24T08:00:00",
    "end-time": "2026-06-24T10:00:00",
    "media-id": "series:42",
    "media-selection-strategy": "sequential",
    "category-filters": ["comedy", "channel:comedy"],
    "notes": ["Season 1 block"]
  }
]
```

**Key casing:** Pseudovision normalises every inbound JSON key to kebab-case, so
`start-time`, `start_time`, and `startTime` are all accepted and map to the same
field. The canonical/documented form is **kebab-case** (matching the response
encoding).

**`start-time` / `end-time` format:** an ISO-8601 datetime. Both forms are
accepted:

* **Naive local datetime** — `"2026-06-24T08:00:00"` (no zone/offset). This is
  the form the expander emits; it is interpreted in the **server's configured
  timezone** (`TZ`, defaulting to UTC). The same zone governs
  playout scheduling and EPG output, so naive slot times line up with the rest
  of the system.
* **Zoned/offset instant** — `"2026-06-24T08:00:00Z"` or
  `"2026-06-24T08:00:00+02:00"`.

A field that is present but does not parse is reported as
`"Invalid start_time: <value>"` (not `"Missing start_time"`); a truly absent or
blank field is reported as `"Missing start_time"`.

### Response `200` — `DailySlotIngestResult`

```json
{
  "ingested": 1,
  "skipped": 0,
  "errors": [],
  "channel-id": 7
}
```

### Response `404`

```json
{"error": "No playout for this channel"}
```

### Request / response schema (Malli)

```clojure
(def DailySlot
  [:map
   [:start-time :string]
   [:end-time :string]
   [:media-id {:optional true} [:maybe :string]]
   [:media-selection-strategy {:optional true} [:enum "random" "sequential" "specific"]]
   [:category-filters {:optional true} [:vector :string]]
   [:notes {:optional true} [:vector :string]]])

(def DailySlotIngestResult
  [:map
   [:ingested :int]
   [:skipped :int]
   [:errors [:vector :string]]
   [:channel-id :int]])
```

### Behaviour notes for the TS client

* `media-id` format: `series:<id>`, `movie:<id>`, or `random:<tag>`.
  * `series:` → resolves to a concrete episode. `sequential` uses playout
    history to pick the next unseen episode; `random` picks uniformly;
    `specific` picks the first episode.
  * `movie:` → resolves to the single movie item.
  * `random:` → resolves to any item tagged with `<tag>` (e.g. `random:comedy`).
* `media-selection-strategy` defaults to `"random"` if omitted.
* `category-filters` are **AND**-ed tag filters applied after the initial media
  resolution.
* The handler **clears existing non-manual events** in the date range of the
  slots before inserting the new ones, so each daily-slot push is idempotent
  for that day.

---

## Quick-reference: `media-id` prefixes

| Prefix  | Meaning          | Example         |
|---------|------------------|-----------------|
| `series:` | Show / series  | `series:42`     |
| `movie:`  | Single movie   | `movie:99`      |
| `random:` | Tag-based pool | `random:comedy` |

