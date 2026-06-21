# Seamless Event Transitions (Option B) — Migration Plan

Status: proposed
Scope: the live HLS streaming path (`/stream/{uuid}`) and the FFmpeg profile model
Related: `TODO.md` §7 (Event Transitions), §10 (raw-TS mode)

---

## 1. Goal & non-goals

**Goal.** When one media item ends (or a manual event cuts in), the channel
continues to the next item with **no visible gap, buffering stall, or
client-side stream restart**, across mainstream IPTV clients.

**Non-goals (for this plan).**
- True single-encoder gapless output (the ErsatzTV "Option C" continuous
  encoder). Rejected — see §3.
- Adaptive bitrate / multi-rendition HLS.
- Raw MPEG-TS output mode (`TODO.md` §10). Compatible with this design but
  tracked separately.

---

## 2. Where we are today

The current live path (`src/pseudovision/http/api/streaming.clj`):

- Runs **one FFmpeg process per event**, all writing into a single shared dir
  (`/tmp/pseudovision/streams/{uuid}/`) with fixed names `playlist.m3u8` +
  `segment-%03d.ts`.
- Transitions are **lazy and serial**: on a playlist poll, `needs-transition?`
  compares the running source identity against what should play 5s ahead
  (`transition-lookahead-secs`); on a mismatch it **kills FFmpeg A, then starts
  FFmpeg B** in the same dir (`stop-and-remove-stream` → `start-new-stream`).
- `src/pseudovision/ffmpeg/hls.clj` hardcodes `-c:v libx264 -preset … -re` and
  owns the HLS muxing (`-f hls -hls_flags delete_segments`).

Three structural reasons this is not seamless:

1. **Playlist resets.** FFmpeg B restarts segment numbering at `segment-000.ts`
   and rewrites `playlist.m3u8` with `#EXT-X-MEDIA-SEQUENCE:0`. Clients see the
   media sequence jump backwards and held segments vanish → reload/stall.
2. **Dead air on spin-up.** Kill-then-start is serial; B needs seconds to open
   the input, transcode, and write its first segment. The playlist is empty or
   404s in that window.
3. **No discontinuity signalling.** No `#EXT-X-DISCONTINUITY`, and Jellyfin
   `static=true` passes source params through, so codec/resolution can change at
   a boundary without the client being told.

---

## 3. Design decision: B over C

| | **B — per-event encoders + managed playlist** | **C — single continuous encoder** |
|---|---|---|
| Fault isolation | One bad file = one item hitch; channel self-heals | One bad file can drop the **whole channel** (the "dead channel" failure mode) |
| Live schedule edits | Re-plans for free at each boundary | Encoder has committed to its concat list; edits force a restart or live re-feed |
| A/V sync | Per-item, FFmpeg handles it | Must own monotonic PTS across boundaries; drift accumulates |
| Per-item metrics / "now playing" | Near 1:1 with current model | Breaks the 1:1; needs in-stream boundary tracking |
| HW encoder fit | Overlap briefly doubles sessions at the splice | One session per channel, constant |
| Client compatibility | Excellent **iff** discontinuity-sequence + normalization are correct | Excellent (no discontinuities at all) |
| Implementation | Incremental on today's architecture | Streaming-model rewrite |

**Conclusion.** B wins on fault isolation, live-schedule agility, and
incremental risk — the properties that matter most for Pseudovision's editable
playout and for avoiding ErsatzTV-style dead channels. C's only decisive edge
(zero discontinuities for creaky STBs) is neutralised by doing B's
**output normalization** properly, which makes each discontinuity carry only a
timestamp reset. The NVENC session-cap concern is acceptable at the target
scale (2–3 channels) and is further mitigated by multiple GPUs and VAAPI
fallback.

---

## 4. Target architecture (Option B)

```
/stream/{uuid}  ──►  Channel Stream Manager (one per active channel)
                         │  owns authoritative playlist.m3u8
                         │  owns monotonic media-sequence + discontinuity-sequence
                         │  owns sliding-window segment GC
                         │
                         ├─ Encoder slot A (current event)  ─► scratch dir A ─┐
                         └─ Encoder slot B (pre-rolled next) ─► scratch dir B ─┘
                                                                      │
                            manager ingests finished segments,        ▼
                            renames into global sequence,      segment-{globalseq}.ts
                            inserts #EXT-X-DISCONTINUITY at handoff
```

