(ns pseudovision.media.scanner
  "Local filesystem media scanner.

   Walk → Identify → Probe (ffprobe) → Upsert

   For each library path:
     1. Walk the directory tree
     2. Match files against known video/audio/image extensions
     3. Run ffprobe to extract stream/chapter metadata
     4. Upsert media_items, media_versions, media_files, media_streams rows
     5. Mark items that no longer exist on disk as 'file_not_found'"
  (:require [clojure.java.io         :as io]
            [clojure.java.shell      :as sh]
            [cheshire.core           :as json]
            [next.jdbc               :as jdbc]
            [next.jdbc.result-set    :as rs]
            [pseudovision.db.media   :as db]
            [pseudovision.util.sql   :as sql-util]
            [taoensso.timbre         :as log])
  (:import [java.io File]
           [java.nio.file Files Path Paths]
           [java.security MessageDigest]
           [java.util HexFormat]))

;; ---------------------------------------------------------------------------
;; File helpers
;; ---------------------------------------------------------------------------

(defn- extension [^File f]
  (let [n (.getName f)]
    (when-let [dot (.lastIndexOf n ".")]
      (when (pos? dot)
        (.toLowerCase (subs n dot))))))

(defn- path-hash
  "SHA-256 of the absolute path string (hex)."
  [^String path]
  (let [md  (MessageDigest/getInstance "SHA-256")
        raw (.digest md (.getBytes path "UTF-8"))]
    (.formatHex (HexFormat/of) raw)))

(defn- filename-without-ext
  "Returns the filename of `f` with the extension stripped."
  [^File f]
  (let [n   (.getName f)
        dot (.lastIndexOf n ".")]
    (if (pos? dot) (subs n 0 dot) n)))

