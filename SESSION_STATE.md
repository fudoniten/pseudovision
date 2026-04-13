# Pseudovision Streaming Implementation - Session State

**Session Date:** 2026-04-10 (Initial), 2026-04-12 (Walking Skeleton), 2026-04-13 (Production Deploy)
**Status:** 🎉 HLS Streaming FULLY FUNCTIONAL in Production  
**Current Step:** Ready for Playout Timeline Integration

---

## 📋 Session Summary

**2026-04-10:** Initial planning and architectural decisions  
**2026-04-11 to 2026-04-12:** Implemented walking skeleton with basic HLS streaming  
**2026-04-13:** Deployed to production, fixed numerous bugs, achieved fully working HLS streaming

**Major Achievement (2026-04-13):**
- ✅ HLS streaming fully functional in production Kubernetes cluster
- ✅ FFmpeg transcoding H.264/AAC working
- ✅ Valid MPEG-TS segments being served
- ✅ Test channels can be created and streamed via API
- ✅ Automatic versioning with git timestamps
- ✅ Kubernetes RBAC for automated deployments configured
- ✅ Stream verified working with curl, VLC-ready

**Production Stream URL:** https://pseudovision.kube.sea.fudo.link/stream/{uuid}

---

## ✅ Completed Work

### 1. Documentation Created

#### **TODO.md** (537 lines)
- Comprehensive checklist for XMLTV/M3U/streaming implementation
- 4 priority levels: BLOCKING, HIGH, MEDIUM, LOW
- 10 integration test scenarios with specific assertions
- Manual testing checklist (VLC, Plex, Jellyfin, Kodi, etc.)
- Performance testing targets
- Estimated effort: ~1-2 weeks for complete IPTV functionality

**Key sections:**
- 🚨 BLOCKING: `/stream/{uuid}` endpoint implementation (8 subsections)
- 🔥 HIGH: EPG enhancements (show_in_epg filtering, guide times)
- 📋 MEDIUM: Streaming optimizations (segment caching, audio/subtitle prefs)
- 🔧 LOW: Nice-to-have features (watermarks, DVR, MPEG-TS)
- 🧪 Testing Plan: Unit, integration, manual, and performance tests

#### **ARCHITECTURE.md** (690 lines)
- 7 critical architectural decisions analyzed and confirmed
- Each decision includes: problem, 2-3 options, pros/cons, recommendation
- All decisions confirmed with ✅ verdict
- Implementation guidelines for resolved questions
- Phase 1-3 roadmap with time estimates

**Confirmed decisions:**
1. ✅ Integrant component with atom state
2. ✅ Temp directory segment storage (`/tmp/pseudovision/streams/{uuid}/`)
3. ✅ ProcessBuilder for FFmpeg invocation (non-blocking)
4. ✅ Stop/restart transitions for MVP → discontinuity markers for v1.1
5. ✅ Atom with swap! for concurrency control
6. ✅ Extend config.edn for streaming configuration
7. ✅ Fallback filler support from day 1

#### **GETTING_STARTED_STREAMING.md** (400+ lines)
- Step-by-step implementation guide
- Walking skeleton approach (minimal end-to-end first)
- Complete code examples for each step
- Test commands and troubleshooting tips
- Success criteria for each phase

**Phases:**
- Phase 0: Walking Skeleton (Steps 1-5) - Get basic streaming working
- Phase 1: Real Playout Integration (Steps 6-8) - Connect to actual media
- Phase 2: Production Readiness (Steps 9-12) - Polish and scale

### 2. Code Implemented (Initial + Updates)

#### **src/pseudovision/http/api/streaming.clj** (NEW - ENHANCED)
- ✅ Full streaming handler with FFmpeg integration
- ✅ Validates channel exists by UUID
- ✅ Returns 404 for invalid channels
- ✅ Manages FFmpeg processes via atom state
- ✅ Implements process sharing (multiple clients → one FFmpeg)
- ✅ URL rewriting for HLS segments
- ✅ Segment serving handler
- **Location:** 136 lines, fully functional