Key change of ownership: **FFmpeg no longer owns the playlist.** Each per-event
encoder writes plain segments into its own scratch dir; a Clojure **Channel
Stream Manager** owns the authoritative playlist and the global segment
sequence.

### 4.1 Channel Stream Manager
- One per active channel (replaces the per-channel entry in `active-streams`).
- Holds: authoritative playlist state, global segment counter,
  discontinuity-sequence counter, sliding window, and 1–2 encoder slots.
- An ingest loop watches each encoder's scratch dir, and for every newly
  *completed* segment:
  - renames/links it to `segment-{globalseq}.ts` (monotonic, never resets),
  - appends an `#EXTINF` entry to `playlist.m3u8`,
  - emits `#EXT-X-DISCONTINUITY` immediately before the **first** segment of a
    new encoder slot,
  - evicts the oldest segment when the window exceeds `hls_list_size`, bumping
    `#EXT-X-MEDIA-SEQUENCE`, and bumping `#EXT-X-DISCONTINUITY-SEQUENCE`
    **when the evicted segment was a discontinuity boundary** (the single most
    important correctness rule — see §9).

### 4.2 Pre-roll + overlap handoff
- At `T_boundary − lookahead`, start encoder B for the next event into scratch
  dir B (the 5s lookahead already exists in `desired-source-identity`).
- Wait until B has produced ≥ N warm segments (config, e.g. 2).
- Splice: after the last A-segment that covers the boundary, the manager begins
  ingesting B's segments and emits the discontinuity.
- Then stop A. Because B is warm before A stops, there is **no dead air**.
- Cold start and resume-into-current-item: only the *first* encoder on a cold
  start takes a wall-clock `-ss` seek (`calculate-start-position` logic moves
  into the manager). Subsequent events start at their `in-point` (usually 0).

### 4.3 Output normalization (the compatibility guarantee)
Every encoder slot must emit **identical output geometry**: resolution (scale +
pad to target), fps, pixel format, codec/profile, audio codec, sample rate,
channel layout. With normalization, the boundary discontinuity is a pure
timestamp reset — the gentlest, most widely supported case (this is the exact
mechanism server-side ad insertion uses, so all maintained players handle it).
Skip normalization and resolution/codec changes will glitch hardware decoders on
some STBs. Optimisation: skip the scale filter when the source already matches
the target to save GPU cycles.

### 4.4 Segment storage — local now, abstracted for later
Live segments stay on **co-located local disk** (today's
`/tmp/pseudovision/streams/{uuid}/` model): both encoder slots for a channel run
on one node writing to one local dir, and the manager stitches locally. This is
the lowest-latency, lowest-complexity choice and is the right fit for the overlap
handoff — a channel's two short-lived encoders are naturally co-located, and at
2–3 channels there is no pressure to split a single channel's live encode across
nodes.

The thing that would actually tie our hands is not the backend but a hardcoded
local-filesystem assumption (today `segment-handler` calls `io/file` /
`io/input-stream` directly). So segment access goes behind a small
**`SegmentStore` protocol** (`put` / `get` / `list` / `delete`) with a
local-disk implementation now. Swapping to shared storage (PVC / NFS) later
becomes one new implementation, not a rewrite. Two supporting habits keep the
door open:

- Managers stay **channel-keyed and location-independent** (they already key on
  `uuid`); nothing assumes "this node owns this channel."
- Deferred batch transcode jobs (§7) may use **shared storage independently** —
  they are distributable by design and need not share a backend with live
  segments. `SegmentStore` is the seam that later lets the live path read a
  batch job's pre-normalized output.

The clean split that avoids lock-in: **co-locate live encoders, distribute batch
jobs**, with `SegmentStore` between them.

---

## 5. Prerequisite: hwaccel-aware FFmpeg profiles

Both the live encoders and (later) the transcode jobs need this; build it first.

