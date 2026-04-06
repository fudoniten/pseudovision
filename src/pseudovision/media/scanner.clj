(ns pseudovision.media.scanner
  "Local filesystem media scanner.

   Walk → Identify → Probe (ffprobe) → Upsert

   For each library path:
     1. Walk the directory tree
     2. Match files against known video/audio/image extensions
     3. Run ffprobe to extract stream/chapter metadata
     4. Upsert media_items, media_versions, media_files, media_streams rows
     5. Mark items that no longer exist on disk as 'file_not_found'"
  (:require [clojure.java.io   :as io]
            [clojure.java.shell :as sh]
            [cheshire.core     :as json]
            [next.jdbc         :as jdbc]
            [pseudovision.db.media :as db]
            [pseudovision.util.sql :as sql-util]
            [taoensso.timbre   :as log])
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
    (HexFormat/of)))

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
                                  (java.time.Duration/ofSeconds))
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

(defn- upsert-batch!
  "Bulk-upserts a batch of media items in a single transaction.
   `batch` is a seq of {:file File :kind keyword :probe map-or-nil}.
   Inserts media_items for all entries, then media_versions and media_files
   only for entries where ffprobe succeeded."
  [db library-path batch]
  (jdbc/with-transaction [tx db]
    ;; 1. Bulk-insert media_items
    (let [item-rows (mapv (fn [{:keys [kind]}]
                            (let [item-kind (case kind
                                              :video :movie
                                              :audio :song
                                              :image :image
                                              :other-video)]
                              {:kind            (sql-util/->pg-enum "media_item_kind" (name item-kind))
                               :state           (sql-util/->pg-enum "media_item_state" "normal")
                               :library-path-id (:library-paths/id library-path)}))
                          batch)
          db-items  (jdbc/execute! tx
                                   (-> (honey.sql.helpers/insert-into :media-items)
                                       (honey.sql.helpers/values item-rows)
                                       honey.sql/format)
                                   {:return-keys true})
          ;; Keep only entries that have probe data, paired with their inserted item
          probed    (filterv (fn [[{:keys [probe]} _]] (some? probe))
                             (map vector batch db-items))]

      ;; 2. Bulk-insert media_versions for entries with probe data
      (when (seq probed)
        (let [ver-rows (mapv (fn [[{:keys [probe]} db-item]]
                               (assoc (probe->version probe)
                                      :media-item-id (:media-items/id db-item)))
                             probed)
              db-vers  (jdbc/execute! tx
                                      (-> (honey.sql.helpers/insert-into :media-versions)
                                          (honey.sql.helpers/values ver-rows)
                                          honey.sql/format)
                                      {:return-keys true})
              ;; 3. Bulk-insert media_files
              file-rows (mapv (fn [[{:keys [file]} _] db-ver]
                                (let [path (.getAbsolutePath ^File file)]
                                  {:media-version-id (:media-versions/id db-ver)
                                   :path             path
                                   :path-hash        (path-hash path)}))
                              probed db-vers)]
          (jdbc/execute! tx
                         (-> (honey.sql.helpers/insert-into :media-files)
                             (honey.sql.helpers/values file-rows)
                             (honey.sql.helpers/on-conflict :path-hash)
                             (honey.sql.helpers/do-nothing)
                             honey.sql/format)))))))

;; ---------------------------------------------------------------------------
;; Public: scan a library
;; ---------------------------------------------------------------------------

(defn scan-library!
  "Scans all paths in `library` and upserts discovered media items."
  [db media-config ffmpeg-config library]
  (let [paths (db/list-library-paths db (:libraries/id library))]
    (doseq [lp paths]
      (let [root (io/file (:library-paths/path lp))]
        (if-not (.isDirectory root)
          (log/warn "Library path is not a directory" {:path (.getPath root)})
          (do
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
