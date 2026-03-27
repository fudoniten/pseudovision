(ns pseudovision.http.api.epg
  "XMLTV EPG endpoint (/xmltv or /epg.xml).
   Returns an XMLTV-formatted XML document covering the next 7 days."
  (:require [pseudovision.db.playouts :as db]
            [pseudovision.util.time   :as t]
            [clojure.string :as str])
  (:import [java.time Instant]))

(defn- channel->xmltv
  "Renders a channel map as an XMLTV <channel> element string."
  [{:keys [channels/uuid channels/name channels/number
           channels/group-name] :as ch}]
  (str "<channel id=\"" uuid "\">"
       "<display-name>" (str/replace name #"[<>&]" "") "</display-name>"
       "<lcn>" number "</lcn>"
       (when group-name
         (str "<group>" (str/replace group-name #"[<>&]" "") "</group>"))
       "</channel>"))

(defn- event->xmltv
  "Renders a playout event as an XMLTV <programme> element string."
  [{:keys [playout-events/start-at playout-events/finish-at
           playout-events/custom-title channels/uuid
           metadata/title metadata/plot]}]
  (let [display-title (or custom-title title "Unknown")]
    (str "<programme start=\"" (t/->xmltv-date start-at)
         "\" stop=\""          (t/->xmltv-date finish-at)
         "\" channel=\""       uuid "\">"
         "<title lang=\"en\">" (str/replace display-title #"[<>&]" "") "</title>"
         (when plot
           (str "<desc lang=\"en\">" (str/replace plot #"[<>&]" "") "</desc>"))
         "</programme>")))

(defn xmltv-handler
  "Returns a Ring handler that generates a full XMLTV document covering the next 7 days."
  [{:keys [db]}]
  (fn [_req]
    (let [now     (t/now)
          horizon (t/add-duration now (t/hours->duration (* 24 7)))
          events  (db/list-events-in-window db now horizon)

          ;; Deduplicate channels preserving insertion order
          channels (->> events
                        (map #(select-keys % [:channels/uuid :channels/name
                                              :channels/number :channels/group-name]))
                        (distinct))]
      {:status  200
       :headers {"Content-Type" "text/xml; charset=utf-8"}
       :body    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                     "<!DOCTYPE tv SYSTEM \"xmltv.dtd\">"
                     "<tv generator-info-name=\"pseudovision\">"
                     (str/join (map channel->xmltv channels))
                     (str/join (map event->xmltv   events))
                     "</tv>")})))
