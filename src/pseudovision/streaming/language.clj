(ns pseudovision.streaming.language
  "Resolves a channel's preferred audio/subtitle language against the actual
   media_streams of the item currently playing, with fallback when a
   preference isn't available:

     * audio:    preferred language -> the file's own default track -> its
                 first audio track (ffmpeg's usual default, made explicit).
     * subtitle: burned into the picture only when \"on by default\" is set,
                 OR as a safety net when the preferred audio language could
                 not be honoured -- so the viewer is never left without
                 either dialogue they understand or a way to follow along.
                 Tries the preferred subtitle language, then `en`; if neither
                 is present nothing is burned in."
  (:require [clojure.string :as str]))

(def default-subtitle-language "en")

;; ISO 639-2 has two 3-letter forms for ~20 languages: bibliographic (used by
;; ffprobe/Jellyfin, e.g. "fre", "ger", "chi") and terminological (what
;; java.util.Locale normalizes 2-letter codes to, e.g. "fra", "deu", "zho").
;; java.util.Locale leaves an already-3-letter bibliographic code unchanged,
;; so without this table a channel preference of "fr" ("fra") would never
;; match a French track tagged "fre".
(def ^:private iso639-bibliographic->terminological
  {"alb" "sqi" "arm" "hye" "baq" "eus" "bur" "mya" "chi" "zho"
   "cze" "ces" "dut" "nld" "fre" "fra" "geo" "kat" "ger" "deu"
   "gre" "ell" "ice" "isl" "mac" "mkd" "mao" "mri" "may" "msa"
   "per" "fas" "rum" "ron" "slo" "slk" "tib" "bod" "wel" "cym"})

(defn- normalize-lang
  "ISO-639 codes come from mixed sources (2-letter channel prefs, mostly
   3-letter track metadata from ffprobe/Jellyfin). Normalize to a 3-letter
   terminological code via java.util.Locale so \"en\" and \"eng\" compare
   equal; falls back to a plain lowercase compare for anything Locale
   doesn't recognize."
  [code]
  (when-not (str/blank? code)
    (let [lc   (str/lower-case (str/trim code))
          iso3 (try (str/lower-case (.getISO3Language (java.util.Locale. lc)))
                    (catch Exception _ lc))]
      (get iso639-bibliographic->terminological iso3 iso3))))

(defn- lang-matches? [track-lang pref-lang]
  (boolean
   (and track-lang pref-lang
        (= (normalize-lang track-lang) (normalize-lang pref-lang)))))

(defn- find-by-lang [streams kind lang]
  (when-not (str/blank? lang)
    (first (filter #(and (= kind (:kind %)) (lang-matches? (:language %) lang)) streams))))

(defn- find-default [streams kind]
  (first (filter #(and (= kind (:kind %)) (:is-default %)) streams)))

(defn- find-first [streams kind]
  (first (filter #(= kind (:kind %)) streams)))

(defn resolve-audio
  "Picks the audio stream to map for `preferred-audio-language` out of
   `streams` (media_streams rows for the item currently playing).

   Returns nil when no preference is set (let ffmpeg pick its own default).
   Otherwise always returns {:stream-index n, :matched-preference? bool} --
   the preferred language if present, else the source's own default track,
   else its first audio track."
  [streams preferred-audio-language]
  (when-not (str/blank? preferred-audio-language)
    (if-let [match (find-by-lang streams "audio" preferred-audio-language)]
      {:stream-index (:stream-index match) :matched-preference? true}
      (when-let [fallback (or (find-default streams "audio") (find-first streams "audio"))]
        {:stream-index (:stream-index fallback) :matched-preference? false}))))

(defn resolve-subtitle-burn-in
  "Decides whether to burn subtitles into the picture, and which track.

   opts:
     :preferred-subtitle-language - channel preference, defaults to 'en'
     :subtitle-default-enabled?   - burn in whenever the language is present
     :audio-matched-preference?   - false forces subtitles on as a fallback,
                                     even when subtitle-default-enabled? is
                                     false (nil/true counts as matched --
                                     nothing to fall back from)
     :subtitle-enabled?           - channel-level on/off switch (subtitle_mode)

   Returns the matching media_streams row, or nil (no burn-in: disabled, or
   neither the preferred nor the 'en' fallback subtitle track exists)."
  [streams {:keys [preferred-subtitle-language subtitle-default-enabled?
                   audio-matched-preference? subtitle-enabled?]}]
  (when (and subtitle-enabled?
             (or subtitle-default-enabled? (false? audio-matched-preference?)))
    (or (find-by-lang streams "subtitle" preferred-subtitle-language)
        (find-by-lang streams "subtitle" default-subtitle-language))))
