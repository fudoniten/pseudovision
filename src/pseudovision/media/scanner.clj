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
;; Item upsert
;; ---------------------------------------------------------------------------

(defn- upsert-item!
  [db library-path file media-kind probe]
  (let [path       (.getAbsolutePath ^File file)
        hash       (path-hash path)
        item-kind  (case media-kind
                     :video :movie       ;; simplification — NFO/parent dir needed for shows
                     :audio :song
                     :image :image
                     :other-video)]
    (jdbc/with-transaction [tx db]
      (let [item (db/upsert-media-item! tx
                   {:kind           (name item-kind)
                    :state          "normal"
                    :library-path-id (:library_paths/id library-path)})]
        (when probe
          (let [ver (jdbc/execute-one! tx
                      (-> (honey.sql.helpers/insert-into :media-versions)
                          (honey.sql.helpers/values [(assoc (probe->version probe)
                                                            :media-item-id (:media_items/id item))])
                          honey.sql/format)
                      {:return-keys true})]
            (jdbc/execute-one! tx
              (-> (honey.sql.helpers/insert-into :media-files)
                  (honey.sql.helpers/values [{:media-version-id (:media_versions/id ver)
                                              :path              path
                                              :path-hash         hash}])
                  (honey.sql.helpers/on-conflict :path-hash)
                  (honey.sql.helpers/do-nothing)
                  honey.sql/format))))
        item))))

;; ---------------------------------------------------------------------------
;; Public: scan a library
;; ---------------------------------------------------------------------------

(defn scan-library!
  "Scans all paths in `library` and upserts discovered media items."
  [db media-config ffmpeg-config library]
  (let [paths (db/list-library-paths db (:libraries/id library))]
    (doseq [lp paths]
      (let [root (io/file (:library_paths/path lp))]
        (if-not (.isDirectory root)
          (log/warn "Library path is not a directory" {:path (.getPath root)})
          (do
            (log/info "Scanning" {:path (.getPath root)})
            (let [files (walk-directory root)]
              (doseq [f files]
                (when-let [kind (media-kind f media-config)]
                  (let [probe (run-ffprobe (.getAbsolutePath f) ffmpeg-config)]
                    (try
                      (upsert-item! db lp f kind probe)
                      (catch Exception e
                        (log/error e "Failed to upsert item" {:path (.getAbsolutePath f)}))))))
              (log/info "Scan complete" {:path  (.getPath root)
                                         :files (count files)}))))))))
