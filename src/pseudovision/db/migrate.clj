(ns pseudovision.db.migrate
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [pseudovision.db.core :as db]
            [taoensso.timbre :as log])
  (:gen-class))

(defn -main [& args]
  (let [action (or (first args) "migrate")
        cfg    (aero/read-config (io/resource "config.edn"))
        db-cfg (get cfg :database)
        ds     (db/make-datasource db-cfg)]
    (try
      (case action
        "migrate"  (do (log/info "Running migrations…") (db/migrate! ds))
        "rollback" (do (log/info "Rolling back…") (db/rollback! ds))
        (do (log/error "Unknown action:" action) (System/exit 1)))
      (finally
        (db/close-datasource! ds)))))
