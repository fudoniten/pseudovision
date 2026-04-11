# Streaming Implementation Testing Plan

This document provides a comprehensive testing plan to verify the FFmpeg-based HLS streaming implementation (Steps 2-4 of the Walking Skeleton).

## Prerequisites

### 1. System Requirements
- ✅ FFmpeg installed (added to flake.nix)
- ✅ PostgreSQL database running
- ✅ At least one media source configured (Jellyfin)
- ✅ At least one collection with media items

### 2. Verify FFmpeg Installation

```bash
# In development shell
nix develop
ffmpeg -version

# Should show ffmpeg version info
```

### 3. Server Running

```bash
# Start the server
clojure -M:run

# Or in REPL
(start-system!)
```

---

## Test Suite

### Phase 1: Basic Functionality (15 minutes)

#### Test 1.1: Create Test Channel

**Objective:** Create a test channel using the utility

**Steps:**
```clojure
;; In REPL, start the system first
(require '[pseudovision.dev.test-channel :as tc])

;; Get database connection from system
(def ds (get-in @pseudovision.system/system [:pseudovision/database :datasource]))

;; Create test channel
(def test-chan (tc/create-test-channel! ds {:number "999" :name "Stream Test"}))

;; Note the UUID and stream URL
(:uuid test-chan)
;; => "550e8400-e29b-41d4-a716-446655440000"

(:stream-url test-chan)
;; => "http://localhost:8080/stream/550e8400-e29b-41d4-a716-446655440000"
```

**Expected Result:**
- ✅ Channel created with UUID
- ✅ Schedule created with random playback from collection
- ✅ Playout created and built
- ✅ Stream URL printed

---

#### Test 1.2: Verify Channel in API

**Objective:** Confirm channel appears in API endpoints

**Steps:**
```bash
# List all channels
curl http://localhost:8080/api/channels | jq

# Get specific channel
curl http://localhost:8080/api/channels/{channel-id} | jq

# Check M3U playlist
curl http://localhost:8080/iptv/channels.m3u | grep "999"

# Check XMLTV/EPG
curl http://localhost:8080/xmltv | grep "Test"
```

**Expected Result:**
- ✅ Channel appears in channels list
- ✅ Channel appears in M3U with stream URL
- ✅ Channel appears in XMLTV with program guide

---

#### Test 1.3: Request HLS Playlist

**Objective:** Verify FFmpeg starts and generates playlist

**Steps:**
```bash
# Replace {uuid} with actual UUID from test-chan
curl http://localhost:8080/stream/{uuid}
```

**Expected Result:**
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

**Verification:**
- ✅ Returns 200 status
- ✅ Content-Type: application/vnd.apple.mpegurl
- ✅ Playlist contains #EXTM3U header
- ✅ Playlist contains segment URLs with /stream/{uuid}/segment-NNN.ts format
- ✅ Takes ~1 second (FFmpeg startup delay)

---

#### Test 1.4: Verify FFmpeg Process

**Objective:** Confirm FFmpeg is running

**Steps:**
```bash
# Check for running FFmpeg processes
ps aux | grep ffmpeg

# Check temp directory
ls -la /tmp/pseudovision/streams/{uuid}/

# Should see:
# - playlist.m3u8
# - segment-000.ts
# - segment-001.ts
# - segment-002.ts
# ...
```

**Expected Result:**
- ✅ One ffmpeg process running
- ✅ Temp directory exists
- ✅ Playlist file present
- ✅ Multiple .ts segment files present

---

#### Test 1.5: Download and Verify Segment

**Objective:** Verify segments are valid MPEG-TS files

**Steps:**
```bash
# Download a segment
curl http://localhost:8080/stream/{uuid}/segment-000.ts -o /tmp/test-segment.ts

# Verify it's MPEG-TS
file /tmp/test-segment.ts

# Check file size (should be >0 bytes)
ls -lh /tmp/test-segment.ts
```

