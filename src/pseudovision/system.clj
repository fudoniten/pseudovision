(ns pseudovision.system
  (:require [integrant.core              :as ig]
            [pseudovision.config         :as config]
            [pseudovision.db.core        :as db]
            [pseudovision.http.core      :as http]
            [taoensso.timbre             :as log]))

;; Re-export so callers only need this namespace
(def ->system-config config/->system-config)

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
    (log/info "Running pending migrations…")
    (db/migrate! ds)
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
;; HTTP server
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :pseudovision/http
  [_ {:keys [port] :as opts}]
  (ig/ref :pseudovision/db)    ; ensure db is up before HTTP
  (let [server (http/start-server! opts)]
    (log/info "HTTP server listening on port" port)
    server))

(defmethod ig/halt-key! :pseudovision/http [_ server]
  (http/stop-server! server)
  (log/info "HTTP server stopped"))

;; ---------------------------------------------------------------------------
;; Integrant dependency declaration
;; ---------------------------------------------------------------------------

(defmethod ig/prep-key :pseudovision/http [_ opts]
  (assoc opts
         :db       (ig/ref :pseudovision/db)
         :ffmpeg   (ig/ref :pseudovision/ffmpeg)
         :media    (ig/ref :pseudovision/media)
         :scheduling (ig/ref :pseudovision/scheduling)))