#### **src/pseudovision/ffmpeg/hls.clj** (NEW)
- ✅ FFmpeg command builder
- ✅ Process management (start/stop/alive check)
- ✅ HLS-specific configuration
- **Location:** 62 lines, fully functional

#### **src/pseudovision/http/core.clj** (MODIFIED)
- ✅ Added streaming namespace import
- ✅ Added route: `["/stream/:uuid" {:get (streaming/stream-handler ctx)}]`
- ✅ Added route: `["/stream/:uuid/:segment" {:get (streaming/segment-handler ctx)}]`
- **Location:** core.clj:106-107

**Test command:**
```bash
curl http://localhost:8080/stream/{uuid}
# OR
vlc http://localhost:8080/stream/{uuid}
```

**Actual response:**
```
#EXTM3U
#EXT-X-VERSION:3
#EXTINF:6.0,
/stream/{uuid}/segment-000.ts
#EXTINF:6.0,
/stream/{uuid}/segment-001.ts
...
```

---

## 🎯 Current State Analysis

### What Works Now

✅ **XMLTV endpoint** (`/xmltv`, `/epg.xml`)
- Fully implemented in `src/pseudovision/http/api/epg.clj`
- Returns 7-day EPG for all channels
- Uses `list-events-in-window` database query
- Includes channel info and programme listings
- Integration test exists

✅ **M3U endpoint** (`/iptv/channels.m3u`)
- Fully implemented in `src/pseudovision/http/api/m3u.clj`
- Returns playlist with all channels
- Advertises stream URLs at `/stream/{uuid}`
- Integration test exists

✅ **HDHomeRun emulation**
- Device discovery: `/media/devices/X-Plex-Client-Profile-Extra`
- Lineup: `/lineup.json`
- Status: `/lineup_status.json`
- Enables Plex/Emby/Jellyfin auto-discovery

✅ **Stream endpoint (WORKING)** - Updated 2026-04-12
- Route exists and responds
- Validates channel UUID
- ✅ FFmpeg integration complete
- ✅ HLS segment generation working
- ✅ Multiple clients share same stream
- ⚠️ **Uses test stream URL** (playout integration TODO)

### What's Missing (Blocking Real Usage)

⚠️ **Playout timeline integration**
- ✅ FFmpeg integration complete
- ✅ HLS segment generation working
- ❌ No playout timeline → stream conversion
- ❌ Uses hardcoded test stream instead of Jellyfin sources
- ❌ No event transitions

**Impact:** Streaming works, but only with test content. Cannot play actual media library content yet.

### What Needs Enhancement (Non-blocking)

⚠️ **EPG filtering** (`show_in_epg` field)
- Database field exists
- Not queried in XMLTV handler
- All channels appear in EPG (should respect flag)

⚠️ **Guide times** (`guide_start_at`, `guide_finish_at`)
- Database fields exist
- Not used in XMLTV output
- Could enable filler hiding and multi-part episode merging

⚠️ **Guide groups** (`guide_group`)
- Database field exists and populated by scheduler
- Not used in XMLTV output
- Could enable collapsing multi-part content in EPG

---

## 🐛 Bugs Fixed in 2026-04-13 Session

During production deployment, we discovered and fixed numerous bugs:

1. **Collection creation JSONB handling** - Config field wasn't being converted to JSONB (media.clj:258)
2. **FFmpeg profile creation** - Parameterized queries didn't work with next.jdbc (test_channel.clj)
3. **Test collection endpoint** - Same parameterized query issues (test.clj)
4. **Schedule creation missing RETURNING** - create-schedule! didn't return created record (schedules.clj:24)
5. **Slot creation missing RETURNING** - create-slot! didn't return created record (schedules.clj:56)
6. **Channel creation missing RETURNING** - create-channel! didn't return created record (channels.clj:30)
7. **Playout upsert with DO NOTHING** - Changed to DO UPDATE SET to return rows (playouts.clj)
8. **UUID type mismatch** - get-channel-by-uuid needed explicit UUID cast (channels.clj:27)
9. **Segment handler UUID lookup** - Path params are strings but active-streams uses UUID objects (streaming.clj:115)
10. **FFmpeg path in container** - Nix binaries not in PATH, added FFMPEG_PATH env var
11. **Missing /tmp directory** - Nix containers don't have /tmp, added emptyDir volume mount
12. **Kebab-case vs snake_case** - Fixed test_channel.clj to use kebab-case for HoneySQL (schedule-id not schedule_id)

