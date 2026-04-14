# Pseudovision XMLTV/M3U/Streaming TODO

This document tracks the completion status of XMLTV, M3U, and live streaming functionality.

**Legend:**
- 🚨 **BLOCKING** — Must be completed for basic IPTV functionality
- 🔥 **HIGH** — Important for completeness and user experience
- 📋 **MEDIUM** — Valuable enhancements
- 🔧 **LOW** — Nice-to-have improvements

---

## 📚 Related Documentation

- **TODO.md** (this file) — Comprehensive implementation checklist
- **ARCHITECTURE.md** — Architectural decisions and rationale
- **GETTING_STARTED_STREAMING.md** — Step-by-step implementation guide
- **SESSION_STATE.md** — Session history and current status
- **TESTING_STREAMING.md** — Testing procedures and scenarios
- **API_TEST_CHANNELS.md** — Test channel creation and management
- **COLLECTIONS_GUIDE.md** — Collections configuration guide

---

## ⚡ Recent Progress Summary (Updated 2026-04-13)

**🎉 STREAMING FULLY FUNCTIONAL (as of 2026-04-13):**

1. **Complete HLS Streaming Infrastructure** (Sections 1, 4-6) ✅ WORKING
   - `/stream/{uuid}` endpoint fully operational
   - FFmpeg transcoding with H.264/AAC producing valid MPEG-TS segments
   - HLS playlists generating and updating correctly
   - Segments serving with proper Content-Type headers
   - Multiple clients sharing FFmpeg processes
   - Tested and verified with curl, segments downloading successfully
   - **Stream URL:** https://pseudovision.kube.sea.fudo.link/stream/{uuid}

2. **Production Deployment** ✅ COMPLETE
   - Kubernetes RBAC configured for automated deployments
   - Automatic versioning using git commit timestamps
   - Container images tagged with YYYYMMDD version tags
   - FFmpeg integrated into Nix container with proper library paths
   - /tmp volume mounted for segment storage
   - Version endpoint for deployment verification

3. **API Enhancements** ✅ COMPLETE
   - FFmpeg profiles API (`/api/ffmpeg/profiles`)
   - Test channel creation API working end-to-end
   - Version endpoint (`/api/version`)

**🚨 REMAINING GAPS (for real media library usage):**
1. **Playout Timeline Integration** (Section 2) — Currently using hardcoded test stream
2. **Media Source Resolution** (Section 3) — Not fetching actual Jellyfin URLs
3. **Event Transitions** (Section 7) — No support for switching between media items
4. **Fallback Handling** (Section 8) — No filler support for gaps

**Next Priority:** Integrate with playout timeline to stream actual media library content instead of test stream.

---

## 🚨 BLOCKING: Live Streaming Implementation

### Overview
The M3U playlist (`/iptv/channels.m3u`) and HDHomeRun lineup (`/lineup.json`) advertise stream URLs at `/stream/{uuid}`, but this endpoint **does not exist**. IPTV clients can load the channel list and EPG, but cannot play video.

**Goal:** Implement HLS (HTTP Live Streaming) endpoint that converts the playout timeline into a live video stream.

---

### 1. Route & Handler Setup 🚨 BLOCKING

- [x] **Add route in `src/pseudovision/http/core.clj`** ✅ DONE
  - Add `["/stream/:uuid" {:get (streaming/stream-handler ctx)}]` around line 90
  - Requires namespace `[pseudovision.http.api.streaming :as streaming]`
  - **Location:** src/pseudovision/http/core.clj:106
  
- [x] **Create `src/pseudovision/http/api/streaming.clj`** ✅ DONE
  - Create new namespace for streaming handlers
  - Implement `stream-handler` function accepting `{:keys [db ffmpeg]}`
  - Extract `:uuid` from route params
  - Return HLS master playlist (`.m3u8`)
  - **Location:** src/pseudovision/http/api/streaming.clj

