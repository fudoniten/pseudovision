# AGENTS.md — Pseudovision

> Working notes for AI agents and humans working on this repo.
> For a deeper tour, see [README.md](README.md) and [ARCHITECTURE.md](ARCHITECTURE.md).

## What this is

Pseudovision is the **media playout engine and content catalog** of the Fudo
stack. It is a Clojure service backed by PostgreSQL that owns the canonical
record of media items, channels, schedules, and live playout timelines, and
serves IPTV output (HLS / MPEG-TS) to downstream players.

It is a from-scratch rewrite of the scheduling domain of
[ErsatzTV](https://ersatztv.org), using ~35 PostgreSQL tables instead of 130+.

## How it fits in the ecosystem

```
                           ┌─────────────────┐
   Pseudovision  ◄──────── │ Tunarr Scheduler │ ◄──────── Tunabrain (LLM)
   (this repo)   HTTP API  │  (control plane)  │  HTTP API  (stateless)
                           └─────────────────┘
                                  │
                                  │ catalog, tags, schedules
                                  ▼
                           ┌─────────────────┐
                           │     Marquee     │  (UI)
                           └─────────────────┘

   Grout (filler) ◄──── Pseudovision reads at air time
   Jellyfin     ◄──── Pseudovision's source of truth for media items
```

- **Tunarr Scheduler** calls Pseudovision's REST API to push daily slots, sync
  channel tags, and read the catalog. It is Pseudovision's only programmatic
  writer in the live cluster.
- **Tunabrain** never calls Pseudovision directly. It is invoked by Tunarr
  Scheduler.
- **Marquee** is the UI; it reads the catalog and channel metadata via
  Pseudovision's REST API.
- **Jellyfin** is the underlying media library — Pseudovision reads from
  Jellyfin to populate `media_items`. See `references/jellyfin-storage-layout-july-2026.md`
  in the `pseudovision-ecosystem-development` skill for the storage map.
- **Grout** is queried by Pseudovision's playout layer to fill gaps with
  bumpers / idents. (PV-side integration is partial as of July 2026 — see
  `references/grout-service-state-july-2026.md`.)

## Live endpoints (cluster)

| Service | URL | Notes |
|---|---|---|
| Public HTTPS | `https://pseudovision.kube.sea.fudo.link` | Ingress via cert-manager; TLS via Let's Encrypt |
| Health | `GET /health` | Returns 200 if Jetty is up; no body |
| Version | `GET /api/version` | `{git-commit, git-timestamp, version-tag}` |
| OpenAPI | `GET /openapi.json` | Reitit-generated; ~65 endpoints |
| In-cluster | `pseudovision.pseudovision.svc.cluster.local:8080` | Service DNS inside the `pseudovision` namespace |

**Deployed as of 2026-07-03:** `9869987` (master HEAD). My live testing always
checks this before debugging scheduler-driven failures — a divergence between
"what I built" and "what's deployed" is the #1 source of false-positive
regressions.

## Local development

```bash
# 1. Database (PostgreSQL ≥ 14)
createdb pseudovision

# 2. Migrations
clojure -X:migrate

# 3. Run
clojure -M:run                       # default config (resources/config.edn)
clojure -M:run -c path/to/config.edn # custom config

# 4. Tests (Kaocha)
clojure -M:test                      # all
clojure -M:test --focus pseudovision.http.api.daily-slots-test

# 5. nREPL
clojure -M:repl                      # listens on :7888
```

Tooling:

- **Clojure CLI** (deps.edn-based, not Leiningen)
- **Java 21+**
- **ffmpeg + ffprobe** on `$PATH` (used by the streaming layer; required for
  the playout engine to start)

## Source layout

```
src/pseudovision/
├── main.clj                  ; entry point
├── http/                     ; reitit routes + handlers
│   ├── core.clj              ; the router (~65 endpoints)
│   └── api/                  ; one namespace per resource
│       ├── channels.clj
│       ├── catalog.clj       ; /api/catalog/aggregate
│       ├── daily_slots.clj   ; /api/channels/{id}/daily-slots
│       ├── playouts.clj
│       ├── ffmpeg.clj
│       └── ...
├── db/                       ; data access (next.jdbc + HoneySQL)
│   ├── core.clj
│   ├── channels.clj
│   ├── playouts.clj
│   ├── catalog.clj
│   └── ...
├── streaming/                ; HLS / MPEG-TS output, ffmpeg orchestration
├── scheduling/               ; playout event generation, fillers
├── ffmpeg/                   ; profile resolution, encoder args (NVENC/VAAPI/libx264)
├── jobs/                     ; integrant-managed background runners
└── util/                     ; SQL helpers, time, etc.

test/pseudovision/...         ; mirror of src layout
```

## Public API surface (high-traffic endpoints)

| Endpoint | Purpose | Notes |
|---|---|---|
| `GET /api/catalog/aggregate` | Bulk media metadata for scheduler/UI | **Channel filter was broken (Issue 4)** — fixed in upstream `06816a2` + `bd7b01c`. Always verify the filter is actually applied; the daily-slots counterpart had a related bug (Jul 2026, fixed in #114). |
| `GET /api/channels` | List channels | |
| `GET /api/channels/{id}/playout` | Current playout state | UUID cast / SQL type issues hit this endpoint when `id` is bad. |
| `GET /api/channels/{id}/playout/events` | Stream of playout events | |
| `POST /api/channels/{id}/daily-slots` | Tunarr Scheduler's bulk slot ingest | Ingest is idempotent: PV clears non-manual events in the range first. Returns `{ingested, skipped, errors, channel_id}`. The `category_filters` field is scoped by top-level item id (show, not episode) as of #114. |
| `POST /api/schedules/...` | Legacy direct-schedule API | Deprecated; Tunarr Scheduler's cron pipeline replaces it. |
| `GET /stream/{uuid}` | Live HLS m3u8 | Returns the live manifest; `EXTINF` values reveal encoder GOP behaviour (Issue 6 / Pitfall 12). |

Full schema: `GET /openapi.json` — keep it handy.

## Common pitfalls

1. **Two endpoints both named "filter" — different bugs.** `GET /api/catalog/aggregate?channel=X` was the original Issue 4 (fixed `06816a2`+`bd7b01c`). `POST /api/channels/{id}/daily-slots` had a *separate* `category_filters` bug fixed in #114 (scoped by episode instead of show). Both must work for weekly scheduling to land content. If a regression appears in one, check the other.
2. **SQL type coercion.** Several handlers parse `:id` from path params as `Long/parseLong` with a `try`/`catch → -1` fallback, but downstream queries then return text-vs-bigint errors. If you see `"operator does not exist: text = bigint"`, it's almost always this. (Pitfall 1 in `pseudovision-ecosystem-development`.)
3. **Channel IDs are integers; UUIDs live in metadata.** Don't mix them up. The `channels` table has `id BIGINT` (PV's primary key) and `uuid UUID` (Pseudovision's external id for clients). Tunarr Scheduler uses `id`; some admin code uses `uuid`.
4. **PV returns 200 on partial ingest.** A 100-slot daily-slots push can return 200 with `skipped=87` — never trust the HTTP status alone; always read the body. The `4e8fb6b` WARN in Tunarr Scheduler exists because of this; same class of bug bit this repo's daily-slots handler in #114.
5. **Empty OpenAPI spec (Pattern 8 in `pseudovision-ecosystem-development`).** If a route's response body is `Content-Length: 0` but the route is registered, the underlying handler is `with-redefs`'d in tests, the handler is bypassed by middleware, or the test stub's `:db` map is missing a key the handler expects. Don't conclude "the endpoint doesn't exist" until you've checked the route file directly.
6. **Tags live on the show, not the episode.** Filter by show-level item id when checking `metadata_tags` for show-level tags like `channel:*`, `time-slot:*`, `audience:*`, `freshness:*`. Episode metadata only carries episode-specific tags. (See #114 for the regression history.)
7. **HLS segment cadence ≠ `hls_time`.** FFmpeg cuts HLS segments on keyframes; the effective segment duration is `min(hls_time, GOP / fps)`. NVENC's default GOP is 250 frames (8.33s at 30fps), which overrides `hls_time=2` unless you set `-g` explicitly. Symptom: live m3u8 shows `EXTINF:8.333` and users see periodic stutter. Fix: `video.gop` in the ffmpeg profile config. (Issue 6 / Pitfall 12.)
8. **`load-items` channel-catalog fallback (Pitfall 51 in `pseudovision-ecosystem-development`).** A schedule slot with neither `collection-id` nor `media-item-id` is implicitly asking for "anything tagged for this channel." Pre-fix, `load-items`'s `:else` branch returned `[]` and every such slot produced zero events. The fix (in the `fix/load-items-channel-catalog-fallback` branch, not yet merged) routes the slot through `media-db/resolve-playable-by-channel-tag` with the channel's `channel:<name>` dimension tag. If you see `events-generated: 0` for an auto-schedule slot on a channel that has tagged content, check that the fix has rolled and that the channel's items actually carry the `channel:<name>` tag.

## Where to look next

- `README.md` — quickstart, configuration, core concepts
- `ARCHITECTURE.md` — streaming layer decisions and rationale
- `SCHEDULING.md` — schedule / playout model in detail
- `references/pseudovision-openapi-current.md` (in `pseudovision-ecosystem-development` skill) — verified endpoint list with examples
- `references/catalog-aggregate-channel-filter-bug.md` — Issue 4 deep dive
- `references/streaming-stutter-diagnosis-july-2026.md` — Issue 6 (NVENC GOP)
- `references/tunarr-scheduler-july-2026-changes.md` — what the scheduler changed recently, useful when "was working yesterday" appears
