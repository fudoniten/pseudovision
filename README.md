# Pseudovision

An IPTV scheduling engine, written in Clojure.

Pseudovision lets you build virtual TV channels from a local media library.
You define a **Schedule** (a sequence of **Slots**), assign it to a
**Channel**, and the engine compiles a concrete **Playout** timeline of
**Events** that a downstream IPTV player consumes via MPEG-TS or HLS.

It is a from-scratch rewrite of the scheduling domain of
[ErsatzTV](https://ersatztv.org), using ~35 PostgreSQL tables instead of
130+.

---

## Quick start

```sh
# 1. Create the database
createdb pseudovision

# 2. Run migrations
clojure -X:migrate

# 3. Start the server (uses resources/config.edn by default)
clojure -M:run

# Or with a custom config file:
clojure -M:run -c /path/to/my-config.edn
```

The server starts on port 8080 (configurable via `PSEUDOVISION_HTTP_PORT`).

---

## Configuration

Copy `resources/config.edn` and override with environment variables:

| Variable                   | Default                                         | Description                    |
|----------------------------|-------------------------------------------------|--------------------------------|
| `PSEUDOVISION_HTTP_PORT`   | `8080`                                          | HTTP server port               |
| `PSEUDOVISION_LOG_LEVEL`   | `info`                                          | Log level (trace/debug/info/warn/error) |
| `PSEUDOVISION_DB_URL`      | `jdbc:postgresql://localhost:5432/pseudovision` | JDBC connection URL            |
| `PSEUDOVISION_DB_USER`     | `pseudovision`                                  | Database username              |
| `PSEUDOVISION_DB_PASS`     | `pseudovision`                                  | Database password              |
| `FFMPEG_PATH`              | `/usr/bin/ffmpeg`                               | ffmpeg binary                  |
| `FFPROBE_PATH`             | `/usr/bin/ffprobe`                              | ffprobe binary                 |

---

## Core concepts

```
Channel
  └── Playout           (one per channel; the live timeline)
        └── Event[]     (concrete scheduled items with timestamps)

Schedule
  └── Slot[]            (ordered list of "what to play, how much")
        └── collection_id | media_item_id
```

### Schedules & Slots

A **Schedule** is a reusable template. It has an ordered list of **Slots**.

Each Slot has:
- **anchor** — `fixed` (starts at a wall-clock time) or `sequential` (follows previous slot)
- **fill\_mode** — how much content to play:
  - `once` — one item
  - `count` — exactly N items
  - `block` — fill a fixed duration (e.g. 30 minutes)
  - `flood` — fill until the next fixed-anchor slot
- **collection\_id** or **media\_item\_id** — where the content comes from
- **playback\_order** — `chronological`, `shuffle`, `random`, etc.
- Per-slot filler and watermark overrides

### Playouts & Events

A **Playout** is the live compiled timeline for a channel. The build engine
turns a Schedule into a sequence of **Events** with concrete start/finish
timestamps.

**Manual events** (`is_manual = true`) are injected via the API (e.g. "add a
bumper at 8pm tonight"). The rebuild engine preserves them.

### Filler

Filler presets bridge gaps. Each preset has:
- **role** — where it plays (`pre`, `mid`, `post`, `tail`, `fallback`)
- **category** — what it is (`commercial`, `bumper`, `short`, `documentary`, `promo`, `trailer`, etc.)
- **mode** — how much to play (`duration`, `count`, `random_count`, `pad_to_minute`)
- **content source** — a local collection, a single media item, **or Grout**
  (`grout_tags`). A preset with `grout_tags` set pulls its clips from the
  [Grout](docs/grout-integration.md) filler service at build time instead of a
  local collection. See the doc for setup.

---

## API

The HTTP API is described by an OpenAPI 3 specification served at
`/openapi.json` and rendered interactively at `/swagger-ui/`. The spec is
generated at runtime from the route table in `src/pseudovision/http/core.clj`
and the malli schemas in `src/pseudovision/http/schemas.clj`. The endpoint
tables below are a quick reference; the live spec is authoritative.

Routes that have been migrated to the schema-driven stack (currently:
channels) validate requests via malli coercion — a malformed body, a
non-integer `:id`, or an invalid UUID query parameter returns a structured
400 response of the form `{"error": "Request coercion failed", "in": [...],
"humanized": {...}}` instead of a 500. Other routes retain their
pre-coercion behaviour until they're migrated.

### Documentation
| Path | Description |
|------|-------------|
| `/openapi.json` | OpenAPI 3 document for the full API |
| `/swagger-ui/` | Interactive Swagger UI |

### Channels
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/channels` | List all channels (optional `?uuid=` filter) |
| POST | `/api/channels` | Create a channel |
| GET | `/api/channels/:id` | Get a channel |
| PUT | `/api/channels/:id` | Update a channel |
| DELETE | `/api/channels/:id` | Delete a channel |

### Schedules & Slots
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/schedules` | List all schedules |
| POST | `/api/schedules` | Create a schedule |
| GET/PUT/DELETE | `/api/schedules/:id` | Manage a schedule |
| GET | `/api/schedules/:id/slots` | List slots |
| POST | `/api/schedules/:id/slots` | Add a slot |
| GET/PUT/DELETE | `/api/schedules/:id/slots/:slot-id` | Manage a slot |
| PUT | `/api/schedules/:id/slot-order` | Reorder slots (ordered list of slot IDs) |

### Playouts
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/channels/:id/playout` | Get the playout for a channel |
| PUT | `/api/channels/:id/playout` | **Attach a schedule** to the channel's playout (creates the playout row on first attach; `?rebuild=true&horizon=N` also triggers a rebuild) — this is the only way to attach a schedule, since `schedule_id` lives on `playouts`, not `channels` |
| POST | `/api/channels/:id/playout` | **Trigger a rebuild** — runs as an async job, returns `202` with `{ "job": … }` (poll `/api/jobs/:job-id`) |
| DELETE | `/api/channels/:id/playout` | **Clear the whole timeline** & reset the cursor (`?manual=true` also wipes injected events) |
| GET | `/api/channels/:id/playout/events` | List upcoming events |
| POST | `/api/channels/:id/playout/events` | **Inject a manual event** (bumper etc.) |
| DELETE | `/api/channels/:id/playout/events` | **Bulk-delete events**, optionally within `?from=&to=` (deletes anything overlapping the window; `?manual=true` includes injected events) |
| PUT/DELETE | `/api/channels/:id/playout/events/:event-id` | Edit/remove a single event |

### Catalog (Tunarr Scheduler / Tunabrain integration)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/catalog/aggregate` | **Library aggregate profile** — show counts, genre breakdowns, runtime histogram. Optional `?channel=` or `?tag=` scope. Includes synced Grout long-form content (`program:` media-ids). |
| POST | `/api/catalog/count` | Stub count endpoint (Phase 7). |
| POST | `/api/sync/grout` | **Sync Grout long-form content** into the catalog (`program`-kind items with metadata + tags) so it's schedulable and appears in the aggregate. Idempotent, best-effort. See [`docs/grout-integration.md`](docs/grout-integration.md). |

### Daily Slots (Tunarr Scheduler expander output)
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/channels/:id/daily-slots` | **Ingest expanded DailySlot[]** — resolves `media_id` + `media_selection_strategy` into concrete playout events. Clears non-manual events in the slot range before inserting. |

### Jobs
Long-running work (currently playout rebuilds) runs as background **jobs**. The
wire shape is compatible with the Tunarr Scheduler `/api/jobs` API so a shared
UI can render jobs from either backend. See [`PLAYOUT_JOBS.md`](PLAYOUT_JOBS.md)
for the full schema and the Marquee frontend integration spec.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/jobs` | List async jobs, newest-first (`{ "jobs": [ … ] }`) |
| GET | `/api/jobs/:job-id` | Get a job's status, progress, and result (`{ "job": … }`, `404` if unknown) |

### Output
| Path | Description |
|------|-------------|
| `/iptv/channels.m3u` | M3U playlist for all channels |
| `/xmltv` or `/epg.xml` | XMLTV EPG (7 day lookahead) |
| `/lineup.json` | HDHomeRun lineup (for Plex/Emby/Jellyfin auto-discovery) |
| `/stream/:uuid` | ⚠️ HLS live stream (basic implementation, uses test stream) |

---

## Development

```sh
# Start nREPL on port 7888
clojure -M:repl

# Run tests
clojure -M:test

# Roll back one migration
clojure -X:migrate :action rollback
```

### Project layout

```
src/pseudovision/
  main.clj              Entry point (CLI parsing, system start)
  system.clj            Integrant component wiring
  config.clj            Config → Integrant key translation
  db/
    core.clj            Connection pool, migrations, query helpers
    catalog.clj         Aggregation queries for the Tunabrain CatalogProfile
    channels.clj        Channel CRUD
    schedules.clj       Schedule + slot CRUD
    playouts.clj        Playout, event, history, gap queries
    media.clj           Media source, library, item, collection queries
    collections.clj     Collection resolver (dispatch by kind)
  http/
    core.clj            reitit router + Jetty server; wires coercion, Muuntaja,
                        OpenAPI/Swagger UI
    middleware.clj      Muuntaja JSON config, coercion-error handler, logging,
                        outer error handler
    schemas.clj         Malli schemas for request/response coercion and the
                        OpenAPI spec
    api/
      catalog.clj       Catalog aggregate endpoint (Tunabrain profile)
      channels.clj      Channel API handlers
      daily_slots.clj   DailySlot ingestion endpoint (Tunarr Scheduler expander)
      schedules.clj     Schedule/slot API handlers
      playouts.clj      Playout/event API handlers (incl. manual inject)
      epg.clj           XMLTV generation
      m3u.clj           M3U + HDHomeRun device emulation
      media.clj         Media/library/collection API handlers
      streaming.clj     HLS live streaming handlers
  ffmpeg/
    hls.clj             FFmpeg HLS command builder and process management
  scheduling/
    core.clj            Build engine (Schedule → Playout)
    cursor.clj          Cursor serialisation / resumption
    enumerators.clj     Collection iterators (chrono / shuffle / random)
    filler.clj          Gap filling and filler injection
  media/
    scanner.clj         Local filesystem scanner + ffprobe
  util/
    time.clj            tick wrappers, XMLTV date formatting
    sql.clj             PostgreSQL type coercion helpers
```

---

## Database

Migrations live in `resources/migrations/`. The schema is documented in
`20260225-001-initial-schema.up.sql`.

Key simplifications vs ErsatzTV's original:

| Area | Original | Here |
|------|----------|------|
| Media items | 14+ tables | 1 (`media_items`, TPH) |
| Metadata | 10 tables | 1 (`metadata`, TPH) |
| Collections | 11+ tables | 1 (`collections`, JSONB config) |
| Playout cursor | 5 tables | `playouts.cursor` JSONB |
| FFmpeg profile | 25+ columns | `ffmpeg_profiles.config` JSONB |

---

## Docker

```sh
docker build -t pseudovision .
docker run -p 8080:8080 \
  -e PSEUDOVISION_DB_URL=jdbc:postgresql://host.docker.internal:5432/pseudovision \
  pseudovision
```