Today `hls.clj` hardcodes `libx264`/`-preset`/`-re` and `ffmpeg_profiles.config`
holds only `{video-codec, audio-codec, preset, video-bitrate, audio-bitrate}`.
`-preset` is a libx264 concept and does not map to VAAPI, so the profile model
must grow an accel dimension.

Proposed `ffmpeg_profiles.config` shape (JSONB — no migration needed for the
column itself, only validation):

```clojure
{:accel        :none            ; :none | :nvenc | :vaapi
 :device       "/dev/dri/renderD128"   ; vaapi only
 :video        {:codec   "h264"        ; logical; mapped per accel
                :bitrate "4000k"
                :rate-control :vbr
                :preset  "p4"}         ; nvenc preset; ignored for vaapi
 :audio        {:codec "aac" :bitrate "192k" :sample-rate 48000
                :channels 2 :layout "stereo"}
 :normalize    {:width 1920 :height 1080 :fps 30 :pixfmt "yuv420p" :sar "1:1"}
 :hls          {:segment-duration 2 :playlist-size 10 :warm-segments 2}}
```

A `pseudovision.ffmpeg.profile` namespace maps the logical config to concrete
flags per accel:

- **CPU:** `-i … -vf scale=W:H:…,format=pixfmt -c:v libx264 -preset … -b:v …`
- **NVENC:** `-hwaccel cuda -hwaccel_output_format cuda -i … -vf scale_cuda=W:H -c:v h264_nvenc -preset p4 -rc vbr -b:v …`
- **VAAPI:** `-hwaccel vaapi -hwaccel_device /dev/dri/renderD128 -hwaccel_output_format vaapi -i … -vf scale_vaapi=W:H -c:v h264_vaapi -rc_mode VBR -b:v …`

Decode fallback: sources that cannot be hardware-decoded fall back to software
decode + `hwupload` into the GPU encoder.

---

## 6. Phased implementation

Each phase is independently shippable and leaves the channel working.

### Phase 0 — hwaccel-aware profiles *(prerequisite)* ✅ DONE
- [x] Add `pseudovision.ffmpeg.profile` (logical config → per-accel flags + the
  normalization filter chain; software / NVENC / VAAPI; accepts legacy flat and
  nested config shapes; auto-downgrades to software when a backend is missing).
- [x] Refactor `hls.clj/build-hls-command` and `build-slate-command` to consume
  it (slate stays software for now — synthetic lavfi source).
- [x] Extend the `FFmpegProfileConfig` malli schema for the nested shape (kept
  open so legacy configs still validate).
- [x] Auto-detect available accel (probe `/dev/nvidia0`, `/dev/dri/renderD*`);
  log at startup; per-channel override falls out of the per-profile config.
- *Deliverable:* existing per-event streaming runs on CPU/NVENC/VAAPI by config.
  No transition change yet. Covered by `test/pseudovision/ffmpeg/profile_test.clj`.

### Phase 1 — Channel Stream Manager (own the playlist) ✅ DONE
- [x] Introduce the manager component (`pseudovision.streaming.manager`), an
  Integrant `:pseudovision/streaming` key replacing the old `active-streams`
  atom; one single-owner background loop per channel.
- [x] Add the `SegmentStore` protocol (§4.4,
  `pseudovision.streaming.segment-store`) with a local-disk implementation;
  `segment-handler` reads through it, never `io/file` directly.
- [x] Encoders write plain segments into a per-encoder scratch dir
  (`build-hls-command`/`build-slate-command` gain `:manager-mode?`); the manager
  ingests them into a manager-owned `playlist.m3u8`
  (`pseudovision.streaming.playlist`) with a **monotonic** media-sequence,
  correct discontinuity-sequence bookkeeping (§9), and sliding-window GC.
- [x] One encoder per channel, restarted at boundaries; a restart no longer
  resets the client-visible sequence and emits a single `#EXT-X-DISCONTINUITY`
  at the boundary. Source-resolution/metrics moved to
  `pseudovision.streaming.source` (shared, no dependency cycle).
- *Deliverable:* transitions no longer reset the playlist; clients stop
  reloading at boundaries. (Dead-air gap still exists — fixed in Phase 2.)