(defn- walk-directory
  "Returns a lazy seq of File objects for all regular files under `root`."
  [^File root]
  (->> (file-seq root)
       (filter #(.isFile ^File %))))

(defn- media-kind
  "Returns :video, :audio, or :image based on file extension, or nil."
  [^File f {:keys [video-extensions audio-extensions image-extensions]}]
  (let [ext (extension f)]
    (cond
      (some #{ext} video-extensions) :video
      (some #{ext} audio-extensions) :audio
      (some #{ext} image-extensions) :image
      :else nil)))

;; ---------------------------------------------------------------------------
;; ffprobe
;; ---------------------------------------------------------------------------

(defn- run-ffprobe
  "Runs ffprobe on `path` and returns the parsed JSON map, or nil on failure."
  [path {:keys [ffprobe-path probe-timeout-ms] :or {ffprobe-path "/usr/bin/ffprobe"
                                                    probe-timeout-ms 30000}}]
  (try
    (let [result (sh/sh ffprobe-path
                        "-v" "quiet"
                        "-print_format" "json"
                        "-show_format"
                        "-show_streams"
                        "-show_chapters"
                        path)]
      (when (zero? (:exit result))
        (json/parse-string (:out result) true)))
    (catch Exception e
      (log/warn e "ffprobe failed" {:path path})
      nil)))

(defn- probe->version
  "Converts a ffprobe result to a media_versions attribute map."
  [probe]
  (let [fmt     (:format probe)
        streams (:streams probe)
        video   (first (filter #(= "video" (:codec_type %)) streams))]
    {:duration            (some-> (:duration fmt)
                                  (Double/parseDouble)
                                  (long)
                                  (java.time.Duration/ofSeconds)
                                  sql-util/duration->pg-interval
                                  sql-util/->pg-interval)
     :width               (or (:width video) 0)
     :height              (or (:height video) 0)
     :r-frame-rate        (:r_frame_rate video)
     :video-scan-kind     "progressive"
     :display-aspect-ratio (:display_aspect_ratio video)}))

(defn- probe->streams
  "Converts ffprobe streams array to a seq of media_streams attribute maps."
  [probe]
  (map-indexed
   (fn [idx s]
     {:stream-index        idx
      :kind                (:codec_type s)
      :codec               (:codec_name s)
      :profile             (:profile s)
      :language            (get-in s [:tags :language])
      :channels            (or (:channels s) 0)
      :title               (get-in s [:tags :title])
      :is-default          (= 1 (:disposition.default s))
      :is-forced           (= 1 (:disposition.forced s))
      :pixel-format        (:pix_fmt s)})
   (:streams probe)))

;; ---------------------------------------------------------------------------
;; Batch upsert
;; ---------------------------------------------------------------------------

(def ^:private batch-size 100)

(defn- item-kind-for [kind]
  (case kind :video :movie :audio :song :image :image :movie))

(defn- upsert-batch!
  "Bulk-upserts a batch of media items in a single transaction.
   `batch` is a seq of {:file File :kind keyword :probe map-or-nil}.

   The absolute file path is stored as remote_key so that ON CONFLICT
   (library_path_id, remote_key) deduplicates properly on rescans.
   Metadata (title from filename) is always upserted for every item."
  [db library-path batch]
  (jdbc/with-transaction [tx db]
    (let [lp-id    (:library-paths/id library-path)
          ;; Enrich each entry with its absolute path and normalised item-kind
          enriched (mapv (fn [{:keys [file kind] :as entry}]
                           (assoc entry
                                  :abs-path  (.getAbsolutePath ^File file)
                                  :item-kind (item-kind-for kind)))
                         batch)]

      ;; 1. Bulk-upsert media_items.
      ;;    Uses abs-path as remote_key so rescans hit ON CONFLICT and update
      ;;    the existing row rather than inserting a duplicate.
      (let [item-rows (mapv (fn [{:keys [abs-path item-kind]}]
                              {:kind            (sql-util/->pg-enum "media_item_kind" (name item-kind))
                               :state           (sql-util/->pg-enum "media_item_state" "normal")
                               :library-path-id lp-id
                               :remote-key      abs-path})
                            enriched)
            db-items  (jdbc/execute! tx
                        (-> (honey.sql.helpers/insert-into :media-items)
                            (honey.sql.helpers/values item-rows)
                            (honey.sql.helpers/on-conflict :library-path-id :remote-key)
                            (honey.sql.helpers/do-update-set :state)
                            honey.sql/format)
                        {:return-keys true
                         :builder-fn  rs/as-unqualified-kebab-maps})
            ;; Re-associate each enriched entry with its (new or existing) item id
            enriched  (mapv (fn [entry item] (assoc entry :item-id (:id item)))
                            enriched db-items)]

        ;; 2. Bulk-upsert metadata: title from filename.
        ;;    ON CONFLICT updates the title so rescans keep it current.
        (let [meta-rows (mapv (fn [{:keys [file item-kind item-id]}]
                                {:media-item-id item-id
                                 :kind          (sql-util/->pg-enum "media_item_kind" (name item-kind))
                                 :title         (filename-without-ext file)})
                              enriched)]
          (jdbc/execute! tx
            (-> (honey.sql.helpers/insert-into :metadata)
                (honey.sql.helpers/values meta-rows)
                (honey.sql.helpers/on-conflict :media-item-id)
                (honey.sql.helpers/do-update-set :title)
                honey.sql/format)))

        ;; 3. Bulk-insert media_versions + media_files for probed entries.
        (let [probed (filterv #(some? (:probe %)) enriched)]
          (when (seq probed)
            (let [ver-rows  (mapv (fn [{:keys [probe item-id]}]
                                    (assoc (probe->version probe) :media-item-id item-id))
                                  probed)
                  db-vers   (jdbc/execute! tx
                              (-> (honey.sql.helpers/insert-into :media-versions)
                                  (honey.sql.helpers/values ver-rows)
                                  honey.sql/format)
                              {:return-keys true
                               :builder-fn  rs/as-unqualified-kebab-maps})
                  file-rows (mapv (fn [{:keys [abs-path]} db-ver]
                                    {:media-version-id (:id db-ver)
                                     :path             abs-path
                                     :path-hash        (path-hash abs-path)})
                                  probed db-vers)]
              (jdbc/execute! tx
                (-> (honey.sql.helpers/insert-into :media-files)
                    (honey.sql.helpers/values file-rows)
                    (honey.sql.helpers/on-conflict :path-hash)
                    (honey.sql.helpers/do-nothing)
                    honey.sql/format)))))))))

;; ---------------------------------------------------------------------------
;; Public: scan a library
;; ---------------------------------------------------------------------------

(defn scan-library!
  "Scans all paths in `library` and upserts discovered media items."
  [db media-config ffmpeg-config library]
  (let [paths (db/list-library-paths db (:libraries/id library))]
    (doseq [lp paths]
      (let [root  (io/file (:library-paths/path lp))
            lp-id (:library-paths/id lp)]
        (if-not (.isDirectory root)
          (log/warn "Library path is not a directory" {:path (.getPath root)})
          (do
            ;; Remove items created by older scanner versions that stored no
            ;; remote_key (the dedup handle).  Items still referenced by
            ;; playout_events or collection_items are left alone.
            (let [deleted (jdbc/execute-one! db
                            ["DELETE FROM media_items
                              WHERE library_path_id = ?
                                AND remote_key IS NULL
                                AND id NOT IN (SELECT media_item_id FROM playout_events)
                                AND id NOT IN (SELECT media_item_id FROM collection_items)"
                             lp-id])]
              (when (pos? (or (:next.jdbc/update-count deleted) 0))
                (log/info "Removed orphaned items with no remote_key"
                          {:library-path-id lp-id
                           :count           (:next.jdbc/update-count deleted)})))

            (log/info "Scanning" {:path (.getPath root)})
            (let [files       (walk-directory root)
                  media-files (keep (fn [f]
                                      (when-let [kind (media-kind f media-config)]
                                        {:file  f
                                         :kind  kind
                                         :probe (run-ffprobe (.getAbsolutePath f) ffmpeg-config)}))
                                    files)]
              (doseq [batch (partition-all batch-size media-files)]
                (try
                  (upsert-batch! db lp batch)
                  (catch Exception e
                    (log/error e "Failed to upsert batch"
                               {:paths (mapv #(.getAbsolutePath ^File (:file %)) batch)}))))
              (log/info "Scan complete" {:path  (.getPath root)
                                         :files (count files)}))))))))
