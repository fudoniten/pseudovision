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

The server starts on port 8080 (configurable via `PSEUDOVISION_PORT`).

---

## Configuration

Copy `resources/config.edn` and override with environment variables:

| Variable              | Default                                         | Description                    |
|-----------------------|-------------------------------------------------|--------------------------------|
| `PSEUDOVISION_PORT`   | `8080`                                          | HTTP server port               |
| `PSEUDOVISION_DB_URL` | `jdbc:postgresql://localhost:5432/pseudovision` | JDBC connection URL            |
| `PSEUDOVISION_DB_USER`| `pseudovision`                                  | Database username              |
| `PSEUDOVISION_DB_PASS`| `pseudovision`                                  | Database password              |
| `FFMPEG_PATH`         | `/usr/bin/ffmpeg`                               | ffmpeg binary                  |
| `FFPROBE_PATH`        | `/usr/bin/ffprobe`                              | ffprobe binary                 |

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

---

## API

### Channels
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/channels` | List all channels |
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

### Playouts
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/channels/:id/playout` | Get the playout for a channel |
| POST | `/api/channels/:id/playout` | Trigger a rebuild |
| GET | `/api/channels/:id/playout/events` | List upcoming events |
| POST | `/api/channels/:id/playout/events` | **Inject a manual event** (bumper etc.) |
| PUT/DELETE | `/api/channels/:id/playout/events/:event-id` | Edit/remove a manual event |

### Output
| Path | Description |
|------|-------------|
| `/iptv/channels.m3u` | M3U playlist for all channels |
| `/xmltv` or `/epg.xml` | XMLTV EPG (7 day lookahead) |
| `/lineup.json` | HDHomeRun lineup (for Plex/Emby/Jellyfin auto-discovery) |

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
    channels.clj        Channel CRUD
    schedules.clj       Schedule + slot CRUD
    playouts.clj        Playout, event, history, gap queries
    media.clj           Media source, library, item, collection queries
    collections.clj     Collection resolver (dispatch by kind)
  http/
    core.clj            reitit router + Jetty server
    middleware.clj      JSON, logging, error handling
    api/
      channels.clj      Channel API handlers
      schedules.clj     Schedule/slot API handlers
      playouts.clj      Playout/event API handlers (incl. manual inject)
      epg.clj           XMLTV generation
      m3u.clj           M3U + HDHomeRun device emulation
      media.clj         Media/library/collection API handlers
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