- *Tested:* `playlist_test` (media-seq monotonicity, discontinuity-sequence
  eviction §9), `segment_store_test`, `manager_test` (simulated-encoder ingest +
  single-discontinuity transition), `source_test`. **Needs a hardware smoke test
  on the real FFmpeg path** (no GPU/FFmpeg in CI).

### Phase 2 — Pre-roll + overlap handoff
- Add the second encoder slot; warm B before stopping A; splice on a segment
  boundary.
- Move `calculate-start-position` (cold-start seek) into the manager.
- *Deliverable:* no dead air at transitions; smooth handoff.

### Phase 3 — Normalization hardening
- Enforce the `:normalize` filter chain on every slot; verify identical output
  params across the discontinuity.
- Add discontinuity-sequence eviction correctness tests (§9).
- *Deliverable:* STB-safe output; the compatibility guarantee holds.

#### Phase 3-lite — decode robustness ✅ DONE
- [x] Add a `:decode` profile mode, default `:software`: hardware backends now
  **software-decode → `hwupload` → hardware-encode**, robust to any input codec
  (10-bit HEVC, odd formats) — the encode stays on the GPU (the expensive part),
  only decode moves to CPU. `:decode "hardware"` opts back into full-GPU decode
  for known-decodable content.
  - VAAPI: `-vaapi_device …` + `-vf …,format=nv12,hwupload` (vs. `-hwaccel vaapi
    -hwaccel_output_format vaapi`); NVENC: `-init_hw_device cuda` +
    `hwupload_cuda`.
  - Motivated by a real production failure: forced VAAPI decode aborted on
    library HEVC content, masking as a software-encode degrade.
  - *Tested:* `profile_test` (software-decode default, upload filters, CPU-scale
    before upload, hardware-decode opt-in). VAAPI software path confirmed in
    production; NVENC software path needs a hardware smoke test.

### Phase 4 — Fault isolation & watchdog
- Per-encoder watchdog: a slot that dies/stalls is restarted; a source that
  fails to open splices in the fallback slate/filler **without** tearing down
  the channel manager.
- Preserve metrics across handoffs (open/close `media_item_views` at each splice
  rather than per-process; see §8).
- *Deliverable:* a bad file degrades to a brief hitch, never a dead channel.

### Phase 5 — Distributed transcoding *(DEFERRED — see §7)*

Explicitly **out of scope for now.** Phases 0–4 are the seamless-transition
deliverable and ship with inline real-time transcoding. Phase 5 is architected
*for* but not *built* yet: the `SegmentStore` seam (§4.4), the location-
independent managers, and the reuse of the Phase-0 profile model as the job
`:target` are the hooks that let it drop in later without reworking the live
path. Revisit once seamless transitions are proven in production (its payoff is
reduced live GPU load, not seamless transitions themselves).

Rough sizing: Phases 0–4 are the seamless-transition deliverable. 0 and 1 are
the bulk of the work; 2–4 are smaller increments on the manager.

---

## 7. Distributed transcoding — DEFERRED design

**Decision: deferred. Architect for it now, build it later.** It is decoupled
from the seamless-transition work and must not be folded into the live encoder.
Live streaming is *real-time, low-latency, seek-aware* transcoding; a transcode
"job" is *batch, throughput-oriented, cluster-scheduled*. Those are different
latency and failure domains; coupling them couples three concerns (real-time
muxing, batch transcode, scheduling) into one fragile unit. Ship Phases 0–4
first; they stand alone with inline real-time transcoding.

The hooks that keep this drop-in-able later (build these into Phases 0–4, then
stop): the `SegmentStore` seam (§4.4) so batch output is readable by the live
path; location-independent, channel-keyed managers; and the Phase-0 profile
model reused as the job `:target`. Nothing in Phases 0–4 should assume
transcoding is always in-process.

When we do build it, the highest-value first form is **ahead-of-time
normalization**, not a generic converter:

- The scheduler pre-transcodes upcoming playout events to the channel's target
  profile shortly before airtime, into shared storage.
