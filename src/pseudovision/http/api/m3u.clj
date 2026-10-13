(ns pseudovision.http.api.m3u
  "M3U playlist API."
  (:require [clojure.string :as str]
            [pseudovision.db.channels :as db]))

(defn- channel->m3u-entry
  "Produces one #EXTINF block for an M3U playlist."
  [base-url ds {:keys [channels/uuid channels/name channels/number
                       channels/group-name channels/id]}]
  (let [artwork (db/list-channel-artwork ds id)
        has-logo? (seq artwork)]
    (str "#EXTINF:-1"
         " tvg-id=\""   uuid   "\""
         " tvg-name=\"" name   "\""
         " tvg-chno=\"" number "\""
         (when group-name (str " group-title=\"" group-name "\""))
         (when has-logo? (str " tvg-logo=\"" base-url "/logos/" uuid "\""))
         "," name "\n"
         base-url "/stream/" uuid "\n")))

(defn m3u-handler
  "Returns a Ring handler that generates an M3U playlist of all channels with stream URLs."
  [{:keys [db]}]
  (fn [req]
    (let [base-url (str (name (:scheme req)) "://" (get-in req [:headers "host"]))
          channels (db/list-channels db)]
      {:status  200
       :headers {"Content-Type"        "application/x-mpegURL"
                 "Content-Disposition" "attachment; filename=\"channels.m3u\""}
       :body    (str "#EXTM3U\n"
                     (str/join (map #(channel->m3u-entry base-url db %) channels)))})))