All bugs fixed and streaming working in production!

---

## 📍 Where We Left Off (Updated 2026-04-13)

### Current Position: Production Streaming Working ✅

**Completed (Steps 1-5):**
- ✅ `/stream/{uuid}` endpoint fully functional
- ✅ FFmpeg command builder module (`ffmpeg/hls.clj`)
- ✅ Process management with atom state
- ✅ HLS playlist generation and URL rewriting
- ✅ Segment serving endpoint
- ✅ Multiple clients share same FFmpeg process
- ✅ Basic error handling (404, 503, 500)
- ✅ Can stream test content in VLC/browsers

**Commits implementing this:**
- `f73a67e` - Implement FFmpeg-based HLS streaming (Steps 2-4)
- `55c2c2e` - Add walking skeleton for streaming implementation
- Plus supporting commits for test channels and utilities

### Next Immediate Step: Phase 1 - Playout Integration (Step 6)

**Goal:** Replace hardcoded test stream with actual playout timeline queries

**What to implement:**
1. Query current playout event for channel
2. Resolve Jellyfin media source URL from event
3. Calculate playback position based on event start time
4. Pass real source URL to FFmpeg instead of test stream

**Key location to modify:** `streaming.clj:46-49` (currently hardcoded)

**Estimated time:** 2-3 hours

**References:**
- `src/pseudovision/db/playouts.clj` - Query functions
- `src/pseudovision/http/api/media.clj:172-219` - Media URL resolution example
- `TODO.md` Section 2-3 for detailed requirements

**Test approach:**
```clojure
;; In REPL
(require '[pseudovision.ffmpeg.hls :as hls])

;; Build command
(def cmd (hls/build-hls-command 
           "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
           "/tmp/test-stream"
           {}))

;; Start FFmpeg
(def stream (hls/start-ffmpeg cmd "/tmp/test-stream"))

;; Verify it's running
(hls/process-alive? stream)  ;; => true

;; Check output directory
;; ls /tmp/test-stream/
;; Should see: playlist.m3u8, segment-000.ts, etc.

;; Stop it
(hls/stop-ffmpeg stream)
```

**Success criteria:**
- [ ] Can build FFmpeg command array
- [ ] Can start FFmpeg process with ProcessBuilder
- [ ] Process writes HLS segments to output directory
- [ ] Can check if process is alive
- [ ] Can stop process gracefully

---

## 🗺️ Implementation Roadmap

### Phase 0: Walking Skeleton (3-5 hours)

**Goal:** Get basic streaming working with test media

- [x] **Step 1:** Minimal endpoint (✅ COMPLETE)
- [ ] **Step 2:** FFmpeg command builder (📍 NEXT - 1-2 hours)
- [ ] **Step 3:** Integrate FFmpeg into handler (1-2 hours)
- [ ] **Step 4:** Serve .ts segments (30 min)
- [ ] **Step 5:** Test end-to-end in VLC (30 min)

**Deliverable:** One channel streaming test media in VLC

### Phase 1: Real Playout Integration (2-3 hours)

- [ ] **Step 6:** Query current playout event
- [ ] **Step 7:** Resolve media source URL (Jellyfin)
- [ ] **Step 8:** Calculate playback start position

**Deliverable:** Channels stream actual media from playout timeline

### Phase 2: Production Readiness (3-5 hours)

- [ ] **Step 9:** Integrant component (move atom to proper lifecycle)
- [ ] **Step 10:** Cleanup task (kill idle streams)
- [ ] **Step 11:** Error handling (fallback filler)
- [ ] **Step 12:** Event transitions (detect and handle)

**Deliverable:** Production-ready streaming system

### Phase 3: Enhancements (Future)

- [ ] EPG filtering (`show_in_epg`)
- [ ] Guide time support (`guide_start_at`/`guide_finish_at`)
- [ ] HLS discontinuity markers (seamless transitions)
- [ ] Channel artwork/logos
- [ ] Segment caching and sharing
- [ ] Database FFmpeg profile support
- [ ] Watermarks
- [ ] Timeshift/DVR

