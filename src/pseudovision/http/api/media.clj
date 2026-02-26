(ns pseudovision.http.api.media
  (:require [pseudovision.db.media       :as db]
            [pseudovision.media.scanner  :as scanner]
            [pseudovision.media.jellyfin :as jellyfin]
            [taoensso.timbre             :as log]))

(defn list-sources-handler [{:keys [db]}]
  (fn [_req] {:status 200 :body (db/list-media-sources db)}))

(defn create-source-handler [{:keys [db]}]
  (fn [req]
    {:status 201 :body (db/create-media-source! db (:body-params req))}))

(defn list-libraries-handler [{:keys [db]}]
  (fn [req]
    (let [source-id (parse-long (get-in req [:path-params :id]))]
      {:status 200 :body (db/list-libraries-for-source db source-id)})))

(defn create-library-handler [{:keys [db]}]
  (fn [req]
    (let [source-id (parse-long (get-in req [:path-params :id]))
          attrs     (assoc (:body-params req) :media-source-id source-id)]
      {:status 201 :body (db/create-library! db attrs)})))

(defn trigger-scan-handler [{:keys [db media ffmpeg]}]
  (fn [req]
    (let [library-id (parse-long (get-in req [:path-params :id]))
          library    (db/get-library db library-id)]
      (if library
        (let [source (db/get-media-source db (:libraries/media_source_id library))
              kind   (keyword (:media_sources/kind source))]
          ;; Run the scan asynchronously so the HTTP request returns quickly.
          (future
            (try
              (case kind
                :jellyfin (jellyfin/scan-library! db source library)
                ;; Default to local filesystem scanner for :local and others
                (scanner/scan-library! db media ffmpeg library))
              (catch Exception e
                (log/error e "Library scan failed" {:library-id library-id
                                                    :kind       kind}))))
          {:status 202 :body {:message "Scan triggered"}})
        {:status 404 :body {:error "Library not found"}}))))

(defn list-collections-handler [{:keys [db]}]
  (fn [_req] {:status 200 :body (db/list-collections db)}))

(defn create-collection-handler [{:keys [db]}]
  (fn [req]
    {:status 201 :body (db/create-collection! db (:body-params req))}))
