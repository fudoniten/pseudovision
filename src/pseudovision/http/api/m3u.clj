(ns pseudovision.http.api.m3u
  "M3U playlist and HDHomeRun device API."
  (:require [cheshire.core    :as json]
            [pseudovision.db.channels :as db]))

(defn- channel->m3u-entry
  "Produces one #EXTINF block for an M3U playlist."
  [base-url {:keys [channels/uuid channels/name channels/number
                     channels/group-name]}]
  (str "#EXTINF:-1"
       " tvg-id=\""   uuid   "\""
       " tvg-name=\"" name   "\""
       " tvg-chno=\"" number "\""
       (when group-name (str " group-title=\"" group-name "\""))
       "," name "\n"
       base-url "/stream/" uuid "\n"))

(defn m3u-handler [{:keys [db]}]
  (fn [req]
    (let [base-url (str (name (:scheme req)) "://" (get-in req [:headers "host"]))
          channels (db/list-channels db)]
      {:status  200
       :headers {"Content-Type"        "application/x-mpegURL"
                 "Content-Disposition" "attachment; filename=\"channels.m3u\""}
       :body    (str "#EXTM3U\n"
                     (clojure.string/join (map #(channel->m3u-entry base-url %) channels)))})))

;; ---------------------------------------------------------------------------
;; HDHomeRun device emulation (allows Plex/Emby/Jellyfin to auto-discover)
;; ---------------------------------------------------------------------------

(defn hdhr-device-handler [_ctx]
  (fn [req]
    (let [host (get-in req [:headers "host"])]
      {:status  200
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string
                 {:FriendlyName    "Pseudovision"
                  :Manufacturer    "Pseudovision"
                  :ModelNumber     "HDHR3-US"
                  :FirmwareName    "hdhomerun3_atsc"
                  :FirmwareVersion "20200101"
                  :DeviceID        "PSEUDOVISION"
                  :DeviceAuth      "pseudovision"
                  :BaseURL         (str "http://" host)
                  :LineupURL       (str "http://" host "/lineup.json")})})))

(defn hdhr-lineup-handler [{:keys [db]}]
  (fn [req]
    (let [host     (get-in req [:headers "host"])
          channels (db/list-channels db)]
      {:status  200
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string
                 (mapv (fn [{:keys [channels/uuid channels/name channels/number]}]
                         {:GuideName   name
                          :GuideNumber number
                          :Streaming   (str "http://" host "/stream/" uuid)
                          :HD          1})
                       channels))})))

(defn hdhr-status-handler [_ctx]
  (fn [_req]
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (json/generate-string
               {:ScanInProgress 0
                :ScanPossible   0
                :Source         "Cable"
                :SourceList     ["Cable"]})}))