**Expected Result:**
```
/tmp/test-segment.ts: MPEG transport stream data
-rw-r--r--  1 user  staff   1.2M Apr 10 23:30 /tmp/test-segment.ts
```

**Verification:**
- ✅ Returns 200 status
- ✅ Content-Type: video/MP2T
- ✅ File type is "MPEG transport stream data"
- ✅ File size is reasonable (>100KB typically)

---

### Phase 2: Playback Testing (15 minutes)

#### Test 2.1: VLC Playback

**Objective:** Verify stream plays in VLC

**Steps:**
```bash
vlc http://localhost:8080/stream/{uuid}
```

**Expected Result:**
- ✅ VLC opens and buffers stream
- ✅ Video plays smoothly
- ✅ Audio is synchronized
- ✅ Stream continues indefinitely
- ✅ No stuttering or buffering issues

---

#### Test 2.2: Browser Playback (HLS.js)

**Objective:** Verify stream plays in web browser

**Steps:**
1. Create an HTML file: `/tmp/test-stream.html`

```html
<!DOCTYPE html>
<html>
<head>
    <title>Pseudovision Stream Test</title>
</head>
<body>
    <h1>Stream Test</h1>
    <video id="video" controls width="800"></video>
    
    <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
    <script>
        const video = document.getElementById('video');
        const streamUrl = 'http://localhost:8080/stream/{uuid}'; // Replace {uuid}
        
        if (Hls.isSupported()) {
            const hls = new Hls();
            hls.loadSource(streamUrl);
            hls.attachMedia(video);
            hls.on(Hls.Events.MANIFEST_PARSED, function() {
                console.log('Stream loaded successfully');
            });
            hls.on(Hls.Events.ERROR, function(event, data) {
                console.error('HLS Error:', data);
            });
        } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
            video.src = streamUrl;
        }
    </script>
</body>
</html>
```

2. Open in browser: `file:///tmp/test-stream.html`

**Expected Result:**
- ✅ Video element loads
- ✅ Stream plays in browser
- ✅ Controls work (play/pause/seek)
- ✅ No console errors

---

#### Test 2.3: FFplay Playback

**Objective:** Verify stream with ffplay (simple test)

**Steps:**
```bash
ffplay http://localhost:8080/stream/{uuid}
```

**Expected Result:**
- ✅ Window opens
- ✅ Video plays
- ✅ Audio works
- ✅ Press Q to quit

---

### Phase 3: Process Management (10 minutes)

#### Test 3.1: Process Reuse

**Objective:** Verify multiple clients share same FFmpeg process

**Steps:**
```bash
# Count FFmpeg processes before
ps aux | grep ffmpeg | grep -v grep | wc -l

# Open stream in 3 separate VLC windows
vlc http://localhost:8080/stream/{uuid} &
sleep 2
vlc http://localhost:8080/stream/{uuid} &
sleep 2
vlc http://localhost:8080/stream/{uuid} &

# Count FFmpeg processes after
ps aux | grep ffmpeg | grep -v grep | wc -l
```

**Expected Result:**
- ✅ Before: 0 or 1 process
- ✅ After: Still 1 process (not 3!)
- ✅ All 3 VLC windows play the same stream
- ✅ Check logs for "Reusing existing stream" messages

---

#### Test 3.2: Inspect Active Streams

**Objective:** Verify stream state management

**Steps:**
```clojure
;; In REPL
(require '[pseudovision.http.api.streaming :as streaming])

;; Check active streams
@streaming/active-streams

;; Should show something like:
;; {"550e8400-e29b-41d4-a716-446655440000" 
;;  {:process #object[java.lang.ProcessImpl]
;;   :pid 12345
;;   :output-dir "/tmp/pseudovision/streams/550e8400-e29b-41d4-a716-446655440000"
;;   :channel-uuid "550e8400-e29b-41d4-a716-446655440000"
;;   :last-access 1712789456789}}
```

**Expected Result:**
- ✅ Atom contains one entry per active channel
- ✅ Process object present
- ✅ PID is valid
- ✅ last-access timestamp updates on each request