---

## 🔑 Key Decisions Made

### Architecture

**Process Management:** Integrant component with atom state
- Stores active streams: `{channel-uuid -> {:process ... :output-dir ... :last-access ...}}`
- Background task cleans up idle streams (>60s)
- Proper lifecycle (start with system, cleanup on shutdown)

**Segment Storage:** Temp directory per channel
- Location: `/tmp/pseudovision/streams/{uuid}/`
- FFmpeg writes segments directly to disk
- Auto-cleanup via `hls_flags delete_segments`

**FFmpeg Invocation:** Java ProcessBuilder
- Non-blocking (returns immediately)
- Full process control (kill, check alive, get PID)
- Better than `clojure.java.shell/sh` for long-running processes

**Event Transitions:** Stop/restart for MVP
- Simple: kill old FFmpeg, start new one
- Accept 1-2 second interruption for viewers
- Plan to upgrade to discontinuity markers in v1.1

**Concurrency:** Atom with careful swap!
- Prevents duplicate FFmpeg processes for same channel
- Idiomatic Clojure
- No locks needed

**Configuration:** Extend config.edn
- New section: `:streaming {:segment-duration 6, :temp-dir "/tmp/...", ...}`
- Follows existing pattern in codebase

**Error Recovery:** Fallback filler from day 1
- When no playout events → serve fallback filler
- When media unavailable → serve fallback filler
- Better UX than showing errors

### Streaming Protocol

**MVP:** HLS only
- Most compatible (browsers, IPTV clients)
- Well-documented FFmpeg support
- MPEG-TS direct streaming deferred to v1.1

**Segment Duration:** 6 seconds
- Good balance of latency vs. overhead
- Standard for live HLS

**Playlist Size:** 10 segments
- 60 seconds of buffering
- Enough for smooth playback

### Database Integration

**FFmpeg Profiles:** Hardcoded defaults for MVP
- H.264 video (libx264, veryfast preset, 2000k bitrate)
- AAC audio (128k bitrate)
- Database `ffmpeg_profiles` table integration deferred to v1.1

**Fallback Filler:** Use channel's `fallback_filler_id`
- Query when no playout events available
- Query when media source fails
- Return 503 if NULL

---

## 📁 File Structure

### New Files Created

```
/net/projects/niten/pseudovision/
├── TODO.md                                    # Complete implementation checklist
├── ARCHITECTURE.md                            # Confirmed architectural decisions
├── GETTING_STARTED_STREAMING.md              # Step-by-step guide
├── SESSION_STATE.md                          # This file
└── src/pseudovision/
    └── http/api/
        └── streaming.clj                     # Minimal streaming handler (NEW)
```

### Files to Create Next

```
src/pseudovision/
└── ffmpeg/
    └── hls.clj                               # FFmpeg command builder (NEXT)
```

### Files to Modify Later

```
src/pseudovision/
├── system.clj                                # Add :pseudovision/streaming component
├── config.clj                                # Add streaming config parsing
└── http/api/
    └── streaming.clj                         # Extend with FFmpeg integration
```

---

## 🧪 Testing Strategy

### Unit Tests (Future)

Create `test/pseudovision/ffmpeg/hls_test.clj`:
- Test `build-hls-command` with various options
- Test process lifecycle (start/stop/alive?)
- Mock ProcessBuilder for testing

### Integration Tests (Future)

Add to `integration-tests.nix`:
- Test `/stream/{uuid}` returns 200 for valid channel
- Test `/stream/{uuid}` returns 404 for invalid UUID
- Test playlist contains `#EXTM3U` header
- Test segment URLs are valid
- Test actual segment files exist and are MPEG-TS

### Manual Testing

**VLC:**
```bash
vlc http://localhost:8080/stream/{uuid}
```

**Browser (HLS.js):**
```html
<video id="video" controls></video>
<script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
<script>
  const hls = new Hls();
  hls.loadSource('http://localhost:8080/stream/{uuid}');
  hls.attachMedia(document.getElementById('video'));
</script>
```

