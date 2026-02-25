(ns pseudovision.db.core
  (:require [migratus.core   :as migratus]
            [next.jdbc       :as jdbc]
            [next.jdbc.connection :as conn]
            [taoensso.timbre :as log])
  (:import [com.zaxxer.hikari HikariDataSource]))

;; ---------------------------------------------------------------------------
;; Connection pool
;; ---------------------------------------------------------------------------

(defn make-datasource
  "Creates a HikariCP connection pool from a config map with keys
   :jdbc-url, :username, :password."
  [{:keys [jdbc-url username password]}]
  (conn/->pool HikariDataSource
               {:jdbcUrl  jdbc-url
                :username username
                :password password
                :maximumPoolSize 10}))

(defn close-datasource! [^HikariDataSource ds]
  (.close ds))

;; ---------------------------------------------------------------------------
;; Migrations
;; ---------------------------------------------------------------------------

(defn- migratus-config [ds]
  {:store     :database
   :migration-dir "migrations"
   :db        {:datasource ds}})

(defn migrate! [ds]
  (migratus/migrate (migratus-config ds)))

(defn rollback! [ds]
  (migratus/rollback (migratus-config ds)))

;; Exec-fn entry point for `clojure -X:migrate`
(defn run-migrations! [{:keys [action] :or {action "migrate"}}]
  (let [cfg  (aero.core/read-config (clojure.java.io/resource "migratus.edn"))
        ds   (make-datasource {:jdbc-url  (get-in cfg [:db :jdbcUrl])
                               :username  nil
                               :password  nil})]
    (case action
      "migrate"  (migrate!  ds)
      "rollback" (rollback! ds)
      (log/error "Unknown action:" action))
    (close-datasource! ds)))

;; ---------------------------------------------------------------------------
;; Query helpers
;; ---------------------------------------------------------------------------

(defn execute!
  "Runs a HoneySQL map (already formatted) against the datasource."
  [ds sql-map]
  (jdbc/execute! ds sql-map {:return-keys true}))

(defn execute-one!
  "Like execute! but returns a single row."
  [ds sql-map]
  (jdbc/execute-one! ds sql-map {:return-keys true}))

(defn query
  "Runs a read query and returns all rows."
  [ds sql-map]
  (jdbc/execute! ds sql-map))

(defn query-one
  "Returns a single row or nil."
  [ds sql-map]
  (jdbc/execute-one! ds sql-map))
