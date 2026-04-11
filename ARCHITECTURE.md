# Streaming Architecture Decisions

This document outlines the critical architectural decisions for implementing live channel streaming in Pseudovision.

**Status:** 🚧 Under Discussion  
**Last Updated:** 2026-04-10

---

## Overview

The streaming implementation needs to convert a playout timeline (scheduled media items with start/finish times) into continuous live video streams that IPTV clients can consume. This requires careful architectural decisions that will affect scalability, complexity, and user experience.

---

## Decision 1: Process Management Strategy 🔴 CRITICAL

### Problem
FFmpeg processes for live transcoding need lifecycle management. Each channel stream requires:
- Starting FFmpeg when first client connects
- Keeping FFmpeg running while clients are watching
- Stopping FFmpeg when all clients disconnect
- Restarting FFmpeg when events transition

### Options

#### Option A: **Stateless Per-Request** (Simplest)
```clojure
;; Each request spawns its own FFmpeg process
(defn stream-handler [req]
  (let [process (start-ffmpeg! ...)]
    {:status 200
     :body (process-output-stream process)}))
```

**Pros:**
- Simple implementation
- No state management needed
- No coordination between requests

**Cons:**
- ❌ **MAJOR:** Multiple FFmpeg processes per channel (1 per viewer)
- ❌ Very high CPU usage (30-50% per stream)
- ❌ Cannot share segments between clients
- ❌ Not viable for >2-3 concurrent viewers

**Verdict:** ❌ **Not recommended** - doesn't scale

---

#### Option B: **Shared Process with Atom State** (Recommended for MVP)
```clojure
;; Global atom tracking active streams
(defonce active-streams 
  (atom {})) ;; {channel-uuid -> {:process ... :last-access ... :segment-dir ...}}

(defn stream-handler [req]
  (let [uuid (get-in req [:path-params :uuid])
        stream (or (get @active-streams uuid)
                   (start-new-stream! uuid))]
    (swap! active-streams assoc-in [uuid :last-access] (now))
    {:status 200
     :body (slurp (str (:segment-dir stream) "/playlist.m3u8"))}))

;; Background thread cleans up idle streams
(defn cleanup-idle-streams! []
  (doseq [[uuid {:keys [process last-access]}] @active-streams]
    (when (> (- (now) last-access) 60000) ;; 60s idle
      (kill-process! process)
      (swap! active-streams dissoc uuid))))
```

**Pros:**
- ✅ One FFmpeg process per channel (shared by all viewers)
- ✅ Low complexity - just an atom and background thread
- ✅ Segments can be cached and shared
- ✅ Good enough for 10-20 concurrent channels

**Cons:**
- ⚠️ Single point of failure (process crash affects all viewers)
- ⚠️ No clustering support (all streams on one node)
- ⚠️ Manual cleanup thread needed

**Verdict:** ✅ **Recommended for MVP** - simple and effective

---

#### Option C: **Integrant Component** (Best for Production)
```clojure
;; New component in system.clj
(defmethod ig/init-key :pseudovision/streaming [_ {:keys [db ffmpeg]}]
  (let [state (atom {})
        cleanup-task (schedule-cleanup-task! state)]
    {:state state
     :cleanup-task cleanup-task
     :db db
     :ffmpeg ffmpeg}))

(defmethod ig/halt-key! :pseudovision/streaming [_ {:keys [state cleanup-task]}]
  (cancel-task! cleanup-task)
  (doseq [[_ stream] @state]
    (kill-stream! stream)))

;; Pass streaming component to HTTP handlers
(defmethod ig/prep-key :pseudovision/http [_ opts]
  (assoc opts
         :streaming (ig/ref :pseudovision/streaming)))
```

**Pros:**
- ✅ Proper lifecycle management (start/stop with system)
- ✅ Clean shutdown (kills all FFmpeg processes)
- ✅ Testable (can inject mock component)
- ✅ Follows existing Integrant pattern

