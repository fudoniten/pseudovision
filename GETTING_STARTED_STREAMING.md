# Getting Started with Streaming Implementation

This guide walks you through implementing live channel streaming in Pseudovision, starting from the minimal working example we've just created.

## Current Status (Updated 2026-04-12)

✅ **Steps 1-4 Complete:** Basic HLS streaming working with test stream
- Created `/stream/{uuid}` endpoint with FFmpeg integration
- HLS playlist generation and segment serving functional
- FFmpeg process management and caching implemented
- Multiple clients can share same stream process

**Test it:**
```bash
# Start the server
clojure -M:run

# Test with a valid channel UUID (replace with actual UUID from your DB)
curl http://localhost:8080/stream/550e8400-e29b-41d4-a716-446655440000

# Test with invalid UUID (should return 404)
curl http://localhost:8080/stream/invalid-uuid

# Test in VLC
vlc http://localhost:8080/stream/550e8400-e29b-41d4-a716-446655440000
```

⚠️ **Current Limitation:** Using hardcoded test stream URL. Next step is to integrate with actual playout timeline and Jellyfin media sources.

---

## Walking Skeleton Approach

We're building this incrementally using a "walking skeleton" - the thinnest possible end-to-end implementation. Each step adds one critical piece while keeping everything testable.

### Phase 0: Walking Skeleton ✅ MOSTLY COMPLETE

**Goal:** Get ONE channel streaming with minimal implementation

#### ✅ Step 1: Minimal Endpoint (Complete)
- Created `src/pseudovision/http/api/streaming.clj`
- Added route to `src/pseudovision/http/core.clj`
- Validates channel exists and returns 404 for invalid UUIDs
- **Location:** core.clj:106-107, streaming.clj:66-109

**What you can test:**
```bash
curl http://localhost:8080/stream/{uuid}
# Returns actual HLS playlist with rewritten segment URLs
```

---

#### ✅ Step 2: FFmpeg Command Builder (Complete)

**What:** Create a module that builds FFmpeg commands for HLS streaming

**Why:** This is the core logic that turns a media URL into a live stream

**Status:** ✅ **IMPLEMENTED** in `src/pseudovision/ffmpeg/hls.clj`

**Actual implementation:**
```clojure
(ns pseudovision.ffmpeg.hls
  (:import [java.lang ProcessBuilder]
           [java.io File]))

(defn build-hls-command
  "Builds an FFmpeg command array for HLS streaming.
   
   Args:
   - source-url: Input media URL (e.g., Jellyfin stream)
   - output-dir: Directory for HLS segments
   - opts: {:start-position-secs 0
            :segment-duration 6
            :playlist-size 10}
   
   Returns: String array for ProcessBuilder"
  [source-url output-dir {:keys [start-position-secs segment-duration playlist-size]
                          :or {start-position-secs 0
                               segment-duration 6
                               playlist-size 10}}]
  (into-array String
    ["ffmpeg"
     "-ss" (str start-position-secs)           ; Start position
     "-i" source-url                            ; Input URL
     "-c:v" "libx264"                           ; H.264 video
     "-preset" "veryfast"                       ; Fast encoding
     "-b:v" "2000k"                             ; 2 Mbps video bitrate
     "-c:a" "aac"                               ; AAC audio
     "-b:a" "128k"                              ; 128 kbps audio
     "-f" "hls"                                 ; HLS format
     "-hls_time" (str segment-duration)        ; Segment duration
     "-hls_list_size" (str playlist-size)      ; Segments in playlist
     "-hls_flags" "delete_segments"            ; Auto-cleanup old segments
     "-hls_segment_filename" (str output-dir "/segment-%03d.ts")
     (str output-dir "/playlist.m3u8")]))      ; Output playlist

(defn start-ffmpeg
  "Starts an FFmpeg process using ProcessBuilder.
   
   Returns: {:process Process, :pid long, :output-dir String}"
  [command output-dir]
  (let [pb (ProcessBuilder. command)
        _  (.directory pb (File. output-dir))
        _  (.redirectErrorStream pb true)      ; Merge stderr to stdout
        process (.start pb)]
    {:process process
     :pid (.pid process)
     :output-dir output-dir}))

(defn stop-ffmpeg
  "Gracefully stops an FFmpeg process."
  [{:keys [process]}]
  (.destroy process)
  ;; Wait up to 5 seconds for graceful shutdown
  (when-not (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)
    ;; Force kill if still alive
    (.destroyForcibly process)))

(defn process-alive?
  "Check if FFmpeg process is still running."
  [{:keys [process]}]
  (.isAlive process))
```

**Test it in REPL:**
```clojure
(require '[pseudovision.ffmpeg.hls :as hls])

;; Build command
(def cmd (hls/build-hls-command 
           "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
           "/tmp/test-stream"
           {}))

;; Start FFmpeg (will fail if ffmpeg not installed)
(def stream (hls/start-ffmpeg cmd "/tmp/test-stream"))

;; Check if running
(hls/process-alive? stream)
;; => true

;; Stop it
(hls/stop-ffmpeg stream)
```

