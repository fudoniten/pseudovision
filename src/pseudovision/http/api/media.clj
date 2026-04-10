(ns pseudovision.http.api.media
  (:require [clojure.string                 :as str]
            [pseudovision.db.media          :as db]
            [pseudovision.media.scanner     :as scanner]
            [pseudovision.media.jellyfin    :as jellyfin]
            [pseudovision.media.connection  :as conn]
            [taoensso.timbre                :as log]))

;; ---------------------------------------------------------------------------
;; Playback URL helpers
;; ---------------------------------------------------------------------------

(defmulti ^:private build-stream-url
  "Builds a direct-play stream URL for a media item given its source kind.
   Dispatches on the source kind string."
  (fn [_item _conn-config kind] kind))

(defmethod build-stream-url "jellyfin" [item conn-config _kind]
  (let [base-url (conn/active-uri (:connections conn-config))
        api-key  (:api-key conn-config)
        item-id  (or (:media-items/remote-key item) (:remote-key item))]
    (when (and base-url api-key item-id)
      (str base-url "/Videos/" item-id "/stream?static=true&api_key=" api-key))))

(defmethod build-stream-url :default [_item _conn-config kind]
  (log/warn "Playback URL not supported for source kind" {:kind kind})
  nil)

(defn list-sources-handler [{:keys [db]}]
  (fn [_req] {:status 200 :body (db/list-media-sources db)}))

(defn create-source-handler [{:keys [db]}]
  (fn [req]
    (let [params (:body-params req)
          _      (log/info "create-source-handler received params" {:params params})
          config (conn/->connection-config params)
          _      (log/info "connection config" {:config config})
          attrs  (-> (select-keys params [:name :kind :path-replacements])
                     (cond-> config (assoc :connection-config config)))]
      (log/info "final attrs" {:attrs attrs})
      {:status 201 :body (db/create-media-source! db attrs)})))

(defn delete-source-handler [{:keys [db]}]
  (fn [req]
    (let [source-id (parse-long (get-in req [:path-params :id]))]
      (db/delete-media-source! db source-id)
      {:status 204})))

(defn list-all-libraries-handler [{:keys [db]}]
  (fn [_req] {:status 200 :body (db/list-libraries db)}))

(defn list-libraries-handler [{:keys [db]}]
  (fn [req]
    (let [source-id (parse-long (get-in req [:path-params :id]))]
      {:status 200 :body (db/list-libraries-for-source db source-id)})))

(defn create-library-handler [{:keys [db]}]
  (fn [req]
    (let [source-id (parse-long (get-in req [:path-params :id]))
          params    (:body-params req)
          attrs     (assoc params :media-source-id source-id)]
      (if (and (:name params) (:kind params))
        {:status 201 :body (db/create-library! db attrs)}
        {:status 400 :body {:error "Missing required fields: name and kind"}}))))

(defn- parse-attrs
  "Splits a comma-separated attrs string into a seq of trimmed strings, or
   returns nil if the input is blank/nil."
  [s]
  (when (not (str/blank? s))
    (->> (str/split s #",")
         (map str/trim)
         (remove str/blank?)
         seq)))

(defn list-library-items-handler [{:keys [db]}]
  (fn [req]
    (let [library-id (parse-long (get-in req [:path-params :id]))
          qp         (:query-params req)
          attrs      (parse-attrs (get qp "attrs"))
          item-type  (not-empty (get qp "type"))
          parent-str (get qp "parent-id")
          opts       (cond-> {}
                       attrs                    (assoc :attrs attrs)
                       item-type                (assoc :type item-type)
                       (contains? qp "parent-id") (assoc :parent-id (some-> parent-str parse-long)))]
      {:status 200 :body (db/list-media-items db library-id opts)})))

(defn trigger-scan-handler [{:keys [db media ffmpeg]}]
  (fn [req]
    (let [library-id (parse-long (get-in req [:path-params :id]))
          library    (db/get-library db library-id)]
      (if library
        (let [source (db/get-media-source db (:libraries/media-source-id library))
              kind   (keyword (:media-sources/kind source))]
          (log/info "Triggering library scan" {:library-id library-id
                                               :library-name (:libraries/name library)
                                               :kind kind})
          ;; Run the scan asynchronously so the HTTP request returns quickly.
          (future
            (try
              (log/info "Starting library scan" {:library-id library-id :kind kind})
              (case kind
                :jellyfin (jellyfin/scan-library! db source library)
                ;; Default to local filesystem scanner for :local and others
                (scanner/scan-library! db media ffmpeg library))
              (log/info "Library scan completed" {:library-id library-id :kind kind})
              (catch Exception e
                (log/error e "Library scan failed" {:library-id library-id
                                                    :kind       kind}))))
          {:status 202 :body {:message "Scan triggered"}})
        {:status 404 :body {:error "Library not found"}}))))

(defn discover-libraries-handler [{:keys [db]}]
  (fn [req]
    (let [source-id (parse-long (get-in req [:path-params :id]))
          source    (db/get-media-source db source-id)]
      (if (nil? source)
        {:status 404 :body {:error "Media source not found"}}
        (let [kind (keyword (str (:media-sources/kind source)))]
          (if (not= kind :jellyfin)
            {:status 400 :body {:error "Library discovery is only supported for Jellyfin sources"}}
            (let [candidates   (jellyfin/discover-libraries! source)
                  existing     (db/list-libraries-for-source db source-id)
                  existing-ids (set (keep :libraries/external_id existing))
                  new-libs     (remove #(existing-ids (:external-id %)) candidates)
                  created      (mapv #(db/create-library! db (assoc % :media-source-id source-id))
                                     new-libs)]
              {:status 201
               :body   {:discovered (count candidates)
                        :created    (count created)
                        :libraries  (into existing created)}})))))))

(defn list-collections-handler [{:keys [db]}]
  (fn [_req] {:status 200 :body (db/list-collections db)}))

(defn create-collection-handler [{:keys [db]}]
  (fn [req]
    {:status 201 :body (db/create-collection! db (:body-params req))}))

;; ---------------------------------------------------------------------------
;; Single item + playback URL
;; ---------------------------------------------------------------------------

(defn get-media-item-handler [{:keys [db]}]
  (fn [req]
    (let [item-id (parse-long (get-in req [:path-params :id]))
          item    (db/get-media-item db item-id)]
      (if item
        {:status 200 :body item}
        {:status 404 :body {:error "Media item not found"}}))))

(defn get-item-stream-url-handler [{:keys [db]}]
  (fn [req]
    (let [item-id (parse-long (get-in req [:path-params :id]))
          row     (db/get-media-item-with-source db item-id)]
      (if (nil? row)
        {:status 404 :body {:error "Media item not found"}}
        (let [kind        (or (some-> row :media-sources/kind str) "")
              conn-config (or (:media-sources/connection-config row)
                              (:connection-config row))
              url         (build-stream-url row conn-config kind)]
          (if url
            {:status 200 :body {:url url :kind kind}}
            {:status 422 :body {:error (str "Playback URL not supported for source kind: " kind)}}))))))