**Cons:**
- ⚠️ Slightly more complex than Option B
- ⚠️ Need to update system.clj and config.clj

**Verdict:** ✅ **Recommended for production** - worth the extra setup

**DECISION NEEDED:** Start with **Option B** for rapid prototyping, refactor to **Option C** before v1.0?

---

## Decision 2: Segment Storage Strategy 🔴 CRITICAL

### Problem
HLS requires serving `.ts` segments and `.m3u8` playlists. Where should these files live?

### Options

#### Option A: **Temp Directory per Channel**
```clojure
;; /tmp/pseudovision/streams/{channel-uuid}/
;;   ├── playlist.m3u8
;;   ├── segment-000.ts
;;   ├── segment-001.ts
;;   └── ...
```

**Pros:**
- ✅ Simple - FFmpeg writes directly to disk
- ✅ Easy debugging (can inspect files)
- ✅ Built-in caching (filesystem)
- ✅ Works with `hls_flags delete_segments` (auto-cleanup)

**Cons:**
- ⚠️ Disk I/O overhead
- ⚠️ Need cleanup on crash/restart
- ⚠️ Not viable for containerized/stateless deployments

**Verdict:** ✅ **Recommended for MVP** - simplicity wins

---

#### Option B: **In-Memory Segment Store**
```clojure
(defonce segments 
  (atom {})) ;; {[channel-uuid segment-num] -> byte-array}

;; FFmpeg pipes output to process
;; Parse segments from stream and store in atom
```

**Pros:**
- ✅ No disk I/O
- ✅ Truly stateless (no cleanup needed)
- ✅ Fast segment serving

**Cons:**
- ❌ **MAJOR:** Very complex to implement
- ❌ Need to parse MPEG-TS from FFmpeg stdout
- ❌ Memory usage scales with viewer count
- ❌ Harder to debug

**Verdict:** ❌ **Not recommended for MVP** - too complex

---

#### Option C: **Hybrid (Disk + Memory Cache)**
```clojure
;; FFmpeg writes to disk
;; Recently accessed segments cached in memory (LRU)
(defonce segment-cache (atom (lru/cache 1000)))
```

**Pros:**
- ✅ Best of both worlds
- ✅ Fast for hot segments
- ✅ Disk fallback for cold segments

**Cons:**
- ⚠️ Added complexity
- ⚠️ Cache invalidation edge cases

**Verdict:** 📋 **Consider for optimization phase**

**DECISION NEEDED:** Start with **Option A** (temp directory)?

---

## Decision 3: FFmpeg Invocation Method 🟡 IMPORTANT

### Problem
How should we spawn and manage FFmpeg processes in Clojure?

### Options

#### Option A: **clojure.java.shell/sh** (Current approach for ffprobe)
```clojure
(require '[clojure.java.shell :as sh])

(defn start-ffmpeg [cmd-args]
  (sh/sh "ffmpeg" "-i" input-url ...))
```

**Pros:**
- ✅ Already used in codebase (scanner.clj:74)
- ✅ Simple for one-shot commands

**Cons:**
- ❌ **BLOCKING** - waits for process to complete
- ❌ Cannot stream output
- ❌ No process handle to kill later
- ❌ Not suitable for long-running streams

**Verdict:** ❌ **Not suitable for streaming**

---

#### Option B: **ProcessBuilder** (Recommended)
```clojure
(defn start-ffmpeg [cmd-args output-dir]
  (let [pb (ProcessBuilder. (into-array String cmd-args))]
    (.directory pb (io/file output-dir))
    (.redirectErrorStream pb true)
    (let [process (.start pb)]
      {:process process
       :pid (.pid process)
       :output-dir output-dir})))

(defn kill-ffmpeg [stream]
  (.destroy (:process stream))
  (.waitFor (:process stream) 5 TimeUnit/SECONDS)
  (when (.isAlive (:process stream))
    (.destroyForcibly (:process stream))))
```

