(ns pseudovision.streaming.manager
  "Channel Stream Manager: owns the authoritative HLS playlist for each channel.

   One background loop per active channel is the SOLE owner of that channel's
   `:state` atom (encoder + playlist), so it mutates without locking. Each tick
   it (1) starts the initial encoder, (2) transitions to new content when the
   playout says so or restarts a dead encoder, and (3) ingests newly-finalized
   segments from the current encoder's scratch dir into the channel playlist,
   which keeps a monotonic media-sequence and inserts an #EXT-X-DISCONTINUITY at
   each encoder boundary (see SEAMLESS_TRANSITIONS_PLAN.md §4.1, §9).

   Request threads only read the rendered playlist / segments and bump a separate
   `:last-access` atom, so they never contend with the loop."
  (:require [clojure.java.io :as io]
             [pseudovision.db.ffmpeg :as db-ffmpeg]
             [pseudovision.db.metrics :as db-metrics]
             [pseudovision.db.playouts :as db-playouts]
             [pseudovision.ffmpeg.hls :as hls]
            [pseudovision.streaming.playlist :as playlist]
            [pseudovision.streaming.segment-store :as store]
            [pseudovision.streaming.source :as source]
            [pseudovision.util.time :as t]
            [taoensso.timbre :as log])
  (:import [java.io File]))

(def ^:private tick-ms 500)

;; Encoder restart backoff: an encoder that dies before running this long is
;; treated as an immediate failure (bad command, VAAPI init error, unreadable
;; source, …); consecutive immediate failures back off exponentially so a
;; broken config does not hot-loop and churn processes/metrics.
(def ^:private healthy-runtime-ms 5000)
(def ^:private base-backoff-ms 500)
(def ^:private max-backoff-ms 30000)

;; After this many consecutive immediate failures, assume the configured
;; hardware acceleration is unusable and degrade the channel to software
;; encoding so it keeps running. Sticky for the life of the loop — restart the
;; stream (or the pod) to re-enable hardware once the driver is fixed.
(def ^:private fallback-after-failures 3)

(defn make-manager
  "Constructs a manager value. `store` is a SegmentStore where served segments
   live; `scratch-dir` is where encoders write before ingest."
  [{:keys [db store scratch-dir]
    :or   {scratch-dir "/tmp/pseudovision/scratch"}}]
  {:db          db
   :store       store
   :scratch-base scratch-dir
   :registry    (atom {})})

;; ---------------------------------------------------------------------------
;; Encoder lifecycle (loop thread only)
;; ---------------------------------------------------------------------------

(defn- scratch-dir! ^String [scratch-base uuid enc-n]
  (let [d (io/file scratch-base (str uuid) (str "enc-" enc-n))]
    (.mkdirs d)
    (.getAbsolutePath d)))

(defn- delete-dir! [^String path]
  (let [d (io/file path)]
    (when (.exists d)
      (doseq [^File f (.listFiles d)] (.delete f))
      (.delete d))))

(defn- read-log-tail
  "Reads the tail of an encoder's ffmpeg.log (its redirected stderr), so a
   failed launch surfaces the real reason. Must be called BEFORE the scratch dir
   is deleted."
  [scratch]
  (let [f (io/file scratch "ffmpeg.log")]
    (when (.exists f)
      (let [s (slurp f)
            n 4000]
        (if (> (count s) n) (str "…" (subs s (- (count s) n))) s)))))

(defn- exit-code [encoder]
  (try (.exitValue ^Process (:process encoder)) (catch Exception _ nil)))

(defn- backoff-ms
  "Exponential backoff for `n` consecutive immediate failures, capped."
  [n]
  (if (pos? n)
    (min max-backoff-ms (* base-backoff-ms (bit-shift-left 1 (min 6 (dec n)))))
    0))

(defn- profile-config [db channel]
  (let [profile-id (:channels/ffmpeg-profile-id channel)]
    (or (when profile-id (:ffmpeg-profiles/config (db-ffmpeg/get-profile db profile-id))) {})))

