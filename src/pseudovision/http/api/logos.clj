(ns pseudovision.http.api.logos
  "Channel logo/artwork serving endpoint."
  (:require [pseudovision.db.channels :as db]
            [clojure.java.io :as io])
  (:import [java.io File]))

(defn logos-handler
  "Serves channel artwork/logos by channel UUID.
   Returns the first available artwork for the channel."
  [{:keys [db]}]
  (fn [req]
    (let [uuid-str (get-in req [:path-params :uuid])
          uuid (try (java.util.UUID/fromString uuid-str)
                   (catch Exception _ nil))]
      (if-not uuid
        {:status 400
         :body {:error "Invalid UUID"}}
        
        (if-let [channel (db/get-channel-by-uuid db uuid)]
          (let [artwork (db/list-channel-artwork db (:channels/id channel))]
            (if (seq artwork)
              (let [logo (first artwork)
                    path (:channel-artwork/path logo)
                    file (io/file path)]
                (if (.exists file)
                  {:status 200
                   :headers {"Content-Type" (or (:channel-artwork/original-content-type logo)
                                               "image/png")
                            "Cache-Control" "public, max-age=86400"}  ; Cache for 24 hours
                   :body (io/input-stream file)}
                  {:status 404
                   :body {:error "Logo file not found on disk"
                          :path path}}))
              {:status 404
               :body {:error "No artwork configured for this channel"}}))
          {:status 404
           :body {:error "Channel not found"}})))))