---

### Phase 4: Error Handling (10 minutes)

#### Test 4.1: Invalid UUID

**Objective:** Verify 404 for non-existent channels

**Steps:**
```bash
curl -i http://localhost:8080/stream/invalid-uuid-12345
```

**Expected Result:**
```
HTTP/1.1 404 Not Found
Content-Type: application/json

{"error":"Channel not found","uuid":"invalid-uuid-12345"}
```

---

#### Test 4.2: Segment Before Playlist Ready

**Objective:** Verify 503 when stream starting

**Steps:**
```bash
# Request segment immediately after triggering stream (within 1 second)
curl http://localhost:8080/stream/{uuid} &
curl -i http://localhost:8080/stream/{uuid}/segment-000.ts
```

**Expected Result (if fast enough):**
```
HTTP/1.1 503 Service Unavailable
Retry-After: 2

{"error":"Stream starting, please retry"}
```

Or if already ready:
```
HTTP/1.1 200 OK
Content-Type: video/MP2T
```

---

#### Test 4.3: Non-Existent Segment

**Objective:** Verify 404 for missing segments

**Steps:**
```bash
curl -i http://localhost:8080/stream/{uuid}/segment-999.ts
```

**Expected Result:**
```
HTTP/1.1 404 Not Found

{"error":"Segment not found"}
```

---

### Phase 5: Cleanup and Maintenance (5 minutes)

#### Test 5.1: Manual Process Cleanup

**Objective:** Verify FFmpeg can be stopped

**Steps:**
```bash
# Get PID
ps aux | grep ffmpeg | grep -v grep

# Kill FFmpeg process
kill {PID}

# Verify it's gone
ps aux | grep ffmpeg | grep -v grep

# Try to play stream again - should restart FFmpeg
vlc http://localhost:8080/stream/{uuid}
```

**Expected Result:**
- ✅ Process stops
- ✅ New process starts on next request
- ✅ Stream continues to work

---

#### Test 5.2: Cleanup Temp Files

**Objective:** Verify temp directory cleanup

**Steps:**
```bash
# Check directory size
du -sh /tmp/pseudovision/streams/{uuid}/

# Kill FFmpeg
kill {PID}

# Wait a moment for cleanup
sleep 5

# FFmpeg should have deleted old segments
ls /tmp/pseudovision/streams/{uuid}/

# Should only see recent segments (10 or fewer .ts files)
```

**Expected Result:**
- ✅ Old segments automatically deleted by FFmpeg
- ✅ Directory doesn't grow unbounded
- ✅ Playlist and recent segments remain

---

#### Test 5.3: Delete Test Channel

**Objective:** Clean up test data

**Steps:**
```clojure
;; In REPL
(tc/delete-test-channel! ds "999")
;; or
(tc/delete-test-channel! ds (:uuid test-chan))
```

**Expected Result:**
- ✅ Channel deleted from database
- ✅ Associated schedule and playout deleted (CASCADE)
- ✅ FFmpeg process continues (orphaned) until you kill it manually

```bash
# Kill any remaining FFmpeg processes
killall ffmpeg

# Clean up temp files
rm -rf /tmp/pseudovision/streams/*
```

---

## Success Criteria Checklist

Mark each criterion as you verify it:

### Basic Functionality
- [ ] Test channel created successfully
- [ ] Channel appears in M3U and XMLTV
- [ ] Playlist request returns valid HLS playlist
- [ ] FFmpeg process starts automatically
- [ ] Segments are valid MPEG-TS files

### Playback
- [ ] Stream plays in VLC without issues
- [ ] Stream plays in web browser (HLS.js)
- [ ] Audio and video are synchronized
- [ ] No buffering or stuttering

### Process Management
- [ ] Multiple clients share same FFmpeg process
- [ ] Active streams tracked in atom
- [ ] last-access timestamp updates correctly

