(ns pseudovision.media.grout
  "HTTP client for Grout, a single-purpose filler service.

   Grout stores tagged clips on a filesystem shared with Pseudovision and makes
   them searchable by channel, tag, and duration.  Pseudovision uses the read
   path (`GET /grout/media`) to find filler that fits a gap, then streams the
   returned absolute `path` directly off the shared mount (the HTTP `stream-url`
   is only a fallback for non-co-mounted callers).

   Conventions (see GROUT.md):
     * query-string params are snake_case (`min_ms`, `max_ms`)
     * response bodies are kebab-case (`duration-ms`, `stream-url`, `path`)

   Every call degrades gracefully: on a network error, non-2xx, or when Grout is
   disabled, the query fns return an empty result and log a warning rather than
   throwing.  Filler is best-effort — a Grout outage must never break a playout
   build or take down a live stream."
  (:require [clj-http.client :as http]
            [clojure.string  :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

(def ^:private default-timeout-ms 5000)
(def ^:private default-limit 50)

(defn client
  "Builds a Grout client value from a config map. Keys:
     :base-url    Grout base URL, e.g. \"http://grout:8080\" (required to enable)
     :enabled?    explicit on/off switch (default: true when base-url is set)
     :timeout-ms  socket/connection timeout (default 5000)
     :media-dir   GROUT_MEDIA_DIR on the shared mount (used by the intake side)
   Returns nil when no base-url is configured, which callers treat as disabled."
  [{:keys [base-url enabled? timeout-ms media-dir] :as _config}]
  (when-not (str/blank? base-url)
    {:base-url   (str/replace base-url #"/+$" "")   ; trim trailing slash
     :enabled?   (if (some? enabled?) (boolean enabled?) true)
     :timeout-ms (or timeout-ms default-timeout-ms)
     :media-dir  media-dir}))

(defn enabled?
  "True when `grout` is a configured, enabled client."
  [grout]
  (boolean (and grout (:enabled? grout) (:base-url grout))))

;; ---------------------------------------------------------------------------
;; Low-level request
;; ---------------------------------------------------------------------------

(defn- grout-get
  "Performs an authenticated-less GET against Grout. Returns the parsed JSON
   body (kebab-case keyword keys) on 2xx, or nil on any failure."
  [grout path & {:keys [query-params]}]
  (let [url (str (:base-url grout) path)]
    (try
      (let [resp (http/get url
                           {:query-params       query-params
                            :as                 :json
                            :throw-exceptions   false
                            :socket-timeout     (:timeout-ms grout)
                            :connection-timeout (:timeout-ms grout)})]
        (if (<= 200 (:status resp) 299)
          (:body resp)
          (do (log/warn "Grout request returned non-2xx"
                        {:url url :status (:status resp)})
              nil)))
      (catch Exception e
        (log/warn e "Grout request failed" {:url url})
        nil))))

;; ---------------------------------------------------------------------------
;; Query (the read path)
;; ---------------------------------------------------------------------------

(defn- ->query-params
  "Maps a kebab-case opts map to Grout's snake_case query params, dropping nils.
   `tags` may be a seq or a comma-separated string (AND semantics)."
  [{:keys [channel tags min-ms max-ms kind random limit]}]
  (let [tags-str (cond
                   (nil? tags)        nil
                   (string? tags)     (when-not (str/blank? tags) tags)
                   (coll? tags)       (when (seq tags) (str/join "," (map name tags)))
                   :else              nil)]
    (cond-> {}
      (not (str/blank? channel)) (assoc "channel" channel)
      tags-str                   (assoc "tags"    tags-str)
      min-ms                     (assoc "min_ms"  (long min-ms))
      max-ms                     (assoc "max_ms"  (long max-ms))
      kind                       (assoc "kind"    (name kind))
      (some? random)             (assoc "random"  (boolean random))
      limit                      (assoc "limit"   (long limit)))))

(defn query
  "Queries `GET /grout/media`. `opts` is a kebab-case map:
     :channel  match this channel OR generic (null-channel) items
     :tags     seq/comma-string; ALL required (AND)
     :min-ms   inclusive minimum duration
     :max-ms   inclusive maximum duration
     :kind     bumper|filler|program
     :random   shuffle results
     :limit    max items (Grout default 10)
   Returns the raw response map {:count N :items [...]} or nil when disabled/failed."
  [grout opts]
  (when (enabled? grout)
    (grout-get grout "/grout/media" :query-params (->query-params opts))))

(defn find-filler
  "Convenience over `query` for gap-filling: returns just the `:items` vector
   (kebab-case keys: :id :name :duration-ms :path :stream-url :vcodec :acodec
   :tags), or [] when Grout is disabled, unreachable, or has no match."
  [grout opts]
  (vec (:items (query grout (merge {:limit default-limit} opts)))))

;; ---------------------------------------------------------------------------
;; Point lookups (foundation for the write/dedup path)
;; ---------------------------------------------------------------------------

(defn by-hash
  "Looks up a clip by SHA-256 of its source bytes. Returns the Media map or nil."
  [grout hash]
  (when (and (enabled? grout) (not (str/blank? hash)))
    (grout-get grout (str "/grout/by-hash/" hash))))

(defn get-media
  "Fetches the full Media object for `id`, or nil."
  [grout id]
  (when (and (enabled? grout) id)
    (grout-get grout (str "/grout/media/" id))))

(defn healthy?
  "True when Grout's `/health` endpoint responds 2xx."
  [grout]
  (boolean (and (enabled? grout) (grout-get grout "/health"))))
