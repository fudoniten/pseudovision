(ns pseudovision.system
  (:require [integrant.core              :as ig]
            [pseudovision.db.core        :as db]
            [pseudovision.http.core      :as http]
            [pseudovision.cleanup        :as cleanup]
            [taoensso.timbre             :as log]))

;; ---------------------------------------------------------------------------
;; Config → Integrant keys
;; ---------------------------------------------------------------------------

(defn- parse-log-level [level]
  (cond
    (keyword? level) level
    (string? level)  (keyword level)
    :else            :info))

(defn ->system-config
  [{:keys [log-level server database ffmpeg media scheduling]}]
  (letfn [(parse-int [i] (if (string? i) (Integer/parseInt i) i))]
    {:pseudovision/logger    {:level     (parse-log-level (or log-level :info))}
     :pseudovision/db        {:jdbc-url  (:jdbc-url database)
                              :username  (:username database)
                              :password  (:password database)}
     :pseudovision/ffmpeg    {:ffmpeg-path  (:ffmpeg-path  ffmpeg)
                              :ffprobe-path (:ffprobe-path ffmpeg)}
     :pseudovision/media     (merge {:scan-concurrency 4
                                     :probe-timeout-ms 30000}
                                    media)
     :pseudovision/scheduling (merge {:lookahead-hours 72
                                      :rebuild-interval-minutes 60}
                                     scheduling)
      :pseudovision/cleanup   {:db (ig/ref :pseudovision/db)}
      :pseudovision/http      {:port        (or (some-> server :port (parse-int)) 8080)
                               :db          (ig/ref :pseudovision/db)
                               :ffmpeg      (ig/ref :pseudovision/ffmpeg)
                               :media       (ig/ref :pseudovision/media)
                               :scheduling  (ig/ref :pseudovision/scheduling)}}))

;; ---------------------------------------------------------------------------
;; Logger
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :pseudovision/logger [_ {:keys [level]}]
  (log/set-min-level! level)
  (log/info "Logger initialised at level" level)
  level)

(defmethod ig/halt-key! :pseudovision/logger [_ _]
  (log/info "Logger shut down"))

;; ---------------------------------------------------------------------------
;; Database (connection pool + migrations)
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :pseudovision/db [_ opts]
  (let [ds (db/make-datasource opts)]
    (log/info "Database ready")
    ds))

(defmethod ig/halt-key! :pseudovision/db [_ ds]
  (db/close-datasource! ds)
  (log/info "Database connection closed"))

;; ---------------------------------------------------------------------------
;; FFmpeg config (just passes the map through; used by media scanner)
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :pseudovision/ffmpeg [_ opts] opts)
(defmethod ig/halt-key! :pseudovision/ffmpeg [_ _] nil)

;; ---------------------------------------------------------------------------
;; Media scanner config
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :pseudovision/media [_ opts] opts)
(defmethod ig/halt-key! :pseudovision/media [_ _] nil)

;; ---------------------------------------------------------------------------
;; Scheduling config
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :pseudovision/scheduling [_ opts] opts)

(defmethod ig/halt-key! :pseudovision/scheduling [_ _] nil)

;; ---------------------------------------------------------------------------
;; Cleanup daemon
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :pseudovision/cleanup [_ {:keys [db]}]
  (cleanup/start-cleanup-daemon db))

(defmethod ig/halt-key! :pseudovision/cleanup [_ daemon]
  (cleanup/stop-cleanup-daemon daemon))

;; ---------------------------------------------------------------------------
;; HTTP server
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :pseudovision/http
  [_ {:keys [port] :as opts}]
  (let [server (http/start-server! opts)]
    (log/info "HTTP server listening on port" port)
    server))

(defmethod ig/halt-key! :pseudovision/http [_ server]
  (http/stop-server! server)
  (log/info "HTTP server stopped"))


