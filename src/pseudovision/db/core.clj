(ns pseudovision.db.core
  (:require [migratus.core   :as migratus]
            [next.jdbc       :as jdbc]
            [next.jdbc.connection :as conn]
            [next.jdbc.result-set :as rs]
            [taoensso.timbre :as log]
            [aero.core       :as aero])
  (:import [com.zaxxer.hikari HikariDataSource]
           [org.postgresql.util PGInterval]
           [java.time Duration]))

;; ---------------------------------------------------------------------------
;; PostgreSQL type coercions
;; ---------------------------------------------------------------------------

(defn- pg-interval->duration [^PGInterval v]
  (let [total-seconds (+ (* (.getDays v) 86400)
                         (* (.getHours v) 3600)
                         (* (.getMinutes v) 60)
                         (.getSeconds v))]
    (Duration/ofMillis (long (* total-seconds 1000)))))

(extend-protocol rs/ReadableColumn
  PGInterval
  (read-column-by-label [v _] (pg-interval->duration v))
  (read-column-by-index [v _ _] (pg-interval->duration v))
  java.sql.Timestamp
  (read-column-by-label [v _] (.toInstant v))
  (read-column-by-index [v _ _] (.toInstant v)))

;; ---------------------------------------------------------------------------
;; Connection pool
;; ---------------------------------------------------------------------------

(defn make-datasource
  "Creates a HikariCP connection pool from a config map with keys
   :jdbc-url, :username, :password."
  [{:keys [jdbc-url username password]}]
  (log/info "Creating datasource with:"
            {:jdbc-url jdbc-url
             :username username
             :password-present? (boolean password)})
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
  (let [cfg  (aero/read-config (clojure.java.io/resource "migratus.edn"))
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
  (jdbc/execute! ds sql-map {:return-keys true
                             :builder-fn rs/as-unqualified-kebab-maps}))

(defn execute-one!
  "Like execute! but returns a single row."
  [ds sql-map]
  (jdbc/execute-one! ds sql-map {:return-keys true
                                 :builder-fn rs/as-unqualified-kebab-maps}))

(defn query
  "Runs a read query and returns all rows."
  [ds sql-map]
  (jdbc/execute! ds sql-map {:builder-fn rs/as-kebab-maps}))

(defn query-one
  "Returns a single row or nil."
  [ds sql-map]
  (jdbc/execute-one! ds sql-map {:builder-fn rs/as-kebab-maps}))
