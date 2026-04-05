(ns pseudovision.db.migrate
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [pseudovision.db.core :as db]
            [taoensso.timbre :as log])
  (:gen-class))

(defn- parse-log-level
  "Convert log level from config to keyword if it's a string."
  [level]
  (cond
    (keyword? level) level
    (string? level)  (keyword level)
    :else            :info))

(defn -main [& args]
  (let [action    (or (first args) "migrate")
        cfg       (aero/read-config (io/resource "config.edn"))
        log-level (parse-log-level (get cfg :log-level :info))
        _         (log/set-level! log-level)
        _         (log/info "Loaded config from config.edn:" (pr-str cfg))
        db-cfg    (get cfg :database)
        _         (log/info "Database config (without password):"
                            {:jdbc-url (:jdbc-url db-cfg)
                             :username (:username db-cfg)})
        ds        (db/make-datasource db-cfg)]
    (try
      (case action
        "migrate"  (do (log/info "Running migrations…") (db/migrate! ds))
        "rollback" (do (log/info "Rolling back…") (db/rollback! ds))
        (do (log/error "Unknown action:" action) (System/exit 1)))
      (finally
        (db/close-datasource! ds)))))
