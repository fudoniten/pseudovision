(ns pseudovision.streaming.playlist
  "Pure model of a live HLS media playlist owned by the Channel Stream Manager.

   FFmpeg no longer owns `playlist.m3u8`. Each per-event encoder writes plain
   numbered segments into a scratch dir; the manager ingests them into this
   model, which maintains a MONOTONIC `#EXT-X-MEDIA-SEQUENCE` and a correct
   `#EXT-X-DISCONTINUITY-SEQUENCE` across encoder restarts, so clients never see
   the playlist reset at an event boundary (the failure mode of the old
   one-ffmpeg-per-event design).

   All functions here are pure; the manager holds a value of this in its
   per-channel state. See SEAMLESS_TRANSITIONS_PLAN.md §4.1 and §9."
  (:require [clojure.string :as str])
  (:import [java.util Locale]))

(def default-window-size
  "Number of segments kept in the live sliding window (mirrors hls_list_size)."
  10)

(def default-target-duration 2)

(defn new-playlist
  "Creates an empty playlist value. Options: :window-size, :target-duration."
  ([] (new-playlist {}))
  ([{:keys [window-size target-duration]
     :or   {window-size      default-window-size
            target-duration  default-target-duration}}]
   {:segments               []     ; oldest-first: {:seq :name :duration :discontinuity?}
    :next-seq               0      ; global media-sequence number for the next segment
    :disc-seq               0      ; #EXT-X-DISCONTINUITY-SEQUENCE: evicted discontinuities
    :pending-discontinuity? false
    :window-size            window-size
    :min-target-duration    target-duration}))

(defn mark-discontinuity
  "Flags the NEXT ingested segment as the first of a new encoder output, so a
   `#EXT-X-DISCONTINUITY` tag precedes it. Idempotent until the next add."
  [pl]
  (assoc pl :pending-discontinuity? true))

(defn add-segment
  "Adds a finalized segment `{:name :duration}` to the window. Returns
   `[playlist' evicted]`, where `evicted` is the vector of segment entries that
   rolled out of the window and whose files the caller should delete from the
   SegmentStore.

   The added segment's global `:seq` is the playlist's current `:next-seq`, so
   the caller can name the stored file deterministically from `:next-seq` before
   calling this. The `:disc-seq` counter is bumped whenever an evicted segment
   was itself a discontinuity boundary — the single most important correctness
   rule for keeping pickier players (MAG/Enigma2) in sync (§9)."
  [pl {:keys [name duration]}]
  (let [seg {:seq            (:next-seq pl)
             :name           name
             :duration       (double (or duration 0.0))
             :discontinuity? (boolean (:pending-discontinuity? pl))}
        pl' (-> pl
                (update :segments conj seg)
                (update :next-seq inc)
                (assoc  :pending-discontinuity? false))]
    (loop [p pl' evicted []]
      (if (> (count (:segments p)) (:window-size p))
        (let [e (first (:segments p))]
          (recur (-> p
                     (update :segments subvec 1)
                     (update :disc-seq #(cond-> % (:discontinuity? e) inc)))
                 (conj evicted e)))
        [p evicted]))))

(defn- media-sequence
  "Media sequence number of the first segment currently in the window."
  [pl]
  (if-let [s (first (:segments pl))]
    (:seq s)
    (:next-seq pl)))

(defn- target-duration
  [pl]
  (let [durs (map :duration (:segments pl))]
    (max (:min-target-duration pl)
         (long (Math/ceil (double (apply max 1.0 durs)))))))

(defn render
  "Renders the playlist to an M3U8 string. `seg-url` maps a stored segment's
   `:name` to the URL the client should request."
  [pl seg-url]
  (let [header ["#EXTM3U"
                "#EXT-X-VERSION:3"
                (str "#EXT-X-TARGETDURATION:" (target-duration pl))
                (str "#EXT-X-MEDIA-SEQUENCE:" (media-sequence pl))
                (str "#EXT-X-DISCONTINUITY-SEQUENCE:" (:disc-seq pl))]
        body   (mapcat (fn [{:keys [name duration discontinuity?]}]
                         (cond-> []
                           discontinuity? (conj "#EXT-X-DISCONTINUITY")
                           true (conj (String/format Locale/US "#EXTINF:%.3f,"
                                                     (object-array [(double duration)])))
                           true (conj (seg-url name))))
                       (:segments pl))]
    (str (str/join "\n" (concat header body)) "\n")))

(defn parse-media-playlist
  "Parses an FFmpeg-written HLS media playlist into an ordered vector of
   `{:name :duration}` for each segment listed. A segment's `:name` is the URI
   line that follows its `#EXTINF`. Tolerant of the tag subset FFmpeg emits."
  [content]
  (when content
    (loop [lines (str/split-lines content)
           dur   nil
           acc   []]
      (if-let [raw (first lines)]
        (let [line (str/trim raw)]
          (cond
            (str/starts-with? line "#EXTINF:")
            (recur (rest lines)
                   (some-> (re-find #"#EXTINF:([0-9.]+)" line) second Double/parseDouble)
                   acc)

            (or (str/blank? line) (str/starts-with? line "#"))
            (recur (rest lines) dur acc)

            :else
            (recur (rest lines) nil (conj acc {:name line :duration (or dur 0.0)}))))
        acc))))