- [x] **Add helper in `src/pseudovision/db/channels.clj`** ✅ DONE
  - Use existing `get-channel-by-uuid` (line 24) to look up channel
  - Return 404 if channel doesn't exist
  - **Already implemented:** streaming.clj:75 uses `db-channels/get-channel-by-uuid`

**Test checkpoint:** `curl http://localhost:8080/stream/{uuid}` should return 200 with valid channel, 404 with invalid UUID ✅ IMPLEMENTED

---

### 2. Playout Timeline Query 🚨 BLOCKING

- [x] **Query current event for channel** ✅ DONE (commit f6e292c)
  - Use `get-channel` to find channel by UUID
  - Get playout for channel: `get-playout-for-channel` (src/pseudovision/db/playouts.clj:18)
  - Get current event: `get-current-event` (src/pseudovision/db/playouts.clj:74-83)
    - Pass playout ID and `(t/now)`
    - Returns event where `start_at <= now < finish_at`
  - **Location:** streaming.clj:97-141 (get-current-stream-source)
  
- [x] **Handle missing/future events** ✅ DONE
  - If no current event, check `get-upcoming-events` for next event ✅
  - If no events at all, use fallback filler (from `channels.fallback_filler_id`) ✅
  - If fallback filler is NULL, return 503 Service Unavailable with message ✅
  - **Location:** streaming.clj:71-95 (get-fallback-stream-source)

**Test checkpoint:** Query should return current media item or fallback gracefully ✅ IMPLEMENTED (pending deployment)

---

### 3. Media Item Resolution 🚨 BLOCKING

- [x] **Resolve playback URL for media item** ✅ DONE (commit f6e292c)
  - Use event's `media_item_id` to query media_items table ✅
  - Join with `media_sources` to get Jellyfin connection details ✅
  - Join with `media_libraries` for library metadata ✅
  - Uses `db-media/get-media-item-with-source` from media.clj ✅
  - **Location:** streaming.clj:19-32 (get-jellyfin-stream-url)
  
- [x] **Calculate playback position** ✅ DONE
  - Calculate elapsed time: `now - event.start_at` ✅
  - Convert to seconds for FFmpeg `-ss` parameter ✅
  - Handles in-point offset for chapter trimming ✅
  - **Location:** streaming.clj:34-51 (calculate-start-position)

**Test checkpoint:** Should retrieve valid Jellyfin stream URL and calculate correct position ✅ IMPLEMENTED (pending deployment & testing)

---

### 4. FFmpeg Command Builder 🚨 BLOCKING

- [x] **Create `src/pseudovision/ffmpeg/hls.clj` namespace** ✅ DONE
  - Create `build-hls-command` function
  - Accept: source URL, start position, channel config, FFmpeg profile
  - Return: FFmpeg command vector for `ProcessBuilder`
  - **Location:** src/pseudovision/ffmpeg/hls.clj:5-34
  
- [ ] **Load FFmpeg profile from database** ⚠️ TODO
  - Query `ffmpeg_profiles` table using `channels.ffmpeg_profile_id`
  - Parse JSONB `config` field (contains FFmpeg parameters)
  - Apply profile settings to command
  - **Status:** Currently using hardcoded defaults (libx264, aac, veryfast preset)
  
- [x] **Build base HLS command** ✅ DONE
  ```clojure
  ["ffmpeg"
   "-ss" (str elapsed-seconds)              ; Start position ✅
   "-i" source-url                          ; Input from Jellyfin ✅
   "-c:v" "libx264"                         ; Video codec ✅
   "-c:a" "aac"                             ; Audio codec ✅
   "-f" "hls"                               ; HLS output format ✅
   "-hls_time" "6"                          ; 6-second segments ✅
   "-hls_list_size" "10"                    ; Keep 10 segments in playlist ✅
   "-hls_flags" "delete_segments"           ; Clean up old segments ✅
   "-hls_segment_filename" "segment-%03d.ts" ✅
   "playlist.m3u8"]                         ✅
  ```
  - **Location:** src/pseudovision/ffmpeg/hls.clj:20-34
  
