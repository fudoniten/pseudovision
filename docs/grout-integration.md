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

## Scope / limitations (initial integration)

- Grout filler is resolved at **build time**; clips are cached as `media_items`,
  so a clip removed from Grout after ingest stays referenceable until cleaned up.
- The "Grout" media source / library / library-path are created lazily on first
  ingest. There is no unique constraint on media-source name, so highly
  concurrent first-time builds could theoretically create duplicates; benign and
  easy to harden later.
- Only the **read/ingest** path is implemented here. Grout intake (the Tunarr
  write path) is Grout's own concern.
