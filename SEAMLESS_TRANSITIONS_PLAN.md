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

### Phase 0 — hwaccel-aware profiles *(prerequisite)*
- Add `pseudovision.ffmpeg.profile` (logical config → per-accel flags + the
  normalization filter chain).
- Refactor `hls.clj/build-hls-command` and `build-slate-command` to consume it.
- Validate `config` shape (malli) in the profile API.
- Auto-detect available accel at startup (probe `/dev/dri`, `nvidia-smi`); pick
  a safe default, allow per-channel override.
- *Deliverable:* existing per-event streaming runs on CPU/NVENC/VAAPI by config.
  No transition change yet.

### Phase 1 — Channel Stream Manager (own the playlist)
- Introduce the manager component; move `active-streams` into an Integrant
  component (the file already flags this as "to be refactored into Integrant").
- Encoders write plain segments (no `-f hls`); manager builds `playlist.m3u8`
  with a **monotonic** media-sequence and sliding-window GC.
- Still one encoder per channel, restarted at boundaries (as today) — but now a
  restart no longer resets the client-visible sequence, and a
  `#EXT-X-DISCONTINUITY` is emitted at the boundary.
- *Deliverable:* transitions no longer reset the playlist; clients stop
  reloading at boundaries. (Dead-air gap may still exist — fixed in Phase 2.)

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

### Phase 4 — Fault isolation & watchdog
- Per-encoder watchdog: a slot that dies/stalls is restarted; a source that
  fails to open splices in the fallback slate/filler **without** tearing down
  the channel manager.
- Preserve metrics across handoffs (open/close `media_item_views` at each splice
  rather than per-process; see §8).
- *Deliverable:* a bad file degrades to a brief hitch, never a dead channel.

### Phase 5 — Distributed transcoding *(decoupled; see §7)*

Rough sizing: Phases 0–4 are the seamless-transition deliverable. 0 and 1 are
the bulk of the work; 2–4 are smaller increments on the manager.

---

## 7. Distributed transcoding — recommendation

**Counter-suggestion: decouple it from the seamless-transition work, and do not
fold it into the live encoder.** Live streaming is *real-time, low-latency,
seek-aware* transcoding; a transcode "job" is *batch, throughput-oriented,
cluster-scheduled*. Those are different latency and failure domains; coupling
them couples three concerns (real-time muxing, batch transcode, k8s scheduling)
into one fragile unit. Ship Phases 0–4 first; they stand alone with inline
real-time transcoding.

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
                :uri  "..."}}      ; shared storage (PVC / object store)
 ;; execution: :in-process (local) | :distributed (k8s)
```

- **Workers** are pods with node affinity to GPU type
  (`accel=nvenc` / `accel=vaapi` node labels); the job's `:target` accel routes
  it to a matching host. VAAPI on the Intel iGPUs is the fallback tier when the
  Quadros are saturated.
- **Queue/state:** a `transcode_jobs` table (DB-backed, fits the existing
  `db/` + Integrant style) is simplest and observable; k8s `Job` objects are an
  alternative if you'd rather the cluster own lifecycle. Recommend DB-backed
  queue + worker pods polling, for visibility and retry control.
- **Subtitles:** keep video/audio/subtitle in one job spec (a single ffmpeg run
  does all three). But note the real decision is **burn-in vs passthrough**:
  burn-in (hard subs) forces a full video re-encode and breaks `-c copy`;
  soft-sub passthrough into HLS means WebVTT side-playlists. Recommend defaulting
  to `:passthrough`/`:drop` and treating burn-in as an explicit per-job option,
  since it changes the cost profile.

**On "one big job":** don't merge the seamless-transition and distributed-jobs
deliverables, but *within* the job service a single job spanning audio + video +
subtitles (one ffmpeg invocation) is correct and efficient. Keep the
deliverables separate; keep each job whole.

If you'd rather, Phase 5 can be deferred entirely — Phases 0–4 deliver seamless
transitions on their own.

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
- New table `transcode_jobs` (Phase 5 only): `id, source_ref, target_profile_id,
  spec jsonb, state, worker, artifact_uri, error, created_at, updated_at`.
- Move the `active-streams` atom into an Integrant streaming component.

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
| Cross-node overlap needs shared segment storage | Keep both encoder slots for a channel **co-located on one node**; only Phase-5 batch jobs are distributed |
| Pre-transcode (Phase 5) misses airtime deadline | Graceful degrade: live path falls back to inline real-time transcode |
| Normalization adds GPU cost on already-matching sources | Skip scale filter when source already matches target |

---

## 13. Open questions

1. **Segment overlap across nodes** — confirm co-location is acceptable, or do we
   want shared storage (PVC/object store) from day one?
2. **Queue substrate for Phase 5** — DB-backed `transcode_jobs` (recommended) vs
   native k8s `Job` objects?
3. **Subtitle policy default** — passthrough/drop with opt-in burn-in
   (recommended), or burn-in by default for STB simplicity?
4. **Phase 5 timing** — build ahead-of-time normalization right after Phase 4
   (it eases live GPU load), or defer until seamless transitions are proven in
   production?
