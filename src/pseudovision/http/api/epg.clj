(ns pseudovision.http.api.epg
  "XMLTV EPG endpoint (/xmltv or /epg.xml).
   Returns an XMLTV-formatted XML document covering the next 7 days."
  (:require [pseudovision.db.playouts :as db]
            [pseudovision.util.time   :as t])
  (:import [java.time Instant]))

(defn- channel->xmltv [{:keys [channels/uuid channels/name channels/number
                                channels/group-name] :as ch}]
  (str "<channel id=\"" uuid "\">"
       "<display-name>" (clojure.string/replace name #"[<>&]" "") "</display-name>"
       "<lcn>" number "</lcn>"
       (when group-name
         (str "<group>" (clojure.string/replace group-name #"[<>&]" "") "</group>"))
       "</channel>"))

(defn- event->xmltv [{:keys [playout_events/start-at playout_events/finish-at
                               playout_events/custom-title channels/uuid
                               metadata/title metadata/plot]}]
  (let [display-title (or custom-title title "Unknown")]
    (str "<programme start=\"" (t/->xmltv-date start-at)
         "\" stop=\""          (t/->xmltv-date finish-at)
         "\" channel=\""       uuid "\">"
         "<title lang=\"en\">" (clojure.string/replace display-title #"[<>&]" "") "</title>"
         (when plot
           (str "<desc lang=\"en\">" (clojure.string/replace plot #"[<>&]" "") "</desc>"))
         "</programme>")))

(defn xmltv-handler [{:keys [db]}]
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
                     (clojure.string/join (map channel->xmltv channels))
                     (clojure.string/join (map event->xmltv   events))
                     "</tv>")})))