- The live path then streams already-normalized content (cheap: `-c copy` or
  light work), which:
  - **guarantees** the §4.3 normalization the client compatibility depends on,
  - **cuts live GPU load** (fewer concurrent live encode sessions — directly
    eases the NVENC overlap concern),
  - **improves fault isolation** (bad/unreadable files are caught at transcode
    time, before airtime, not mid-stream).

Design it as **one job abstraction with pluggable execution**, so there is a
single transcode code path:

```clojure
;; A transcode job spec — one ffmpeg invocation handles all three stream types.
{:source       {...}              ; media item / source URL + in/out points
 :target       <ffmpeg-profile>   ; reuses the Phase-0 profile model
 :streams      {:video :transcode ; :transcode | :copy
                :audio :transcode
                :subtitle :passthrough} ; :passthrough | :burn-in | :drop
 :output       {:kind :hls-segments | :file
                :uri  "..."}}      ; SegmentStore / shared storage
 ;; execution: :in-process (local) | :distributed (worker pool)
```

**Queue architecture (decided): PostgreSQL-backed, `FOR UPDATE SKIP LOCKED`.**
No k8s Jobs, no external broker — it fits the existing `db/` + Integrant style
and Postgres is already the backbone.

- `transcode_jobs` table: `state` (pending/claimed/running/done/failed),
  `priority`, `required_accel`, `claimed_at`, `lease_expires_at`, `worker_id`,
  `artifact_uri`, `error`.
- **Claim:** `SELECT … WHERE state='pending' AND (required_accel IS NULL OR
  required_accel = ANY(:my-accels)) ORDER BY priority, created_at FOR UPDATE
  SKIP LOCKED LIMIT 1`. `SKIP LOCKED` lets concurrent workers pull distinct rows
  with zero double-processing — no broker required.
- **Crash recovery:** lease + heartbeat; a watchdog re-queues jobs whose
  `lease_expires_at` has passed.
- **Wakeup:** poll every few seconds (transcodes are minute-scale); add
  `LISTEN/NOTIFY` later if instant pickup is wanted.
- **Why this:** transactional enqueue (queue a transcode in the same transaction
  as a playout build — exactly-once), fully observable (just rows; inspect /
  retry / cancel via SQL or the API), and no new infra to run. Outgrown only at
  thousands-of-jobs/sec, which transcoding never reaches.

**GPU routing without a scheduler.** Workers are Integrant components that
advertise their accel(s) (`my-accels`); the claim predicate filters on
`required_accel`, so an NVENC job is only pulled by an NVENC-capable worker.
Node affinity (`accel=nvenc` / `accel=vaapi` labels + `nodeSelector`) is purely a
deployment concern. Cap GPU sessions with a per-worker concurrency limit matching
the session budget; VAAPI workers on the Intel iGPUs are the fallback tier when
the Quadros are saturated.

**Subtitles — optional, channel-dependent burn-in (decided).** Keep
video/audio/subtitle in one job spec (a single ffmpeg run does all three). Policy
is a **channel-level default, overridable per item**; default `:passthrough` /
`:drop`, with `:burn-in` as an explicit opt-in. Two consequences to design in:
burn-in forces a full video re-encode (it **breaks the `-c copy` fast path**),
and a burned-in artifact is channel-specific — so the pre-normalized cache key is
`(media-item, target-profile, subtitle-policy)`, and burn-in channels simply do
not share normalized cache with passthrough channels.

**On "one big job":** keep the seamless-transition and distributed-jobs
deliverables separate, but *within* the job service a single job spanning
audio + video + subtitles (one ffmpeg invocation) is correct and efficient. Keep
the deliverables separate; keep each job whole.

---

## 8. Metrics & "now playing" continuity

Today `start-new-stream` opens one `channel_views` + one `media_item_views` per
FFmpeg process and `end-stream-metrics!` closes them on teardown — a clean
"one process = one item" mapping (`db/metrics.clj`).

Under B the manager outlives individual encoders, so ownership moves:
- `channel_views`: opened when the manager starts, closed when it stops (1:1
  with a viewing session — unchanged semantics).