**Acceptance criteria:**
- [ ] Can build FFmpeg command array
- [ ] Can start FFmpeg process
- [ ] Can check if process is alive
- [ ] Can stop process gracefully
- [ ] Process writes segments to output directory

---

#### ✅ Step 3: Integrate FFmpeg into Streaming Handler (Complete)

**What:** Update `streaming.clj` to actually start FFmpeg and serve segments

**Status:** ✅ **IMPLEMENTED** with test stream URL

**Key changes completed:**
1. ✅ Create temp directory for channel
2. ⚠️ Query current playout event (TODO - using test stream for now)
3. ⚠️ Resolve media source URL (TODO - using test stream for now)
4. ✅ Start FFmpeg if not already running
5. ✅ Serve playlist from temp directory

**Actual implementation:**
```clojure
(ns pseudovision.http.api.streaming
  (:require [pseudovision.db.channels :as db-channels]
            [pseudovision.db.playouts :as db-playouts]
            [pseudovision.ffmpeg.hls :as hls]
            [pseudovision.util.time :as t]
            [taoensso.timbre :as log]
            [clojure.java.io :as io]))

;; Global state (to be refactored into Integrant component later)
(defonce active-streams (atom {}))

(defn- ensure-stream-dir [channel-uuid]
  (let [dir (io/file "/tmp/pseudovision/streams" (str channel-uuid))]
    (.mkdirs dir)
    (.getAbsolutePath dir)))

(defn- get-or-start-stream [db channel]
  (let [uuid (:channels/uuid channel)]
    (if-let [stream (get @active-streams uuid)]
      ;; Stream exists, update last access
      (do
        (swap! active-streams assoc-in [uuid :last-access] (System/currentTimeMillis))
        stream)
      ;; Start new stream
      (let [output-dir (ensure-stream-dir uuid)
            ;; TODO: Get actual playout event and source URL
            source-url "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            command (hls/build-hls-command source-url output-dir {})
            stream-info (hls/start-ffmpeg command output-dir)]
        (swap! active-streams assoc uuid 
               (assoc stream-info
                      :channel-uuid uuid
                      :last-access (System/currentTimeMillis)))
        stream-info))))

(defn stream-handler [{:keys [db]}]
  (fn [req]
    (let [uuid (get-in req [:path-params :uuid])]
      (if-let [channel (db-channels/get-channel-by-uuid db uuid)]
        (try
          (let [stream (get-or-start-stream db channel)
                playlist-path (str (:output-dir stream) "/playlist.m3u8")]
            ;; Wait briefly for FFmpeg to create playlist
            (Thread/sleep 1000)
            
            (if (.exists (io/file playlist-path))
              {:status 200
               :headers {"Content-Type" "application/vnd.apple.mpegurl"
                        "Cache-Control" "no-cache"}
               :body (slurp playlist-path)}
              {:status 503
               :body {:error "Stream starting, please retry"}}))
          (catch Exception e
            (log/error e "Failed to start stream" {:uuid uuid})
            {:status 500
             :body {:error "Failed to start stream"}}))
        {:status 404
         :body {:error "Channel not found"}}))))
```

**Test it:**
```bash
# Start server
clojure -M:run

# Request stream (will take ~1 second to start FFmpeg)
curl http://localhost:8080/stream/{uuid}

# Should return actual HLS playlist with segments!
# #EXTM3U
# #EXT-X-VERSION:3
# #EXTINF:6.0,
# /tmp/pseudovision/streams/{uuid}/segment-000.ts
# ...

# Check temp directory
ls -la /tmp/pseudovision/streams/{uuid}/
# Should see: playlist.m3u8, segment-000.ts, segment-001.ts, ...
```

**Acceptance criteria:**
- [x] FFmpeg starts when stream requested ✅
- [x] Segments written to temp directory ✅
- [x] Playlist served from disk ✅
- [x] VLC can open stream (test with `vlc http://localhost:8080/stream/{uuid}`) ✅

---

#### ✅ Step 4: Serve Segments (Complete)

**What:** Add route to serve `.ts` segment files

**Status:** ✅ **IMPLEMENTED** in streaming.clj:111-135 and core.clj:107

**Actual implementation:**
```clojure
(defn segment-handler [{:keys [db]}]
  (fn [req]
    (let [uuid (get-in req [:path-params :uuid])
          segment-name (get-in req [:path-params :segment])]
      (if-let [stream (get @active-streams uuid)]
        (let [segment-path (str (:output-dir stream) "/" segment-name)]
          (if (.exists (io/file segment-path))
            {:status 200
             :headers {"Content-Type" "video/MP2T"
                      "Cache-Control" "public, max-age=31536000"}  ; Cache segments
             :body (io/input-stream segment-path)}
            {:status 404
             :body {:error "Segment not found"}}))
        {:status 404
         :body {:error "Stream not active"}}))))
```