**Pros:**
- ✅ Non-blocking - returns immediately
- ✅ Full process control (kill, check alive, get PID)
- ✅ Can redirect output to files
- ✅ Standard Java, no dependencies

**Cons:**
- ⚠️ Slightly more verbose than shell/sh
- ⚠️ Need proper cleanup on errors

**Verdict:** ✅ **Recommended**

---

#### Option C: **Library (e.g., conch, clj-commons/process)**
```clojure
(require '[me.raynes.conch :as conch])

(defn start-ffmpeg [cmd-args]
  (conch/proc "ffmpeg" cmd-args))
```

**Pros:**
- ✅ Higher-level API
- ✅ Nice process management

**Cons:**
- ⚠️ External dependency
- ⚠️ Another library to learn
- ⚠️ ProcessBuilder is good enough

**Verdict:** 📋 **Optional** - not worth the dependency

**DECISION NEEDED:** Use **ProcessBuilder** (Option B)?

---

## Decision 4: Event Transition Handling 🟡 IMPORTANT

### Problem
When the playout timeline advances from one media item to the next, how should the stream transition?

### Scenario
```
Event 1: Movie (20:00 - 21:30)
Event 2: TV Show (21:30 - 22:00)
```

At 21:30, viewers watching the stream should seamlessly transition from the movie to the TV show.

### Options

#### Option A: **Stop and Restart FFmpeg** (Simplest)
```clojure
(defn check-event-transition [stream]
  (let [current-event (get-current-event (:channel-uuid stream))
        stream-event (:event stream)]
    (when (not= (:id current-event) (:id stream-event))
      ;; Event changed!
      (kill-ffmpeg! stream)
      (start-ffmpeg! current-event))))
```

**Pros:**
- ✅ Very simple implementation
- ✅ Clean separation between events

**Cons:**
- ❌ **Stream interruption** (1-5 second gap for clients)
- ❌ Clients may need to reconnect
- ❌ Poor user experience

**Verdict:** ⚠️ **Acceptable for MVP**, but not ideal

---

#### Option B: **HLS Discontinuity Markers** (Recommended)
```clojure
;; When event changes, insert discontinuity in playlist
;; FFmpeg continues running but switches input source

#EXTINF:6.0,
/stream/{uuid}/segment-042.ts
#EXT-X-DISCONTINUITY
#EXTINF:6.0,
/stream/{uuid}/segment-043.ts
```

**Implementation:**
1. Detect event transition
2. Start new FFmpeg process for new event
3. Insert `#EXT-X-DISCONTINUITY` in playlist
4. Switch segment serving to new process
5. Kill old process after grace period

**Pros:**
- ✅ Seamless playback for clients
- ✅ Standard HLS feature
- ✅ No reconnection needed

**Cons:**
- ⚠️ More complex - need to coordinate two FFmpeg processes briefly
- ⚠️ Segment numbering needs careful handling

**Verdict:** ✅ **Recommended for good UX**

---

#### Option C: **FFmpeg Input Concat** (Most Complex)
```clojure
;; Use FFmpeg concat demuxer or filter to chain inputs
ffmpeg -f concat -safe 0 -i inputs.txt -c copy output.m3u8

;; inputs.txt dynamically updated with next media items
```

**Pros:**
- ✅ Single FFmpeg process
- ✅ Truly seamless transitions

**Cons:**
- ❌ **Very complex** - need to predict next events
- ❌ Hard to handle live playout changes
- ❌ Difficult to debug

**Verdict:** ❌ **Not recommended** - too complex for benefit

**DECISION NEEDED:** Use **Option A** for MVP, plan for **Option B** in v1.0?

---

## Decision 5: Concurrency Model 🟡 IMPORTANT

### Problem
How should the streaming system handle concurrent requests and process management?

### Key Considerations
- Multiple clients requesting same channel simultaneously
- Segment cleanup while clients are reading
- Event transitions while streams are active
- Server shutdown while streams are running

### Options

