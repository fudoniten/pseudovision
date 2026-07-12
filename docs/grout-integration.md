# Grout filler integration

[Grout](https://github.com/fudoniten/grout) is a single-purpose service that
stores short clips, tags them (channel, audience, source, …), and makes them
searchable by tag and duration. Pseudovision can draw **filler** from Grout
instead of a local Jellyfin collection.

Because Grout and Pseudovision share the underlying media filesystem,
Pseudovision streams the clip's absolute `path` directly off the mount — no HTTP
media transfer between the two services.

## How it works

Grout filler is wired into the **playout build** (not a separate runtime path),
so packing, recency-aware variety, guide data, and metrics all work exactly as
they do for local filler:

1. A `filler_presets` row carries a `grout_tags` array (e.g. `{ident,daytime}`).
   A non-empty `grout_tags` marks the preset as Grout-backed.
2. When the scheduler fills a gap with that preset, it queries Grout
   (`GET /grout/media`) for clips matching the **channel**, the **tags**, and the
   **gap duration** (`max_ms`), at random.
3. Each returned clip is ingested **once** as an ordinary local-path
   `media_item` (idempotent, keyed by `grout:<id>` under a dedicated "Grout"
   media source). From there the existing bin-packer selects clips and emits
   normal `playout_events`.
4. At stream time the event resolves to the clip's file path on the shared mount
   and ffmpeg reads it directly.

Grout is **best-effort**: if it is disabled, unreachable, or has no match, the
gap is simply left unfilled (or falls through to the channel's slate) — a Grout
outage never fails a build or a live stream.

### Channel mapping

A Pseudovision channel maps to a Grout `channel` tag by **kebab-casing its
name** (e.g. channel *"Britannia"* → `channel=britannia`). Grout treats
`channel=britannia` as "this channel OR generic (null-channel) clips", so
generic filler is usable everywhere. A channel-less fill queries generic filler
only.

## Configuration

Grout is **off unless `GROUT_URL` is set**.

| Env var           | Config key          | Default              | Purpose                                              |
| ----------------- | ------------------- | -------------------- | ---------------------------------------------------- |
| `GROUT_URL`       | `:grout :base-url`  | *(unset → disabled)* | Grout base URL, e.g. `http://grout:8080`             |
| `GROUT_MEDIA_DIR` | `:grout :media-dir` | `/data/media/grout`  | Root of the shared mount (must match Grout's mount)  |
| —                 | `:grout :timeout-ms`| `5000`               | Per-request timeout                                  |

## Using it

Create (or update) a filler preset with `grout_tags`, then reference it as a
channel's `fallback_filler_id` or a slot's `*_filler_id` — exactly like any
other preset:

```bash
curl -sX POST http://pseudovision:8080/api/filler-presets \
  -H 'content-type: application/json' \
  -d '{"name":"Britannia daytime idents",
       "role":"fallback","mode":"duration",
       "grout_tags":["ident","daytime"]}'
```

Rebuild the channel's playout and the gaps fill from Grout.

## Long-form content

Grout also stores long-form **content** (`kind=program`): documentaries, video
essays, and orphan web/YouTube long-form that isn't a Jellyfin movie or show but
should still be scheduled and appear in the catalog. Unlike filler — pulled
lazily at build time as bare items that only need to stream and pack — content
must be visible to the **catalog aggregate** (the report Tunarr Scheduler reads)
and the **daily-slot resolver** *before* a schedule is built. So content is
materialised by an explicit sync rather than lazily.

### Sync

`POST /api/sync/grout` pulls every `program` item from Grout and upserts each as
a `program`-kind `media_item` **with metadata + metadata_tags**:

- `name` → `metadata.title`, `description` → `metadata.plot`.
- Grout tags pass through verbatim into `metadata_tags` (an already
  `genre:`-prefixed tag flows into the catalog's genre aggregate; a bare tag is
  still matchable by `random:<tag>`), plus a synthesized `channel:<slug>` tag
  from Grout's `channel` column so the item lands in the right channel's slice.
- Items are keyed by `remote_key = grout:<id>` under a dedicated `grout-content`
  library (distinct from the `grout-filler` library, same "Grout" media source).

The sync is idempotent and best-effort: re-running refreshes metadata + tags to
match Grout (Grout is the source of truth, including tag removals), leaves the
immutable file/version untouched, and is a no-op when Grout is disabled. The
response is a `{enabled, total, synced, updated, skipped, errors}` summary. It's
intended to be driven by a scheduled job (e.g. a Kubernetes CronJob) as well as
manually.

### Report & scheduling

Once synced, content needs no separate resolution path:

- **Catalog aggregate** (`GET /api/catalog/aggregate`) lists each program in
  `shows[]` as a flat, movie-like entry (`episode-count: 1`) with a `program:`
  media-id, and its tags/genres feed `tags[]`/`genres[]`.
- **Daily-slots** (`POST …/daily-slots`) resolves `program:grout:<uuid>` to the
  synced item, and `random:<category>` pools include matching programs — so
  Tunarr Scheduler references Grout content like any native title.
- **Streaming** resolves it by path off the shared mount, exactly like filler.

## Scope / limitations (initial integration)

- Grout filler is resolved at **build time**; clips are cached as `media_items`,
  so a clip removed from Grout after ingest stays referenceable until cleaned up.
- The "Grout" media source / library / library-path are created lazily on first
  ingest. There is no unique constraint on media-source name, so highly
  concurrent first-time builds could theoretically create duplicates; benign and
  easy to harden later.
- Grout **content** metadata is fully owned by Grout: each sync overwrites PV's
  copy (title/plot/tags) to match, so channel/tag edits belong in Grout, not in
  PV's `metadata_tags` for these items. The sync is triggered on demand (or by a
  future scheduled job); it does not yet prune programs deleted from Grout.
- Only the **read/ingest** path is implemented here. Grout intake (the Tunarr
  write path) is Grout's own concern.