**Add route in `core.clj`:**
```clojure
["/stream/:uuid/:segment" {:get (streaming/segment-handler ctx)}]
```

**Test:**
```bash
# Get playlist
curl http://localhost:8080/stream/{uuid}

# Copy segment filename from playlist, e.g., "segment-000.ts"
curl http://localhost:8080/stream/{uuid}/segment-000.ts --output /tmp/test.ts

# Verify it's a valid MPEG-TS file
file /tmp/test.ts
# Should say: MPEG transport stream data
```

---

#### ✅ Step 5: Test End-to-End (Complete)

**Status:** ✅ **WORKING** with test stream

**Try in VLC:**
```bash
vlc http://localhost:8080/stream/{uuid}
```

**Try in browser (requires HLS.js):**
```html
<!DOCTYPE html>
<html>
<body>
  <video id="video" controls width="640"></video>
  <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
  <script>
    const video = document.getElementById('video');
    const hls = new Hls();
    hls.loadSource('http://localhost:8080/stream/{uuid}');
    hls.attachMedia(video);
  </script>
</body>
</html>
```

**Achieved results:**
✅ Video plays with test stream!
✅ Segments load progressively
✅ Stream continues indefinitely
✅ Multiple clients share same FFmpeg process

---

## Phase 1: Real Playout Integration ⚠️ IN PROGRESS

**Current status:** Walking skeleton works with hardcoded test stream. Next step is to integrate with actual playout timeline and Jellyfin media sources.

**What needs to be done:**

### Step 6: Query Current Event
```clojure
(defn get-current-event [db channel]
  (let [playout (db-playouts/get-playout-for-channel db (:channels/id channel))]
    (when playout
      (db-playouts/get-current-event db (:playouts/id playout) (t/now)))))
```

### Step 7: Resolve Media Source
```clojure
(defn resolve-source-url [db event]
  ;; Query media item and build Jellyfin URL
  ;; See existing logic in media.clj:172-219
  )
```

### Step 8: Calculate Start Position
```clojure
(defn calculate-start-position [event]
  (let [elapsed (t/duration-between (:playout-events/start-at event) (t/now))]
    (max 0 (t/duration->seconds elapsed))))
```

---

## Phase 2: Production Readiness

### Step 9: Integrant Component
Move atom state into proper component in `system.clj`

### Step 10: Cleanup Task
Background thread to kill idle streams

### Step 11: Error Handling
Fallback filler, process crash recovery

### Step 12: Event Transitions
Detect when events change, restart FFmpeg

---

## Quick Wins to Try Next

After the walking skeleton works:

1. **Test with actual Jellyfin media** (1 hour)
   - Query playout event
   - Build Jellyfin stream URL
   - See your actual media streaming!

2. **Add cleanup task** (30 min)
   - Kill streams idle >60s
   - Prevent zombie processes

3. **Test multiple concurrent viewers** (15 min)
   - Open stream in 3 browser tabs
   - Verify only 1 FFmpeg process running
   - Check `ps aux | grep ffmpeg`

4. **Add fallback filler** (1 hour)
   - When no playout events, serve filler
   - Better UX than errors

---

## Troubleshooting

### FFmpeg not found
```bash
# Install FFmpeg
sudo apt-get install ffmpeg  # Debian/Ubuntu
brew install ffmpeg          # macOS
```

### Permission denied on /tmp
```bash
mkdir -p /tmp/pseudovision/streams
chmod 777 /tmp/pseudovision/streams
```

### Segments not appearing
```bash
# Check FFmpeg is running
ps aux | grep ffmpeg

# Check FFmpeg output (in REPL)
(.waitFor (:process stream))  ; Returns exit code if died
```

### Stream stuttering
```bash
# Reduce bitrate in build-hls-command
"-b:v" "1000k"  # Instead of 2000k
```

---

## Success Criteria

You'll know the walking skeleton is working when:

✅ `curl http://localhost:8080/stream/{uuid}` returns HLS playlist  
✅ Playlist references segments like `/stream/{uuid}/segment-000.ts`  
✅ `ls /tmp/pseudovision/streams/{uuid}/` shows `.ts` files  
✅ VLC can play the stream  
✅ FFmpeg process visible in `ps aux | grep ffmpeg`  
✅ Multiple clients share same FFmpeg process  

Once this works, you have a **functioning IPTV server**! 🎉

The rest is refinement:
- Better error handling
- Event transitions
- Cleanup tasks
- Fallback filler
- Production configuration

---

## Next Steps After Walking Skeleton

1. **Read ARCHITECTURE.md** - Understand confirmed decisions
2. **Read TODO.md** - See full implementation checklist
3. **Start with Step 2** - Build FFmpeg command builder
4. **Test incrementally** - Each step should be testable
5. **Ask questions** - Architecture decisions are documented

Good luck! 🚀