(defn- build-command
  "Builds the FFmpeg command for `source-info`, writing into `scratch` in
   manager mode (encoder does not own the served playlist).

   When the source-info carries an :event with kind 'bumper', adds a drawtext
   overlay showing what's coming up next."
  [db channel source-info scratch cfg]
  (if (= (:type source-info) :generated-slate)
    (hls/build-slate-command scratch
                             {:channel-name    (:channels/name channel)
                              :channel-number  (:channels/number channel)
                              :upcoming-events (:upcoming-events source-info)
                              :profile-config  cfg
                              :manager-mode?   true})
    (let [event (:event source-info)
          is-bumper? (= "bumper" (some-> event :playout-events/kind str))
          ;; Build overlay text for bumpers
          overlay-text (when is-bumper?
                         (let [playout-id (some-> event :playout-events/playout-id)
                               next-events (when playout-id
                                            (db-playouts/get-upcoming-events-with-metadata
                                             db playout-id (t/now) 3))
                               next-title (some-> next-events first :metadata/title)
                               channel-name (:channels/name channel)]
                           (if next-title
                             (format "Coming up next: %s" next-title)
                             (format "You're watching %s" channel-name))))]
      (hls/build-hls-command (:source-url source-info) scratch
                             {:start-position-secs (or (:start-position source-info) 0)
                              :profile-config      cfg
                              :manager-mode?       true
                              :overlay-text        overlay-text
                              :audio-stream-index  (:audio-stream-index source-info)
                              :subtitle-burn-in    (:subtitle-burn-in source-info)}))))

(defn- open-media-view!
  "Records a media_item_views row for a real content encoder; nil for slates."
  [db channel source-info]
  (when (not= (:type source-info) :generated-slate)
    (let [mid (:media-item-id source-info)
          dur (try (db-metrics/get-media-item-duration-secs db mid) (catch Exception _ nil))]
      (:id (db-metrics/insert-media-item-view!
            db {:media-item-id       mid
                :channel-id          (:channels/id channel)
                :source-type         (:type source-info)
                :start-position-secs (or (:start-position source-info) 0)
                :total-duration-secs dur})))))

(defn- spawn-encoder!
  "Starts an FFmpeg encoder for `source-info` into a fresh scratch dir and opens
   its media-view record. Bumps :enc-counter. Returns the encoder map; does NOT
   touch the playlist — the caller decides current vs. next-encoder and whether a
   discontinuity is needed. Honours the sticky `:degraded?` software fallback."
  [{:keys [db scratch-base]} state source-info]
  (let [{:keys [channel uuid enc-counter degraded?]} @state
        scratch    (scratch-dir! scratch-base uuid enc-counter)
        cfg        (cond-> (profile-config db channel)
                     degraded? (assoc :accel "none"))
         command    (build-command db channel source-info scratch cfg)
        proc       (hls/start-ffmpeg command scratch)
        media-view (open-media-view! db channel source-info)]
    (swap! state update :enc-counter inc)
    (log/info "Spawned encoder"
              {:uuid uuid :enc enc-counter :pid (:pid proc)
               :type (:type source-info) :identity (source/source-identity source-info)})
    {:process       (:process proc)
     :pid           (:pid proc)
     :scratch-dir   scratch
     :source-info   source-info
     :media-view-id media-view
     :ingested      #{}
     :started-ms    (System/currentTimeMillis)}))

(defn- discard-encoder!
  "Closes the encoder's media-view, kills the process, and removes its scratch
   dir. Does NOT ingest — use for a next-encoder that never went live."
  [db enc]
  (when enc
    (try
      (when (:media-view-id enc)
        (source/end-view-records! db {:stream-started-ms (:started-ms enc)
                                      :media-view-id     (:media-view-id enc)}))
      (catch Exception e (log/warn e "closing media view failed")))
    (hls/stop-ffmpeg enc)
    (delete-dir! (:scratch-dir enc))))

(declare ingest-once!)