#### Option A: **Atom with Swap for Synchronization**
```clojure
(defonce streams (atom {}))

(defn get-or-create-stream [channel-uuid]
  (if-let [stream (get @streams channel-uuid)]
    (do
      (swap! streams update-in [channel-uuid :last-access] (constantly (now)))
      stream)
    ;; Race condition here - two threads might both enter this branch
    (let [new-stream (start-stream! channel-uuid)]
      (swap! streams assoc channel-uuid new-stream)
      new-stream)))
```

**Pros:**
- ✅ Simple Clojure idiom
- ✅ No locks needed

**Cons:**
- ❌ **Race condition** - might start duplicate streams
- ❌ Need careful swap! ordering

**Fix:** Use `swap!` with check-and-set
```clojure
(defn get-or-create-stream [channel-uuid]
  (get 
    (swap! streams 
           (fn [s]
             (if (contains? s channel-uuid)
               (update-in s [channel-uuid :last-access] (constantly (now)))
               (assoc s channel-uuid (start-stream! channel-uuid)))))
    channel-uuid))
```

**Verdict:** ✅ **Recommended** - idiomatic Clojure

---

#### Option B: **Agent for Async Process Management**
```clojure
(defonce stream-manager (agent {}))

(defn get-or-create-stream [channel-uuid respond]
  (send stream-manager 
        (fn [streams]
          (if-let [stream (get streams channel-uuid)]
            (do (respond stream) streams)
            (let [new-stream (start-stream! channel-uuid)]
              (respond new-stream)
              (assoc streams channel-uuid new-stream))))))
```

**Pros:**
- ✅ Serialized updates (no race conditions)
- ✅ Non-blocking for caller

**Cons:**
- ⚠️ More complex - need callback pattern
- ⚠️ Harder to reason about

**Verdict:** 📋 **Overkill for this use case**

---

#### Option C: **Core.async Channels**
```clojure
(defonce stream-requests (chan))

(go-loop []
  (when-let [[channel-uuid respond-ch] (<! stream-requests)]
    (let [stream (get-or-create-stream-sync channel-uuid)]
      (>! respond-ch stream))
    (recur)))
```

**Pros:**
- ✅ Clean async model
- ✅ Backpressure handling

**Cons:**
- ⚠️ Another dependency (core.async)
- ⚠️ Overkill for simple case

**Verdict:** ❌ **Not worth the complexity**

**DECISION NEEDED:** Use **Option A** (atom with careful swap!)?

---

## Decision 6: Configuration Strategy 🟢 NICE-TO-HAVE

### Problem
Where should streaming configuration live?

### Options

#### Option A: **Extend Existing Config**
```clojure
;; resources/config.edn
{:streaming {:segment-duration 6
             :playlist-size 10
             :cleanup-interval-seconds 60
             :temp-dir "/tmp/pseudovision/streams"}}

;; config.clj
(defn ->system-config [config]
  {:pseudovision/streaming (merge {:segment-duration 6
                                   :playlist-size 10}
                                  (:streaming config))})
```

**Verdict:** ✅ **Recommended** - follows existing pattern

---

#### Option B: **Database Configuration**
```sql
CREATE TABLE streaming_config (
  id SERIAL PRIMARY KEY,
  segment_duration INTEGER DEFAULT 6,
  playlist_size INTEGER DEFAULT 10,
  ...
);
```

**Pros:**
- ✅ Runtime reconfigurable
- ✅ Per-channel overrides possible

**Cons:**
- ⚠️ Overkill for global settings
- ⚠️ Migration overhead

**Verdict:** 📋 **Consider for per-channel settings later**

**DECISION NEEDED:** Use **Option A** (extend config.edn)?

---

## Decision 7: Error Recovery Strategy 🟢 NICE-TO-HAVE

### Problem
FFmpeg processes can crash, media sources can become unavailable, network issues can occur.

### Options

#### Option A: **Fail Fast, Client Retries**
```clojure
(defn stream-handler [req]
  (try
    (serve-stream! ...)
    (catch Exception e
      (log/error e "Stream failed")
      {:status 503 :body {:error "Stream unavailable"}})))
```