**curl:**
```bash
# Get playlist
curl http://localhost:8080/stream/{uuid}

# Get segment
curl http://localhost:8080/stream/{uuid}/segment-000.ts -o test.ts

# Verify segment
file test.ts  # Should say "MPEG transport stream data"
```

---

## 💡 Key Insights from Session

### Understanding XMLTV and M3U

**XMLTV** is the TV guide (EPG):
- Channel definitions (ID, name, number, logo)
- Programme listings (start/stop times, title, description)
- Covers 7-14 days of programming
- Used by clients to show "what's on now" and "what's coming up"

**M3U** is the channel list:
- Channel metadata (UUID, name, number, group)
- Stream URLs (where to watch)
- Links to XMLTV via `tvg-id` attribute
- Used by clients to build channel lineup

**The Connection:**
- M3U says: "Channel ABC has `tvg-id=123` and streams at `http://server/stream/123`"
- XMLTV says: "Channel with `id=123` is showing Breaking News from 8-9 PM"
- Client matches them by ID to show program info with playable streams

### Current Pseudovision Status

**Already working:**
- ✅ Scheduling engine (channels, schedules, playouts, events)
- ✅ Database schema (all tables exist)
- ✅ XMLTV generation (7-day EPG)
- ✅ M3U generation (channel playlist)
- ✅ HDHomeRun emulation (Plex/Emby/Jellyfin discovery)
- ✅ Jellyfin media source integration
- ✅ Media scanning and metadata

**Missing piece:**
- ❌ Live streaming (`/stream/{uuid}` endpoint)

**This is why:** IPTV clients can load the guide and channel list, but clicking "play" fails (404).

### Why Walking Skeleton Approach?

Instead of implementing all features before testing:
1. Build minimal end-to-end version first
2. Test it works (even with fake data)
3. Add features incrementally
4. Keep everything working at each step

**Benefits:**
- Can test in VLC after ~3-5 hours of work
- Validates architecture decisions early
- Builds confidence with working code
- Easier debugging (smaller increments)

---

## 🔧 Technical Details

### FFmpeg Command Example

```bash
ffmpeg \
  -ss 0 \                                      # Start position (seconds)
  -i "https://jellyfin/stream/item123" \       # Input URL
  -c:v libx264 \                               # H.264 video codec
  -preset veryfast \                           # Fast encoding
  -b:v 2000k \                                 # 2 Mbps video bitrate
  -c:a aac \                                   # AAC audio codec
  -b:a 128k \                                  # 128 kbps audio
  -f hls \                                     # HLS format
  -hls_time 6 \                                # 6-second segments
  -hls_list_size 10 \                          # Keep 10 segments
  -hls_flags delete_segments \                 # Auto-cleanup
  -hls_segment_filename /tmp/.../segment-%03d.ts \
  /tmp/pseudovision/streams/{uuid}/playlist.m3u8
```

### HLS Playlist Example

```
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:6.0,
/stream/{uuid}/segment-000.ts
#EXTINF:6.0,
/stream/{uuid}/segment-001.ts
#EXTINF:6.0,
/stream/{uuid}/segment-002.ts
```

### ProcessBuilder Pattern

```clojure
(import '[java.lang ProcessBuilder]
        '[java.io File])

(defn start-ffmpeg [command output-dir]
  (let [pb (ProcessBuilder. command)
        _  (.directory pb (File. output-dir))
        _  (.redirectErrorStream pb true)
        process (.start pb)]
    {:process process
     :pid (.pid process)
     :output-dir output-dir}))

(defn stop-ffmpeg [{:keys [process]}]
  (.destroy process)
  (when-not (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)
    (.destroyForcibly process)))
```

### Atom State Pattern

```clojure
(defonce active-streams (atom {}))

(defn get-or-start-stream [channel-uuid]
  (get 
    (swap! active-streams 
           (fn [streams]
             (if (contains? streams channel-uuid)
               ;; Update timestamp
               (update-in streams [channel-uuid :last-access] (constantly (now)))
               ;; Start new stream
               (assoc streams channel-uuid (start-stream! channel-uuid)))))
    channel-uuid))
```

---

## 📖 Reference Commands