- [ ] **Handle channel streaming mode** ⚠️ TODO (from `channels.streaming_mode` enum)
  - `ts` — Output MPEG-TS directly (single stream, no HLS)
  - `ts_hybrid` — MPEG-TS with HLS fallback (implement HLS first)
  - `hls_direct` — HLS passthrough (no transcode, direct copy)
  - `hls_segmenter` — HLS with live segmenter (full transcode)
  - **For MVP, implement `hls_segmenter` only** — Currently hardcoded to this mode

**Test checkpoint:** Command should generate valid FFmpeg command that runs without errors ✅ PARTIALLY IMPLEMENTED (basic command works)

---

### 5. HLS Playlist Generation 🚨 BLOCKING

- [x] **Generate master playlist** (`.m3u8`) ✅ DONE
  - Return `Content-Type: application/vnd.apple.mpegurl` ✅
  - Simple single-variant playlist for MVP:
    ```
    #EXTM3U
    #EXT-X-VERSION:3
    #EXT-X-TARGETDURATION:6
    #EXT-X-MEDIA-SEQUENCE:0
    #EXTINF:6.0,
    /stream/{uuid}/segment-000.ts
    #EXTINF:6.0,
    /stream/{uuid}/segment-001.ts
    ...
    ```
  - **Location:** streaming.clj:66-96 (serves and rewrites FFmpeg-generated playlist)
  - **Implementation:** FFmpeg generates playlist, handler rewrites segment URLs (streaming.clj:23-30)
  
- [x] **Implement segment serving** ✅ DONE
  - Add route `["/stream/:uuid/segment-:n.ts" {:get (streaming/segment-handler ctx)}]` ✅
  - Serve FFmpeg-generated `.ts` segments ✅
  - Return `Content-Type: video/MP2T` ✅
  - Return 404 if segment doesn't exist (expired or not yet generated) ✅
  - **Location:** streaming.clj:111-135, route at core.clj:107

**Test checkpoint:** HLS playlist should load in VLC or browser with HLS.js ✅ IMPLEMENTED (basic functionality working)

---

### 6. Segment Management 🚨 BLOCKING

- [x] **Decide segment storage strategy** ✅ DONE
  - **Option A:** Pipe FFmpeg output to response stream (stateless, no disk)
  - **Option B:** Write segments to temp directory, serve from disk (cacheable) ✅ CHOSEN
  - **Recommended:** Option B for MVP (easier debugging, allows caching)
  - **Implementation:** streaming.clj:16-21 creates temp directories
  
