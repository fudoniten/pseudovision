(ns pseudovision.streaming.segment-store
  "Abstraction over where served HLS segments live and how they are read.

   Phase 1 ships a local-disk implementation. The protocol is the seam (see
   SEAMLESS_TRANSITIONS_PLAN.md §4.4) that lets a later phase move segments to
   shared storage (PVC / object store) for distribution WITHOUT touching the
   Channel Stream Manager or the HTTP handlers — both depend only on this
   protocol, never on `io/file` directly."
  (:require [clojure.java.io :as io]
            [taoensso.timbre :as log])
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption]))

(defprotocol SegmentStore
  (put-segment! [store channel-uuid seg-name src-file]
    "Moves the finalized segment file `src-file` into the store under
     channel-uuid/seg-name. Returns the stored path/identifier.")
  (open-segment [store channel-uuid seg-name]
    "Opens an InputStream over a stored segment, or nil if absent.")
  (segment-exists? [store channel-uuid seg-name])
  (delete-segment! [store channel-uuid seg-name]
    "Deletes a stored segment. No-op if absent.")
  (purge-channel! [store channel-uuid]
    "Deletes all stored segments (and the dir) for a channel."))

(defn- seg-file ^File [base-dir uuid seg-name]
  (io/file base-dir (str uuid) seg-name))

(defrecord LocalDiskSegmentStore [base-dir]
  SegmentStore
  (put-segment! [_ uuid seg-name src-file]
    (let [dest (seg-file base-dir uuid seg-name)
          src  (io/file src-file)]
      (io/make-parents dest)
      (try
        (Files/move (.toPath src) (.toPath dest)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
        (catch Exception _
          ;; Cross-device or atomic-move unsupported: fall back to copy+delete.
          (io/copy src dest)
          (.delete src)))
      (.getAbsolutePath dest)))
  (open-segment [_ uuid seg-name]
    (let [f (seg-file base-dir uuid seg-name)]
      (when (.exists f) (io/input-stream f))))
  (segment-exists? [_ uuid seg-name]
    (.exists (seg-file base-dir uuid seg-name)))
  (delete-segment! [_ uuid seg-name]
    (let [f (seg-file base-dir uuid seg-name)]
      (when (.exists f) (.delete f))))
  (purge-channel! [_ uuid]
    (let [dir (io/file base-dir (str uuid))]
      (when (.exists dir)
        (doseq [^File f (.listFiles dir)] (.delete f))
        (.delete dir)))))

(defn local-disk-store
  "Creates a local-disk SegmentStore rooted at `base-dir`."
  [base-dir]
  (.mkdirs (io/file base-dir))
  (log/debug "Local segment store rooted at" base-dir)
  (->LocalDiskSegmentStore base-dir))
