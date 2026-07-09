(ns pseudovision.media.grout-source
  "Bridges Grout clips into Pseudovision's media model.

   Grout clips live on a filesystem shared with Pseudovision, so once a clip is
   ingested as an ordinary local-path `media_item` the existing scheduler
   (packer, enumerators, event emission) and the live streamer can reference it
   by `media_item_id` with no special-casing.

   Ingest is idempotent: each clip is keyed by `grout:<id>` in `remote_key`
   under a dedicated \"Grout\" local media source, so re-ingesting the same clip
   is a no-op that returns the existing item.  Grout clips are content-addressed
   and immutable, so an already-ingested clip is never re-probed or rewritten."
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string         :as str]
            [next.jdbc              :as jdbc]
            [honey.sql              :as sql]
            [honey.sql.helpers      :as h]
            [pseudovision.db.core   :as db]
            [pseudovision.media.grout :as grout]
            [pseudovision.util.sql  :as sql-util]
            [taoensso.timbre        :as log])
  (:import [java.security MessageDigest]
           [java.time Duration]
           [java.util HexFormat]))

(def ^:private source-name "Grout")
(def ^:private library-name "grout-filler")
(def ^:private default-media-dir "/data/media/grout")

;; ---------------------------------------------------------------------------
;; Shared Grout library (media source → library → library path)
;; ---------------------------------------------------------------------------

(defn- find-or-create!
  "Returns the id of an existing row matching `where`, creating it from `attrs`
   (with RETURNING) if absent. `id-key` is the qualified id keyword."
  [ds table id-key where attrs]
  (or (id-key (db/query-one ds (-> (h/select :id) (h/from table) (h/where where) sql/format)))
      (id-key (db/execute-one! ds (-> (h/insert-into table)
                                      (h/values [attrs])
                                      (h/returning :*)
                                      sql/format)))))

(defn ensure-library-path!
  "Ensures the singleton Grout media source / library / library-path exist,
   rooted at `media-dir`. Returns the library_path id used for all Grout items."
  [ds media-dir]
  (let [root       (or media-dir default-media-dir)
        source-id  (find-or-create! ds :media-sources :media-sources/id
                                     [:= :name source-name]
                                     {:name source-name
                                      :kind (sql-util/->pg-enum "media_source_kind" "local")})
        library-id (find-or-create! ds :libraries :libraries/id
                                     [:and [:= :media-source-id source-id]
                                           [:= :name library-name]]
                                     {:media-source-id source-id
                                      :name            library-name
                                      :kind            (sql-util/->pg-enum "library_kind" "other_videos")
                                      :should-sync     false})]
    (find-or-create! ds :library-paths :library-paths/id
                     [:and [:= :library-id library-id] [:= :path root]]
                     {:library-id library-id :path root})))

;; ---------------------------------------------------------------------------
;; Clip ingest
;; ---------------------------------------------------------------------------