**Verdict:** ✅ **Minimum viable** - let clients handle retries

---

#### Option B: **Auto-Restart on Failure**
```clojure
(defn monitor-stream [stream]
  (future
    (while (.isAlive (:process stream))
      (Thread/sleep 1000))
    ;; Process died
    (when (should-restart? stream)
      (restart-stream! stream))))
```

**Verdict:** 📋 **Good for production** - reduces manual intervention

---

#### Option C: **Fallback Filler**
```clojure
(defn get-stream-source [channel]
  (or (current-event-source channel)
      (fallback-filler-source channel)
      (technical-difficulties-slate)))
```

**Verdict:** ✅ **Highly recommended** - better UX than errors

**DECISION NEEDED:** Implement fallback filler from day 1?

---

## Recommended Architecture (MVP)

Based on the analysis above, here's the recommended starting architecture:

### Component Structure
```
:pseudovision/streaming (Integrant component)
  ├── state (atom) - {channel-uuid -> stream-info}
  ├── cleanup-task - scheduled executor for idle stream cleanup
  └── config - {:segment-duration 6, :temp-dir "/tmp/...", ...}
```

### Stream Lifecycle
1. **Client requests `/stream/{uuid}`**
2. **Handler checks atom** - is stream already running?
3. **If not, start new stream:**
   - Query current event from playout
   - Resolve media source URL
   - Create temp directory
   - Build FFmpeg command
   - Start ProcessBuilder
   - Store in atom
4. **If yes, update last-access timestamp**
5. **Serve playlist or segment from temp directory**
6. **Background task cleans up idle streams every 60s**

### Event Transitions (MVP)
- Query current event on each playlist request
- If event changed, kill FFmpeg and start new one
- Accept brief interruption (1-2 seconds)
- *(Improve with discontinuity markers in v1.1)*

### Error Handling
- Process crash → return 503, let client retry
- Missing media → use fallback filler if configured
- No fallback → return 503 with message
- *(Add auto-restart in v1.1)*

---

## Action Items

**Before implementation starts:**

- [ ] **Decision 1:** Confirm Integrant component approach (Option C)
- [ ] **Decision 2:** Confirm temp directory storage (Option A)
- [ ] **Decision 3:** Confirm ProcessBuilder usage (Option B)
- [ ] **Decision 4:** Confirm stop/restart for MVP, plan discontinuity markers
- [ ] **Decision 5:** Confirm atom-based concurrency (Option A)
- [ ] **Decision 6:** Confirm config.edn extension (Option A)
- [ ] **Decision 7:** Confirm fallback filler support from day 1

**Create issues/subtasks for:**
- [ ] Streaming component implementation
- [ ] FFmpeg command builder module
- [ ] Segment serving handler
- [ ] Event transition logic
- [ ] Cleanup background task
- [ ] Fallback filler integration

---

## Open Questions

1. **Should we support MPEG-TS direct streaming in MVP, or HLS only?**
   - Recommendation: HLS only for MVP, TS in v1.1

2. **What should happen if playout has no events?**
   - Recommendation: Serve fallback filler if configured, else 503

3. **Should segment cleanup be time-based or space-based?**
   - Recommendation: Time-based (delete segments >60s old)

4. **How do we handle FFmpeg transcoding profiles from database?**
   - Recommendation: Start with hardcoded sensible defaults, add profile support in v1.1

5. **Should we log FFmpeg stderr for debugging?**
   - Recommendation: Yes, but rate-limited to avoid log spam

---

## References

- **ErsatzTV streaming:** https://github.com/ErsatzTV/ErsatzTV (reference implementation)
- **HLS spec:** https://datatracker.ietf.org/doc/html/rfc8216
- **FFmpeg HLS options:** https://ffmpeg.org/ffmpeg-formats.html#hls-2
- **Clojure ProcessBuilder:** https://clojuredocs.org/clojure.java.io/ProcessBuilder