### Development

```bash
# Start server
clojure -M:run

# Start with custom config
clojure -M:run -c /path/to/config.edn

# Start REPL
clojure -M:repl

# Run tests
clojure -M:test

# Run migrations
clojure -X:migrate
```

### Testing Streaming

```bash
# Test endpoint exists
curl http://localhost:8080/stream/{uuid}

# Test with VLC
vlc http://localhost:8080/stream/{uuid}

# Check FFmpeg processes
ps aux | grep ffmpeg

# Monitor temp directory
watch -n 1 'ls -lh /tmp/pseudovision/streams/{uuid}/'

# Check segment is valid MPEG-TS
file /tmp/pseudovision/streams/{uuid}/segment-000.ts
```

### Debugging

```bash
# Tail server logs
tail -f /path/to/logs

# Check FFmpeg output (if captured)
cat /tmp/pseudovision/streams/{uuid}/ffmpeg.log

# Kill all FFmpeg processes
killall ffmpeg

# Clean up temp files
rm -rf /tmp/pseudovision/streams/*
```

---

## 🎓 Learning Resources

### Specifications

- **HLS RFC:** https://datatracker.ietf.org/doc/html/rfc8216
- **XMLTV Format:** https://wiki.xmltv.org/index.php/XMLTVFormat
- **M3U Format:** https://en.wikipedia.org/wiki/M3U

### FFmpeg

- **HLS Options:** https://ffmpeg.org/ffmpeg-formats.html#hls-2
- **FFmpeg Streaming Guide:** https://trac.ffmpeg.org/wiki/StreamingGuide
- **FFmpeg Protocols:** https://ffmpeg.org/ffmpeg-protocols.html

### Reference Implementation

- **ErsatzTV:** https://github.com/ErsatzTV/ErsatzTV
  - See streaming implementation for guidance
  - Similar architecture (playout timeline → live stream)

---

## 🚀 Quick Start for Next Session

1. **Read this document** to recall context (5 min)
2. **Read `GETTING_STARTED_STREAMING.md` Step 2** (5 min)
3. **Create `src/pseudovision/ffmpeg/hls.clj`** (1 hour)
4. **Test in REPL** with test stream URL (15 min)
5. **Continue to Step 3** (integrate into handler)

---

## ✅ Session Checklist

**Documents created:**
- [x] TODO.md
- [x] ARCHITECTURE.md
- [x] GETTING_STARTED_STREAMING.md
- [x] SESSION_STATE.md (this file)

**Code created:**
- [x] src/pseudovision/http/api/streaming.clj
- [x] Route added to src/pseudovision/http/core.clj

**Decisions confirmed:**
- [x] All 7 architectural decisions
- [x] Walking skeleton approach
- [x] Implementation order
- [x] Testing strategy

**Next step identified:**
- [x] Step 2: FFmpeg command builder
- [x] Code examples provided
- [x] Test approach documented

**Ready to resume:** ✅ YES

---

## 📞 Questions to Address Next Session

1. **Do you have FFmpeg installed?**
   - Test: `which ffmpeg`
   - Install if needed: `brew install ffmpeg` (macOS) or `apt-get install ffmpeg` (Linux)

2. **Do you have test channels in the database?**
   - Need at least one channel with a UUID to test
   - Can create via API or directly in DB

3. **Do you have test media available?**
   - Jellyfin server configured?
   - Or can use public test stream: `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`

4. **What's your testing preference?**
   - VLC (easiest)
   - Web browser with HLS.js
   - IPTV client (TiviMate, Kodi, etc.)

---

## 🎯 Success Criteria

You'll know implementation is complete when:

✅ `curl /stream/{uuid}` returns HLS playlist with segments  
✅ VLC can play the stream  
✅ Multiple clients share same FFmpeg process  
✅ Streams transition between playout events  
✅ Fallback filler plays when no events scheduled  
✅ Integration tests pass  
✅ Performance targets met (CPU, memory, concurrent streams)  

**Estimated total time:** 8-12 hours of focused work

---

**END OF SESSION STATE**

Resume implementation by starting with Step 2 in `GETTING_STARTED_STREAMING.md`.