(defn- path-hash
  "SHA-256 hex of a path string (matches the media scanner's dedup key)."
  [^String path]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256") (.getBytes path "UTF-8"))))

(defn- remote-key [clip] (str "grout:" (:id clip)))

(defn- existing-item
  "Returns {:media-items/id .. :media-versions/duration ..} for an
   already-ingested clip, or nil."
  [ds lib-path-id clip]
  (db/query-one ds (-> (h/select :mi.id [:mv.duration :duration])
                       (h/from [:media-items :mi])
                       (h/left-join [:media-versions :mv] [:= :mv.media-item-id :mi.id])
                       (h/where [:and
                                 [:= :mi.library-path-id lib-path-id]
                                 [:= :mi.remote-key (remote-key clip)]])
                       sql/format)))

(defn ingest-clip!
  "Idempotently materialises one Grout clip as a local-path media item under the
   Grout library path. Returns a candidate map shaped for the filler packer
   ({:media-items/id .. :media-versions/duration <Duration>}), or nil if the
   clip is unusable (no path or non-positive duration)."
  [ds lib-path-id clip]
  (let [path (:path clip)
        ms   (:duration-ms clip)]
    (cond
      (str/blank? path)
      (do (log/warn "Skipping Grout clip with no path" {:id (:id clip)}) nil)

      (or (nil? ms) (not (pos? ms)))
      (do (log/warn "Skipping Grout clip with non-positive duration"
                    {:id (:id clip) :duration-ms ms})
          nil)

      :else
      (if-let [found (existing-item ds lib-path-id clip)]
        {:media-items/id        (:media-items/id found)
         :media-versions/duration (or (:duration found) (Duration/ofMillis ms))}
        (jdbc/with-transaction [tx ds]
          (let [item    (db/execute-one! tx
                          (-> (h/insert-into :media-items)
                              (h/values [{:kind            (sql-util/->pg-enum "media_item_kind" "other_video")
                                          :state           (sql-util/->pg-enum "media_item_state" "normal")
                                          :library-path-id lib-path-id
                                          :remote-key      (remote-key clip)}])
                              (h/returning :*)
                              sql/format))
                ;; Both rows come from `db/execute-one!`, which uses
                ;; `rs/as-unqualified-kebab-maps` — so the returned keys are
                ;; `:id` and `:id` (not `:media-items/id` / `:media-versions/id`).
                ;; The previous qualified lookups silently returned nil and
                ;; the `media-versions` insert below violated the NOT NULL
                ;; constraint on `media_item_id`, rolling back the whole Grout
                ;; clip ingest. With these, both inserts succeed and Grout
                ;; clips can flow into `media_files` like Jellyfin items.
                item-id (:id item)
                ver     (db/execute-one! tx
                          (-> (h/insert-into :media-versions)
                              (h/values [{:media-item-id item-id
                                          :duration (-> (Duration/ofMillis ms)
                                                        sql-util/duration->pg-interval
                                                        sql-util/->pg-interval)
                                          :width  (or (:width clip) 0)
                                          :height (or (:height clip) 0)}])
                              (h/returning :*)
                              sql/format))]
            (db/execute-one! tx
              (-> (h/insert-into :media-files)
                  (h/values [{:media-version-id (:id ver)
                              :path             path
                              :path-hash        (path-hash path)}])
                  (h/on-conflict :path-hash)
                  (h/do-update-set :media-version-id :path)
                  sql/format))
            (log/info "Ingested Grout clip" {:grout-id (:id clip) :media-item-id item-id :path path})
            ;; Return the same shape callers expect — `item-id` is the local
            ;; unqualified id (the only kind this function actually has), and
            ;; `duration` is the resolved Java Duration.
            {:media-items/id item-id
             :media-versions/duration (Duration/ofMillis ms)}))))))

;; ---------------------------------------------------------------------------
;; Filler candidate loading (called by the scheduler)
;; ---------------------------------------------------------------------------

(defn- channel-tag
  "Derives Grout's `channel` tag from a Pseudovision channel's name
   (kebab-cased, e.g. \"Britannia\" -> \"britannia\"). Returns nil for a generic
   / channel-less fill so Grout returns null-channel (generic) filler."
  [channel]
  (let [nm (:channels/name channel)]
    (when-not (str/blank? nm) (csk/->kebab-case nm))))

(defn grout-filler-items
  "Queries Grout for filler candidates that fit the gap [from, to] on `channel`,
   ingests each as a local-path media item, and returns the candidate vector for
   the filler packer. Returns [] when Grout is disabled/unreachable or has no
   match — the caller then falls back to an empty fill (a gap), exactly as with
   an empty local collection.

   `grout` is the client, `preset` supplies :filler-presets/grout-tags, and the
   duration window is derived from the gap so oversized clips aren't fetched."
  [ds grout channel from to preset]
  (if-not (grout/enabled? grout)
    []
    (let [gap-ms (when (and from to) (max 0 (.toMillis (Duration/between from to))))
          clips  (grout/find-filler grout
                                    (cond-> {:tags   (:filler-presets/grout-tags preset)
                                             :random true}
                                      (channel-tag channel) (assoc :channel (channel-tag channel))
                                      (and gap-ms (pos? gap-ms)) (assoc :max-ms gap-ms)))]
      (if (empty? clips)
        (do (log/info "Grout returned no filler candidates"
                      {:channel (channel-tag channel)
                       :tags    (:filler-presets/grout-tags preset)
                       :gap-ms  gap-ms})
            [])
        (let [lib-path-id (ensure-library-path! ds (:media-dir grout))]
          (into [] (keep #(ingest-clip! ds lib-path-id %)) clips))))))
