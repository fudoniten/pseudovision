(ns pseudovision.http.api.epg
  "XMLTV EPG endpoint (/xmltv or /epg.xml).
   Returns an XMLTV-formatted XML document covering the next 7 days."
  (:require [pseudovision.db.playouts :as db]
            [pseudovision.db.channels :as db-channels]
            [pseudovision.util.time   :as t]
            [clojure.string :as str]
            [cheshire.core :as json])
  (:import [java.time Instant Year]))

(defn- escape-xml
  "Escapes special XML characters."
  [s]
  (when s
    (-> s
        (str/replace "&" "&amp;")
        (str/replace "<" "&lt;")
        (str/replace ">" "&gt;")
        (str/replace "\"" "&quot;"))))

(defn- channel->xmltv
  "Renders a channel map as an XMLTV <channel> element string."
  [base-url ds {:keys [channels/uuid channels/name channels/number
                       channels/group-name channels/id] :as ch}]
  (let [artwork (db-channels/list-channel-artwork ds id)]
    (str "<channel id=\"" uuid "\">"
         "<display-name>" (escape-xml name) "</display-name>"
         "<lcn>" number "</lcn>"
         (when group-name
           (str "<group>" (escape-xml group-name) "</group>"))
         ;; Add channel logo if artwork exists
         (when (seq artwork)
           (str "<icon src=\"" base-url "/logos/" uuid "\" />"))
         "</channel>")))

(defn- event->xmltv
  "Renders a playout event as an XMLTV <programme> element string."
  [{:keys [playout-events/start-at playout-events/finish-at
           playout-events/custom-title channels/uuid
           metadata/title metadata/plot metadata/episode-number 
           metadata/content-rating metadata/release-date]}]
  (let [display-title (or custom-title title "Unknown")
        ;; Extract year from release-date if present
        release-year (when release-date
                      (try
                        (.getValue (Year/from release-date))
                        (catch Exception _ nil)))]
    (str "<programme start=\"" (t/->xmltv-date start-at)
         "\" stop=\""          (t/->xmltv-date finish-at)
         "\" channel=\""       uuid "\">"
         "<title lang=\"en\">" (escape-xml display-title) "</title>"
         
         ;; Description/plot
         (when plot
           (str "<desc lang=\"en\">" (escape-xml plot) "</desc>"))
         
         ;; Episode numbering (XMLTV onscreen format: E05 without season)
         ;; Note: metadata table doesn't have season_number field
         (when episode-number
           (str "<episode-num system=\"onscreen\">E" 
                (format "%02d" episode-number) "</episode-num>"))
         
         ;; Categories/Genres - TODO: Query from metadata_genres table
         ;; (currently genres are in a separate table, not included in this query)
         
         ;; Content rating
         (when content-rating
           (str "<rating system=\"MPAA\"><value>" (escape-xml content-rating) "</value></rating>"))
         
         ;; Original air date (year only)
         (when release-year
           (str "<date>" release-year "</date>"))
         
         "</programme>")))

(defn xmltv-handler
  "Returns a Ring handler that generates a full XMLTV document covering the next 7 days."
  [{:keys [db]}]
  (fn [req]
    (let [now     (t/now)
          horizon (t/add-duration now (t/hours->duration (* 24 7)))
          events  (db/list-events-in-window db now horizon)
          base-url (str (name (:scheme req)) "://" (get-in req [:headers "host"]))

          ;; Deduplicate channels preserving insertion order
          channels (->> events
                        (map #(select-keys % [:channels/uuid :channels/name
                                              :channels/number :channels/group-name
                                              :channels/id]))
                        (distinct))]
      {:status  200
       :headers {"Content-Type" "text/xml; charset=utf-8"}
       :body    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                     "<!DOCTYPE tv SYSTEM \"xmltv.dtd\">"
                     "<tv generator-info-name=\"pseudovision\">"
                     (str/join (map #(channel->xmltv base-url db %) channels))
                     (str/join (map event->xmltv events))
                     "</tv>")})))