(defn- stop-encoder!
  "Drains the CURRENT encoder's final segments, then discards it. Leaves the
   channel-view open and the next-encoder (if any) untouched."
  [{:keys [db] :as mgr} state]
  (when-let [enc (:encoder @state)]
    (try (ingest-once! mgr state) (catch Exception e (log/warn e "final drain failed")))
    (discard-encoder! db enc)
    (swap! state assoc :encoder nil)))

;; ---------------------------------------------------------------------------
;; Ingest (loop thread only)
;; ---------------------------------------------------------------------------

(defn ingest-once!
  "Moves any newly-finalized segments from the current encoder's scratch
   playlist into the store and the channel playlist. Pure-ish: the only side
   effects are file moves/deletes through the store and a single `reset!`."
  [{:keys [store]} state]
  (let [{:keys [uuid encoder] :as s} @state]
    (when-let [scratch (:scratch-dir encoder)]
      (let [m3u8 (io/file scratch "playlist.m3u8")]
        (when (.exists m3u8)
          (let [parsed   (playlist/parse-media-playlist (slurp m3u8))
                ingested (:ingested encoder)
                fresh    (remove #(contains? ingested (:name %)) parsed)]
            (when (seq fresh)
              (loop [pl (:playlist s), ing ingested, segs fresh]
                (if-let [{:keys [name duration]} (first segs)]
                  (let [src    (io/file scratch name)
                        served (str "seg-" (:next-seq pl) ".ts")]
                    (if (.exists src)
                      (do
                        (store/put-segment! store uuid served src)
                        (let [[pl' evicted] (playlist/add-segment pl {:name served :duration duration})]
                          (doseq [e evicted] (store/delete-segment! store uuid (:name e)))
                          (recur pl' (conj ing name) (rest segs))))
                      (recur pl (conj ing name) (rest segs))))
                  (reset! state (-> @state
                                    (assoc :playlist pl)
                                    (assoc-in [:encoder :ingested] ing))))))))))))

;; ---------------------------------------------------------------------------
;; Per-channel loop
;; ---------------------------------------------------------------------------

(defn- start-current!
  "Spawns the current encoder for what should play now. Marks a discontinuity
   when the channel has already emitted segments (a cold start does not)."
  [mgr state]
  (let [si  (source/current-stream-source (:db mgr) (:channel @state))
        enc (spawn-encoder! mgr state si)]
    (swap! state #(cond-> (assoc % :encoder enc)
                    (pos? (:next-seq (:playlist %)))
                    (update :playlist playlist/mark-discontinuity)))))

(defn- preroll-next!
  "Spawns the NEXT encoder for the upcoming source (resolved at the lookahead
   horizon) WITHOUT stopping the current one, so it warms up before the boundary."
  [{:keys [db] :as mgr} state]
  (let [{:keys [channel uuid]} @state
        at  (t/add-duration (t/now) (t/seconds->duration source/transition-lookahead-secs))
        si  (source/current-stream-source db channel at)
        enc (spawn-encoder! mgr state si)]
    (log/info "Pre-rolling next encoder" {:uuid uuid :to (source/source-identity si)})
    (swap! state assoc :next-encoder enc)))

(defn- encoder-warm?
  "True once an encoder has finalized at least one segment in its scratch dir."
  [encoder]
  (let [m3u8 (io/file (:scratch-dir encoder) "playlist.m3u8")]
    (and (.exists m3u8)
         (boolean (seq (playlist/parse-media-playlist (slurp m3u8)))))))

(defn- promote-next!
  "Splices the pre-rolled next encoder in: drains + stops the current one, marks a
   discontinuity, and makes the next encoder current. Its first ingested segment
   then carries the #EXT-X-DISCONTINUITY tag. Because the current encoder keeps
   feeding the playlist until the next one is warm, there is no dead air."
  [mgr state]
  (let [nxt (:next-encoder @state)]
    (log/info "Splicing to pre-rolled encoder"
              {:uuid (:uuid @state) :to (source/source-identity (:source-info nxt))})
    (stop-encoder! mgr state)
    (swap! state #(-> %
                      (update :playlist playlist/mark-discontinuity)
                      (assoc :encoder nxt)
                      (assoc :next-encoder nil)
                      (assoc :fail-count 0)))))

(defn- note-failure!
  "Updates the consecutive-failure count and the sticky software-degrade flag for
   an encoder that died after `lifetime` ms. Returns the backoff to wait."
  [state uuid lifetime]
  (let [fails (if (< lifetime healthy-runtime-ms) (inc (:fail-count @state 0)) 0)]
    (swap! state assoc :fail-count fails)
    (when (and (>= fails fallback-after-failures) (not (:degraded? @state)))
      (log/warn "Repeated encoder failures; degrading channel to software encoding"
                {:uuid uuid :failures fails})
      (swap! state assoc :degraded? true))
    (backoff-ms fails)))

(defn- handle-dead-encoder!
  "The current encoder died: surface its stderr, drop any pre-rolled next, back
   off, degrade if warranted, then restart the current encoder."
  [{:keys [db] :as mgr} state]
  (let [{:keys [encoder next-encoder uuid]} @state
        lifetime (- (System/currentTimeMillis) (:started-ms encoder))]
    (log/error "Encoder died"
               {:uuid uuid :pid (:pid encoder) :exit-code (exit-code encoder)
                :lifetime-ms lifetime
                :ffmpeg-log (or (read-log-tail (:scratch-dir encoder)) "<no ffmpeg.log>")})
    (when next-encoder (discard-encoder! db next-encoder))
    (stop-encoder! mgr state)
    (swap! state assoc :next-encoder nil)
    (let [wait (note-failure! state uuid lifetime)]
      (when (pos? wait) (Thread/sleep wait)))
    (start-current! mgr state)))

(defn- handle-dead-next!
  "A pre-rolled next encoder died before warming: log and discard it, count the
   failure. The CURRENT encoder keeps playing — a failed transition never takes
   down the live stream."
  [{:keys [db] :as mgr} state]
  (let [{:keys [next-encoder uuid]} @state
        lifetime (- (System/currentTimeMillis) (:started-ms next-encoder))]
    (log/error "Pre-rolled encoder died before warming; current stream unaffected"
               {:uuid uuid :pid (:pid next-encoder) :exit-code (exit-code next-encoder)
                :lifetime-ms lifetime
                :ffmpeg-log (or (read-log-tail (:scratch-dir next-encoder)) "<no ffmpeg.log>")})
    (discard-encoder! db next-encoder)
    (swap! state assoc :next-encoder nil)
    (let [wait (note-failure! state uuid lifetime)]
      (when (pos? wait) (Thread/sleep wait)))))

(defn- maybe-transition!
  "Drives encoder lifecycle each tick: cold start, restart on death, pre-roll the
   next encoder before a boundary, and splice it in once it is warm — keeping the
   current encoder live throughout so the playlist never runs dry. Loop only."
  [{:keys [db] :as mgr} state]
  (let [{:keys [channel encoder next-encoder]} @state]
    (cond
      (nil? encoder)
      (start-current! mgr state)

      (not (hls/process-alive? encoder))
      (handle-dead-encoder! mgr state)

      next-encoder
      (cond
        (not (hls/process-alive? next-encoder)) (handle-dead-next! mgr state)
        (encoder-warm? next-encoder)            (promote-next! mgr state)
        :else                                   nil)   ; still warming — keep ingesting current

      (source/needs-transition? db channel (:source-info encoder) (:started-ms encoder))
      (preroll-next! mgr state))))

(defn tick!
  "One loop iteration: transition bookkeeping then ingest. Exposed for tests."
  [mgr state]
  (maybe-transition! mgr state)
  (ingest-once! mgr state))

(defn- run-loop [mgr state uuid stop?]
  (log/info "Stream loop started" {:uuid uuid})
  (try
    (while (not @stop?)
      (try (tick! mgr state)
           (catch Exception e (log/error e "stream loop tick failed" {:uuid uuid})))
      (Thread/sleep tick-ms))
    (catch InterruptedException _ nil))
  (log/info "Stream loop stopped" {:uuid uuid}))

;; ---------------------------------------------------------------------------
;; Public API (request threads)
;; ---------------------------------------------------------------------------

(defn ensure-stream!
  "Ensures a manager loop exists for `channel`, starting one (and recording a
   channel_views row) on first request. Returns the channel ref."
  [{:keys [db registry] :as mgr} channel]
  (let [uuid (str (:channels/uuid channel))]
    (locking registry
      (or (get @registry uuid)
          (let [channel-view-id (:id (db-metrics/insert-channel-view! db (:channels/id channel)))
                state (atom {:uuid uuid :channel channel
                             :playlist (playlist/new-playlist)
                             :encoder nil :enc-counter 0
                             :channel-view-id channel-view-id
                             :stream-started-ms (System/currentTimeMillis)})
                stop? (atom false)
                ref   {:state state :stop stop?
                       :last-access (atom (System/currentTimeMillis))
                       :future (future (run-loop mgr state uuid stop?))}]
            (swap! registry assoc uuid ref)
            ref)))))

(defn playlist-content
  "Renders the current served playlist for `uuid`, or nil if no segments are
   ready yet (encoder still warming). Bumps last-access."
  [{:keys [registry]} uuid]
  (when-let [ref (get @registry uuid)]
    (reset! (:last-access ref) (System/currentTimeMillis))
    (let [pl (:playlist @(:state ref))]
      (when (seq (:segments pl))
        (playlist/render pl #(str "/stream/" uuid "/" %))))))

(defn open-segment
  "Opens an InputStream over a served segment, or nil."
  [{:keys [store registry]} uuid seg-name]
  (when-let [ref (get @registry uuid)]
    (reset! (:last-access ref) (System/currentTimeMillis)))
  (store/open-segment store uuid seg-name))

(defn debug-info
  [{:keys [registry]} uuid]
  (when-let [ref (get @registry uuid)]
    (let [s @(:state ref)
          enc (:encoder s)]
      {:uuid uuid
       :enc-counter (:enc-counter s)
       :last-access @(:last-access ref)
       :fail-count (:fail-count s 0)
       :degraded? (boolean (:degraded? s))
       :process-alive (boolean (and enc (hls/process-alive? enc)))
       :encoder (some-> enc (dissoc :process))
       :next-encoder (some-> (:next-encoder s) (dissoc :process))
       :playlist (-> (:playlist s)
                     (select-keys [:next-seq :disc-seq :window-size])
                     (assoc :segment-count (count (:segments (:playlist s)))))})))

(defn stop-stream!
  "Stops a channel's loop and encoder, closes its channel-view, purges segments."
  [{:keys [db store registry] :as mgr} uuid]
  (when-let [ref (locking registry (let [r (get @registry uuid)]
                                     (swap! registry dissoc uuid) r))]
    (reset! (:stop ref) true)
    (try (when-let [nxt (:next-encoder @(:state ref))] (discard-encoder! db nxt))
         (catch Exception e (log/warn e "discard next-encoder failed")))
    (try (stop-encoder! mgr (:state ref)) (catch Exception e (log/warn e "stop-encoder failed")))
    (try
      (when-let [cvid (:channel-view-id @(:state ref))]
        (source/end-view-records! db {:stream-started-ms (:stream-started-ms @(:state ref))
                                      :channel-view-id   cvid}))
      (catch Exception e (log/warn e "closing channel view failed")))
    (store/purge-channel! store uuid)
    (log/info "Stopped stream" {:uuid uuid})))

(defn reap-idle!
  "Stops channels not accessed within `idle-ms`. Returns count stopped."
  [{:keys [registry] :as mgr} idle-ms]
  (let [now (System/currentTimeMillis)
        stale (for [[uuid ref] @registry
                    :when (> (- now @(:last-access ref)) idle-ms)]
                uuid)]
    (doseq [uuid stale] (stop-stream! mgr uuid))
    (count stale)))

(defn shutdown-all!
  "Stops every active stream (component teardown)."
  [{:keys [registry] :as mgr}]
  (doseq [uuid (keys @registry)] (stop-stream! mgr uuid)))
