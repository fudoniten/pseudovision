(ns pseudovision.streaming.source
  "Resolves what a channel should be playing right now, and the bookkeeping that
   goes with it (Jellyfin URL resolution, start-position, fallback slate, view
   metrics, and stable source identities used to detect transitions).

   Extracted from the HTTP streaming handler so both the Channel Stream Manager
   and the handlers can share it without a dependency cycle."
  (:require [pseudovision.db.playouts :as db-playouts]
            [pseudovision.db.media :as db-media]
            [pseudovision.db.metrics :as db-metrics]
            [pseudovision.media.connection :as conn]
            [pseudovision.util.time :as t]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Source resolution
;; ---------------------------------------------------------------------------

(defn get-jellyfin-stream-url
  "Resolves a Jellyfin direct-stream URL for a media item, or nil."
  [db media-item-id]
  (when-let [row (db-media/get-media-item-with-source db media-item-id)]
    (let [conn-config (:media-sources/connection-config row)
          base-url    (conn/active-uri (:connections conn-config))
          api-key     (:api-key conn-config)
          item-id     (or (:media-items/remote-key row) (:remote-key row))]
      (when (and base-url api-key item-id)
        (let [url (str base-url "/Videos/" item-id "/stream?static=true&api_key=" api-key)]
          (log/info "Resolved Jellyfin stream URL"
                    {:media-item-id media-item-id :remote-key item-id :base-url base-url})
          url)))))

(defn calculate-start-position
  "FFmpeg start position (seconds) for a playout event: time elapsed since the
   event started plus its in-point offset."
  [event now]
  (let [start-at      (:playout-events/start-at event)
        in-point      (:playout-events/in-point event)
        elapsed-secs  (t/duration->seconds (t/duration-between start-at now))
        in-point-secs (if in-point (t/duration->seconds in-point) 0)]
    (max 0 (+ elapsed-secs in-point-secs))))

(defn- format-time-12h [^java.time.Instant inst]
  (let [zdt (.atZone inst (java.time.ZoneId/of "America/Los_Angeles"))]
    (.format zdt (java.time.format.DateTimeFormatter/ofPattern "h:mm a"))))

(defn- truncate-title [title max-length]
  (if (> (count title) max-length)
    (str (subs title 0 (- max-length 3)) "...")
    title))

(defn- upcoming-events-for-slate
  [db playout-id]
  (when playout-id
    (let [events (db-playouts/get-upcoming-events-with-metadata db playout-id (t/now) 5)]
      (map (fn [event]
             {:title      (truncate-title (or (:metadata/title event) "Untitled") 50)
              :start-time (format-time-12h (:playout-events/start-at event))})
           events))))

(defn fallback-stream-source
  "Fallback when no current event is available: the channel's configured filler
   if resolvable, otherwise a generated slate carrying the upcoming-events list."
  [db channel playout-id]
  (if-let [filler-id (:channels/fallback-filler-id channel)]
    (if-let [url (get-jellyfin-stream-url db filler-id)]
      {:source-url url :start-position 0 :type :fallback-filler :media-item-id filler-id}
      (do (log/warn "Fallback filler unavailable - using generated slate"
                    {:channel-id (:channels/id channel) :filler-id filler-id})
          {:type :generated-slate :upcoming-events (upcoming-events-for-slate db playout-id)}))
    {:type :generated-slate :upcoming-events (upcoming-events-for-slate db playout-id)}))

(defn current-stream-source
  "Determines what should be streaming for a channel right now. Returns a
   source-info map (see fallback-stream-source / :current-event branch)."
  [db channel]
  (let [channel-id (:channels/id channel)
        playout    (db-playouts/get-playout-for-channel db channel-id)]
    (if-not playout
      (fallback-stream-source db channel nil)
      (let [now           (t/now)
            playout-id    (:playouts/id playout)
            current-event (db-playouts/get-current-event db playout-id now)]
        (if current-event
          (let [media-item-id (:playout-events/media-item-id current-event)
                source-url    (get-jellyfin-stream-url db media-item-id)]
            (if source-url
              {:source-url     source-url
               :start-position (calculate-start-position current-event now)
               :event          current-event
               :type           :current-event
               :media-item-id  media-item-id}
              (do (log/error "Failed to resolve stream URL for current event"
                             {:event-id (:playout-events/id current-event)
                              :media-item-id media-item-id})
                  (fallback-stream-source db channel playout-id))))
          (fallback-stream-source db channel playout-id))))))

;; ---------------------------------------------------------------------------
;; Source identity & transition detection
;; ---------------------------------------------------------------------------

(def transition-lookahead-secs
  "Look this many seconds ahead when deciding what should be playing, so the
   next encoder can be started before the boundary arrives."
  5)

(def slate-refresh-secs
  "Rebuild a running fallback slate at least this often so its baked-in
   'coming up next' list does not go stale during a long timeline gap."
  (* 5 60))

(defn slate-expired?
  "True when a fallback slate started `started-ms` ago is due for a refresh."
  [started-ms]
  (and started-ms
       (>= (- (System/currentTimeMillis) started-ms) (* slate-refresh-secs 1000))))

(defn source-identity
  "Stable identity of a source-info: [:event <id>] for real content, or
   :fallback for any slate/filler (which carry no :event)."
  [source-info]
  (if-let [event-id (get-in source-info [:event :playout-events/id])]
    [:event event-id]
    :fallback))

(defn desired-source-identity
  "Stable identity of what SHOULD be playing now (looking a few seconds ahead),
   without resolving any stream URLs — cheap enough to poll."
  [db channel]
  (let [playout (db-playouts/get-playout-for-channel db (:channels/id channel))]
    (if-not playout
      :fallback
      (let [at      (t/add-duration (t/now) (t/seconds->duration transition-lookahead-secs))
            current (db-playouts/get-current-event db (:playouts/id playout) at)]
        (if current
          [:event (:playout-events/id current)]
          :fallback)))))

(defn needs-transition?
  "True when what the channel SHOULD play differs from what `current-source-info`
   is playing, or when a long-running fallback slate is due for a refresh."
  [db channel current-source-info started-ms]
  (let [actual (source-identity current-source-info)]
    (or (not= actual (desired-source-identity db channel))
        (and (= actual :fallback) (slate-expired? started-ms)))))

;; ---------------------------------------------------------------------------
;; View metrics
;; ---------------------------------------------------------------------------

(defn end-view-records!
  "Closes any open channel/media view records for a stream. Safe on partial maps."
  [db {:keys [stream-started-ms channel-view-id media-view-id]}]
  (let [now          (t/now)
        elapsed-secs (if stream-started-ms
                       (/ (- (System/currentTimeMillis) stream-started-ms) 1000.0)
                       0.0)]
    (when channel-view-id (db-metrics/end-channel-view! db channel-view-id now))
    (when media-view-id   (db-metrics/end-media-item-view! db media-view-id now elapsed-secs))))