- [x] **Implement segment cache** ✅ PARTIALLY DONE
  - Create temp directory per channel: `/tmp/pseudovision/streams/{uuid}/` ✅
  - FFmpeg writes segments to this directory ✅
  - Clean up segments older than 60 seconds ✅ (FFmpeg's `-hls_flags delete_segments` handles this)
  - Handle multiple concurrent viewers (share FFmpeg process per channel) ✅
  - **Location:** streaming.clj:14-60 (active-streams atom tracks processes)
  
- [x] **Handle playlist updates** ✅ DONE
  - FFmpeg continuously updates `playlist.m3u8` ✅
  - Serve updated playlist on each request ✅
  - Implement rolling window (keep last 10 segments) ✅ (configured in hls.clj:31)
  - **Location:** streaming.clj:78-91

**Test checkpoint:** Multiple clients can stream same channel without spawning multiple FFmpeg processes ✅ IMPLEMENTED (streaming.clj:32-60 reuses existing streams)

---

### 7. Event Transitions 🚨 BLOCKING

- [ ] **Detect event boundaries** ❌ NOT IMPLEMENTED
  - Check if current time > `event.finish_at`
  - If so, stop FFmpeg process for old event
  - Query next event and start new FFmpeg process
  - **Status:** Not yet implemented (requires playout integration first)
  
- [ ] **Implement discontinuity markers** ❌ NOT IMPLEMENTED
  - Insert `#EXT-X-DISCONTINUITY` in playlist when event changes
  - Signals to client that stream properties may change (codec, resolution, etc.)
  - **Status:** Not yet implemented
  
- [ ] **Handle filler injection** ❌ NOT IMPLEMENTED
  - Check if event has filler (pre/mid/post-roll)
  - Create temporary playout sequence: `[pre-filler, content, post-filler]`
  - Treat as single continuous stream with discontinuities
  - **Status:** Not yet implemented

**Test checkpoint:** Stream should transition smoothly from one media item to the next ❌ NOT IMPLEMENTED

---

### 8. Error Handling & Resilience 🚨 BLOCKING

- [ ] **Handle missing media sources** ⚠️ PARTIAL
  - If Jellyfin source is unreachable, return fallback filler
  - Log error with channel ID and source ID
  - **Status:** Basic error handling exists (streaming.clj:98-102), but no fallback filler logic
  
- [x] **Handle FFmpeg failures** ✅ PARTIALLY DONE
  - Catch FFmpeg process exit codes ✅
  - Return 503 Service Unavailable with retry-after header ✅ (streaming.clj:93-96)
  - Log stderr output for debugging ⚠️ (stderr redirected but not actively monitored)
  - **Location:** streaming.clj:76-102
  
- [ ] **Handle playout gaps** ❌ NOT IMPLEMENTED
  - If no current or upcoming events, use `channels.fallback_filler_id`
  - If no fallback filler, return offline slate (static image/video)
  - Consider implementing "technical difficulties" placeholder
  - **Status:** Not yet implemented (requires playout integration)
  
- [ ] **Process cleanup** ⚠️ PARTIAL
  - Kill FFmpeg processes on stream disconnect ⚠️ (process tracked but no cleanup daemon)
  - Implement timeout (e.g., kill process if no clients for 30s) ❌
  - Clean up temp files on shutdown ⚠️ (FFmpeg deletes segments, but directory not cleaned)
  - **Status:** hls.clj:49-56 has stop-ffmpeg function, but not actively used

**Test checkpoint:** Server should handle errors gracefully without crashes ✅ PARTIALLY IMPLEMENTED (basic error handling works)

---

## 🔥 HIGH PRIORITY: EPG Enhancements

### EPG Filtering by `show_in_epg` Flag

- [ ] **Update `list-events-in-window` query** (src/pseudovision/db/playouts.clj:60-72)
  - Add `WHERE` clause: `[:= :c.show-in-epg true]`
  - Channels with `show_in_epg = FALSE` will not appear in XMLTV
  - They will still appear in M3U playlist (intentional behavior)
  
- [ ] **Add integration test** (integration-tests.nix:599-603)
  - Create channel with `show_in_epg = FALSE`
  - Verify it's absent from `/xmltv` output
  - Verify it's present in `/iptv/channels.m3u` output

**Test checkpoint:** Hidden channels excluded from EPG but streamable

---

### Guide Time Support

- [ ] **Use `guide_start_at` / `guide_finish_at` in XMLTV**
  - Modify `event->xmltv` function (src/pseudovision/http/api/epg.clj:20-32)
  - Check if `guide_start_at` is NULL
  - If NULL, use `start_at` / `finish_at` (current behavior)
  - If not NULL, use `guide_start_at` / `guide_finish_at`
  
- [ ] **Test filler hiding scenario**
  - Create event with `start_at = 20:00`, `finish_at = 20:30`
  - Set `guide_start_at = 20:05`, `guide_finish_at = 20:25` (hide 5min filler on each end)
  - Verify XMLTV shows 20:05-20:25, not 20:00-20:30

**Test checkpoint:** EPG displays adjusted times when guide times are set

---

### Guide Group Collapsing (Optional)

- [ ] **Group events by `guide_group` before rendering XMLTV**
  - In `xmltv-handler`, partition events by `guide_group`
  - Events with same `guide_group` → single `<programme>` entry
  - Use earliest `guide_start_at` and latest `guide_finish_at`
  - Use title from first event (or concatenate titles)
  
- [ ] **Test multi-part episode scenario**
  - Create 4 events (TV show episodes) with same `guide_group = 123`
  - Verify XMLTV shows single entry spanning all 4 events
  - Verify title reflects multi-part content

**Test checkpoint:** Multi-part content appears as single EPG entry

---

## 🔥 HIGH PRIORITY: Channel Artwork

- [ ] **Add logo URLs to M3U** (src/pseudovision/http/api/m3u.clj:6-16)
  - Query `channel_artwork` table (src/pseudovision/db/channels.clj:46-50)
  - Add `tvg-logo="http://{host}/logos/{uuid}.{ext}"` to M3U entries
  - Only include if artwork exists
  
- [ ] **Add `<icon>` to XMLTV channels** (src/pseudovision/http/api/epg.clj:9-18)
  - Query `channel_artwork` for each channel
  - Add `<icon src="{url}" />` element to `<channel>`
  
- [ ] **Create `/logos/:uuid` endpoint**
  - Add route in `src/pseudovision/http/core.clj`
  - Serve channel artwork from database or proxy from source
  - Return appropriate `Content-Type` (image/png, image/jpeg, etc.)

**Test checkpoint:** IPTV clients display channel logos

---

## 📋 MEDIUM PRIORITY: Streaming Optimizations

### Direct Streaming Mode (No Transcode)

- [ ] **Detect compatible formats**
  - Query `media_streams` table for video/audio codecs
  - Check if format matches client requirements (H.264/AAC for HLS)
  - If compatible, use FFmpeg `-c copy` (stream copy, no transcode)
  
- [ ] **Implement `hls_direct` streaming mode**
  - When `channels.streaming_mode = 'hls_direct'`
  - Build FFmpeg command with `-c:v copy -c:a copy`
  - Much lower CPU usage, near-instant startup

**Test checkpoint:** Direct streaming uses <5% CPU vs. 50%+ for transcode

---

### Audio/Subtitle Preferences

- [ ] **Apply audio language preferences**
  - Read `channels.preferred_audio_language` (e.g., 'eng', 'spa')
  - Read `channels.preferred_audio_title` (e.g., 'Commentary')
  - Query `media_streams` for matching audio stream
  - Add FFmpeg `-map` parameter to select stream
  
- [ ] **Implement subtitle modes** (`channels.subtitle_mode` enum)
  - `none` — No subtitle streams
  - `any` — Include all subtitle streams
  - `forced_only` — Only forced subtitles (foreign dialogue)
  - `default_only` — Only default subtitle track
  - `burn_in` — Burn subtitles into video with FFmpeg overlay filter

**Test checkpoint:** Stream uses preferred audio language

---

### Segment Caching & Sharing

- [ ] **Share FFmpeg process across clients**
  - Maintain map of active streams: `{channel-uuid -> ffmpeg-process}`
  - If FFmpeg already running for channel, reuse existing segments
  - Only start new process if none exists
  
- [ ] **Implement segment cache**
  - Cache segments in memory (atom or LRU cache)
  - Serve from cache if available
  - Fall back to disk if not in cache
  
- [ ] **Clean up idle streams**
  - Track last access time per stream
  - Kill FFmpeg process if no requests for 60 seconds
  - Clean up temp directory

**Test checkpoint:** 10 concurrent viewers = 1 FFmpeg process per channel

---

## 📋 MEDIUM PRIORITY: Advanced EPG Features

### Extended XMLTV Metadata

- [ ] **Add episode numbering** (`<episode-num>`)
  - Query `metadata.season_number` and `metadata.episode_number`
  - Add `<episode-num system="onscreen">S{season}E{episode}</episode-num>`
  
- [ ] **Add categories** (`<category>`)
  - Query `metadata.genres` (JSONB array)
  - Add `<category lang="en">{genre}</category>` for each
  
- [ ] **Add content ratings** (`<rating>`)
  - Query `metadata.content_rating`
  - Add `<rating system="MPAA"><value>{rating}</value></rating>`
  
- [ ] **Add original air date** (`<date>`)
  - Query `metadata.release_date`
  - Add `<date>{year}</date>`

**Test checkpoint:** EPG shows rich metadata in IPTV clients that support it

---

## 🔧 LOW PRIORITY: Nice-to-Have Features

### Watermark Support

- [ ] **Load watermark configuration**
  - Query `watermarks` table using `channels.watermark_id`
  - Parse watermark settings (position, opacity, size)
  
- [ ] **Apply FFmpeg overlay filter**
  - Add `-filter_complex` with watermark image overlay
  - Position according to watermark config

**Test checkpoint:** Channel stream shows watermark overlay

---

### Timeshift/DVR

- [ ] **Record stream segments**
  - Keep segments for configurable duration (e.g., 4 hours)
  - Store in persistent directory (not `/tmp`)
  
- [ ] **Add `tvg-rec` to M3U**
  - Mark channels with DVR support in M3U entries
  - Add `timeshift` attribute
  
- [ ] **Implement playback from history**
  - Accept optional `?start={timestamp}` query parameter
  - Start FFmpeg from historical segment instead of live edge

**Test checkpoint:** Can rewind and watch past 4 hours

---

### MPEG-TS Direct Streaming (Alternative to HLS)

- [ ] **Implement continuous MPEG-TS stream**
  - When `channels.streaming_mode = 'ts'`
  - FFmpeg outputs continuous MPEG-TS to HTTP response
  - No segmentation, lower latency
  
- [ ] **Handle event transitions in TS mode**
  - Concatenate multiple FFmpeg processes
  - Use discontinuity indicators in TS packets

**Test checkpoint:** VLC plays TS stream with lower latency than HLS

---

## 🧪 Testing Plan

### Unit Tests

- [ ] **Database queries**
  - Test `get-channel-by-uuid` with valid/invalid UUIDs
  - Test `get-current-event` at various time points
  - Test `list-events-in-window` with `show_in_epg` filter
  
- [ ] **FFmpeg command builder**
  - Test command generation with various profiles
  - Test audio/subtitle preference application
  - Test watermark overlay configuration

### Integration Tests (Add to `integration-tests.nix`)

#### Test 1: XMLTV Availability ✅ Already Exists
```python
epg_out = server.succeed(f"curl -sf {base}/xmltv")
assert "<?xml" in epg_out or "<tv" in epg_out
```

#### Test 2: M3U Availability ✅ Already Exists
```python
m3u_out = server.succeed(f"curl -sf {base}/iptv/channels.m3u")
assert "#EXTM3U" in m3u_out
```

#### Test 3: Stream Endpoint Returns 200
- [ ] Add test: `curl -sf {base}/stream/{valid-uuid}` returns 200
- [ ] Add test: `curl -sf {base}/stream/invalid-uuid` returns 404
- [ ] Add test: Response `Content-Type` is `application/vnd.apple.mpegurl`

#### Test 4: HLS Playlist Structure
- [ ] Add test: Playlist contains `#EXTM3U`
- [ ] Add test: Playlist contains `#EXT-X-VERSION`
- [ ] Add test: Playlist contains segment references

#### Test 5: HLS Segment Availability
- [ ] Add test: Parse segment URL from playlist
- [ ] Add test: `curl` segment URL returns `Content-Type: video/MP2T`
- [ ] Add test: Segment file is valid MPEG-TS (check magic bytes)

#### Test 6: EPG Filtering
- [ ] Create channel with `show_in_epg = FALSE`
- [ ] Add test: Channel UUID not in `/xmltv` output
- [ ] Add test: Channel UUID present in `/iptv/channels.m3u` output

#### Test 7: Multi-Client Streaming
- [ ] Add test: Start 3 concurrent streams for same channel
- [ ] Add test: Verify only 1 FFmpeg process running (shared segments)
- [ ] Add test: All 3 clients receive same segments

#### Test 8: Event Transition
- [ ] Create playout with 2 short events (30 seconds each)
- [ ] Add test: Stream playlist after 35 seconds shows discontinuity
- [ ] Add test: Segment sequence continues across event boundary

#### Test 9: Fallback Filler
- [ ] Create channel with no playout events
- [ ] Set `fallback_filler_id` to test filler preset
- [ ] Add test: Stream returns filler content, not 503 error

#### Test 10: Error Handling
- [ ] Create channel with playout pointing to missing media item
- [ ] Add test: Stream returns 503 or fallback filler
- [ ] Add test: Error logged to server logs

### Manual Testing Checklist

- [ ] **VLC Desktop** — Load M3U, verify channels play
- [ ] **Plex** — Add as HDHomeRun device, verify discovery and playback
- [ ] **Jellyfin** — Add as Live TV tuner, verify EPG and streaming
- [ ] **Kodi** — Load M3U and XMLTV, verify PVR integration
- [ ] **TiviMate (Android)** — Test mobile IPTV client
- [ ] **Web browser (HLS.js)** — Test HLS playback in Chrome/Firefox

### Performance Testing

- [ ] **Single stream CPU usage** — Should be <30% per stream (with transcode)
- [ ] **10 concurrent streams** — All channels play smoothly
- [ ] **Memory usage** — Should not leak over 24 hours
- [ ] **Segment cleanup** — Temp directory should not grow unbounded

---

## Status Summary

| Component | Status | Blocking? |
|-----------|--------|-----------|
| XMLTV endpoint (`/xmltv`, `/epg.xml`) | ✅ Implemented | No |
| M3U playlist (`/iptv/channels.m3u`) | ✅ Implemented | No |
| HDHomeRun emulation (`/lineup.json`) | ✅ Implemented | No |
| Database schema (channels, events, metadata) | ✅ Complete | No |
| EPG filtering (`show_in_epg`) | ⚠️ Field exists, not queried | No |
| Guide time support (`guide_start_at`) | ⚠️ Field exists, not used | No |
| Stream endpoint (`/stream/{uuid}`) | ✅ Implemented (basic) | No |
| HLS playlist generation | ✅ Implemented (basic) | No |
| FFmpeg transcoding pipeline | ✅ Implemented (basic) | No |
| **Playout timeline integration** | ❌ Not implemented | **YES** |
| **Media source resolution** | ❌ Not implemented | **YES** |
| Event transitions & discontinuity | ❌ Not implemented | **YES** |

---

## Next Steps

**Immediate priority:** Implement `/stream/{uuid}` endpoint with basic HLS streaming (Section 1-8 above).

**Recommended implementation order:**
1. Route & handler setup (Section 1)
2. Playout timeline query (Section 2)
3. Media item resolution (Section 3)
4. FFmpeg command builder (Section 4)
5. HLS playlist generation (Section 5)
6. Segment management (Section 6)
7. Event transitions (Section 7)
8. Error handling (Section 8)
9. Testing (Section 🧪)
10. EPG enhancements (Section 🔥)

**Estimated effort:** 
- **MVP streaming** (Sections 1-8): ~3-5 days
- **EPG enhancements** (Section 🔥): ~1 day
- **Optimizations** (Section 📋): ~2-3 days
- **Full testing suite** (Section 🧪): ~1-2 days

**Total:** ~1-2 weeks for complete IPTV functionality