### Error Handling
- [ ] 404 returned for invalid UUID
- [ ] 503 returned when stream starting
- [ ] 404 returned for non-existent segments
- [ ] Process can be restarted after failure

### Performance
- [ ] Playlist loads within ~1 second
- [ ] Segments load quickly (<500ms)
- [ ] Memory usage stable
- [ ] CPU usage reasonable (<50% for one stream)

---

## Troubleshooting

### Issue: Playlist returns empty or malformed

**Check:**
```bash
# Verify FFmpeg is running
ps aux | grep ffmpeg

# Check FFmpeg output directory
ls -la /tmp/pseudovision/streams/{uuid}/

# Check server logs
tail -f logs/pseudovision.log
```

**Solution:**
- Ensure FFmpeg is installed: `which ffmpeg`
- Check permissions on /tmp: `mkdir -p /tmp/pseudovision/streams && chmod 777 /tmp/pseudovision/streams`
- Verify test stream URL is accessible: `curl -I https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`

---

### Issue: Stream stutters or buffers

**Check:**
```bash
# Monitor FFmpeg process
top -p {ffmpeg-pid}

# Check network throughput
curl http://localhost:8080/stream/{uuid}/segment-000.ts -o /dev/null -w "%{speed_download}\n"
```

**Solution:**
- Reduce bitrate in `src/pseudovision/ffmpeg/hls.clj:88` from "2000k" to "1000k"
- Increase segment duration from 6 to 10 seconds
- Check disk I/O: `iostat -x 1`

---

### Issue: FFmpeg not found in container

**Check:**
```bash
# In container
which ffmpeg

# Check flake.nix includes ffmpeg in environmentPackages
```

**Solution:**
- Rebuild container: `nix build .#deployContainer`
- Verify flake.nix has `environmentPackages = [ pkgs.ffmpeg ];`

---

### Issue: No collections found

**Error:** `No collections found. Create a collection first`

**Solution:**
```bash
# Create a media source and collection via API first
curl -X POST http://localhost:8080/api/media/sources \
  -H "Content-Type: application/json" \
  -d '{"name":"Jellyfin","kind":"jellyfin","base_url":"http://jellyfin:8096","api_key":"..."}'

# Then create collection
curl -X POST http://localhost:8080/api/media/collections \
  -H "Content-Type: application/json" \
  -d '{"name":"Test Collection"}'
```

---

## Next Steps

After all tests pass:

1. **Update SESSION_STATE.md** - Mark Step 5 as complete
2. **Real Playout Integration (Phase 1)**
   - Implement Steps 6-8 to use actual media from playout
   - Replace test stream URL with Jellyfin media
3. **Production Readiness (Phase 2)**
   - Move to Integrant component (Step 9)
   - Add cleanup task for idle streams (Step 10)
   - Implement error handling and fallback filler (Step 11)
   - Add event transition detection (Step 12)

---

## Quick Reference

**Key Files:**
- `/net/projects/niten/pseudovision/src/pseudovision/ffmpeg/hls.clj` - FFmpeg command builder
- `/net/projects/niten/pseudovision/src/pseudovision/http/api/streaming.clj` - Streaming handlers
- `/net/projects/niten/pseudovision/src/pseudovision/dev/test_channel.clj` - Test channel utility

**Key URLs:**
- Playlist: `http://localhost:8080/stream/{uuid}`
- Segment: `http://localhost:8080/stream/{uuid}/segment-NNN.ts`
- M3U: `http://localhost:8080/iptv/channels.m3u`
- XMLTV: `http://localhost:8080/xmltv`

**Key Commands:**
```bash
# Start server
clojure -M:run

# Check FFmpeg processes
ps aux | grep ffmpeg

# Monitor temp directory
watch -n 1 'ls -lh /tmp/pseudovision/streams/{uuid}/'

# Test in VLC
vlc http://localhost:8080/stream/{uuid}

# Kill all FFmpeg
killall ffmpeg
```

---

**Good luck with testing!** 🚀