- `media_item_views`: opened/closed **at each handoff** by the manager (it knows
  the current encoder→media-item mapping), not per OS process.

This is the one modest refactor B requires beyond the streaming layer. It is
localized to the manager and far smaller than C's in-stream boundary tracking.

---

## 9. Correctness rules (don't get these wrong)

1. **`#EXT-X-MEDIA-SEQUENCE` is monotonic** and never resets across encoder
   restarts. The manager owns the counter, not FFmpeg.
2. **`#EXT-X-DISCONTINUITY-SEQUENCE` must increment when a discontinuity-tagged
   segment is evicted from the sliding window.** Forgetting this desyncs the
   pickier players (MAG/Enigma2) even though everything else looks right. This
   is the highest-risk correctness detail and gets dedicated unit tests.
3. **Discontinuity tag precedes the first segment of each new encoder slot.**
4. **Normalized output params are byte-for-byte consistent** across slots, so
   the discontinuity is a timestamp reset only.

---

## 10. Schema / config changes

- `ffmpeg_profiles.config` (JSONB): extend to the §5 shape. Column already
  exists; add malli validation. Provide a migration only to seed/upgrade
  existing rows to the new shape.
- Channel-level `subtitle_policy` default (`:passthrough` | `:drop` |
  `:burn-in`), overridable per item; resolved when a job spec is built (§7).
- Move the `active-streams` atom into an Integrant streaming component; add the
  `SegmentStore` protocol (§4.4) with a local-disk implementation.
- New table `transcode_jobs` (**deferred — Phase 5 only**): `id, source_ref,
  target_profile_id, spec jsonb, state, priority, required_accel, worker_id,
  claimed_at, lease_expires_at, artifact_uri, error, created_at, updated_at`
  (see §7 for the `SKIP LOCKED` claim).

---

## 11. Testing strategy

- **Unit (manager):** media-sequence monotonicity; discontinuity-sequence
  increments exactly when a boundary segment is evicted; window GC; playlist
  rendering.
- **Unit (profiles):** each accel produces a valid command + correct
  normalization filter; software-decode fallback path.
- **Integration** (extend `integration-tests.nix`, `TODO.md` Test 8): two short
  events; assert a single `#EXT-X-DISCONTINUITY`, monotonic media-sequence,
  continuous segment numbering across the boundary, and no gap in coverage.
- **Client matrix:** at minimum VLC + ffmpeg as playback validators in CI; spot-
  check ExoPlayer (TiviMate/IPTV Smarters) and hls.js manually.
- **HW:** command-generation tests run anywhere; actual GPU encode tests gated to
  GPU-labelled hosts.

---

## 12. Risks & mitigations

| Risk | Mitigation |
|---|---|
| discontinuity-sequence bug → client desync | Dedicated unit tests on window eviction (§9.2) |
| Overlap doubles GPU sessions at splice | Target scale is 2–3 channels; multiple Quadros + VAAPI fallback; optional NVENC patch; session accounting in the manager |
| Cross-node overlap needs shared segment storage | Keep both encoder slots for a channel **co-located on one node** behind `SegmentStore`; only deferred batch jobs are distributed |
| Pre-transcode (deferred Phase 5) misses airtime deadline | Graceful degrade: live path falls back to inline real-time transcode |
| Normalization adds GPU cost on already-matching sources | Skip scale filter when source already matches target |

---

## 13. Resolved decisions

1. **Segment storage** — co-located local disk now, behind a `SegmentStore`
   protocol (§4.4); shared storage stays a later drop-in, not a day-one cost.
2. **Queue substrate** — PostgreSQL `transcode_jobs` + `FOR UPDATE SKIP LOCKED`
   (§7). No k8s Jobs, no external broker.
3. **Subtitle policy** — optional, channel-dependent burn-in; default
   `:passthrough` / `:drop`, `:burn-in` opt-in per channel (§7, §10).
4. **Phase 5 (distributed transcoding)** — **deferred.** Architect for it in
   Phases 0–4 (the `SegmentStore` seam, location-independent managers, reusable
   profile model), build it after seamless transitions are proven.

## 14. Open questions

- None blocking. Phase 0 can start now.
