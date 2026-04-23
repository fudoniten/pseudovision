(ns pseudovision.http.api.logos
  "Channel logo/artwork serving endpoint."
  (:require [pseudovision.db.channels :as db]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File ByteArrayInputStream]
           [java.util Base64]))

(defn- decode-base64
  "Decodes a base64 string to bytes."
  [^String b64-str]
  (.decode (Base64/getDecoder) b64-str))

(defn logos-handler
  "Serves channel artwork/logos by channel UUID.
   Supports both file paths and base64-encoded data URIs.
   Returns the first available artwork for the channel."
  [{:keys [db]}]
  (fn [req]
    (let [uuid (get-in req [:parameters :path :uuid])]
      (if-let [channel (db/get-channel-by-uuid db uuid)]
        (let [artwork (db/list-channel-artwork db (:channels/id channel))]
          (if (seq artwork)
              (let [logo (first artwork)
                    path (:channel-artwork/path logo)
                    content-type (or (:channel-artwork/original-content-type logo) "image/png")]
                
                ;; Check if path is a data URI (base64-encoded)
                (if (str/starts-with? path "data:")
                  ;; Parse data URI: data:image/png;base64,iVBORw0KG...
                  (let [[_ mime encoding data] (re-matches #"data:([^;,]+)(?:;([^,]+))?,(.+)" path)]
                    (if (and mime data)
                      (if (or (nil? encoding) (= encoding "base64"))
                        (let [decoded-bytes (decode-base64 data)]
                          {:status 200
                           :headers {"Content-Type" (or mime content-type)
                                    "Cache-Control" "public, max-age=86400"
                                    "Content-Length" (str (count decoded-bytes))}
                           :body (ByteArrayInputStream. decoded-bytes)})
                        {:status 500
                         :body {:error "Unsupported data URI encoding"
                                :encoding encoding}})
                      {:status 500
                       :body {:error "Invalid data URI format"
                              :path path}}))
                  
                  ;; Try to serve from file path (legacy/backward compatibility)
                  (let [file (io/file path)]
                    (if (.exists file)
                      {:status 200
                       :headers {"Content-Type" content-type
                                "Cache-Control" "public, max-age=86400"}
                       :body (io/input-stream file)}
                      {:status 404
                       :body {:error "Logo file not found on disk"
                              :path path}}))))
              {:status 404
               :body {:error "No artwork configured for this channel"}}))
          {:status 404
           :body {:error "Channel not found"}})))))
