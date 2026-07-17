(ns pseudovision.db.core
  (:require [migratus.core   :as migratus]
            [next.jdbc       :as jdbc]
            [next.jdbc.connection :as conn]
            [next.jdbc.result-set :as rs]
            [cheshire.core   :as json]
            [camel-snake-kebab.core :as csk]
            [taoensso.timbre :as log]
            [aero.core       :as aero])
  (:import [com.zaxxer.hikari HikariDataSource]
           [org.postgresql.util PGInterval PGobject]
           [java.sql ResultSet ResultSetMetaData Types]
           [java.time Duration]))

;; ---------------------------------------------------------------------------
;; Boolean column coercion
;;
;; PostgreSQL via the JDBC text protocol returns BOOLEAN columns through
;; `.getObject` as the literal strings "t" / "f" rather than as
;; `java.lang.Boolean`. The default `next.jdbc.result-set/as-kebab-maps`
;; builder hands those strings through unchanged, which then breaks Malli
;; response schemas that declare `[:is-enabled :boolean]` (the live symptom:
;; /api/channels and /api/channels/{id} returning HTTP 500 with
;; "Request coercion failed" for every channel row, verified 2026-07-17).
;;
;; `boolean-coerce-column-reader` is a column-reader suitable for
;; `next.jdbc.result-set/as-maps-adapter`: for BOOLEAN columns it reads
;; `.getBoolean` (canonical `Boolean`); NULLs stay `nil` via `.wasNull` so
;; nullable boolean columns keep their NULL distinction. All other column
;; types continue to use `.getObject`.
;; ---------------------------------------------------------------------------

(defn boolean-coerce-column-reader
  "Column-reader that reads BOOLEAN columns as `java.lang.Boolean` (instead
   of the \"t\"/\"f\" strings that PostgreSQL hands back via `.getObject` in
   text protocol mode) and lets NULLs stay `nil`. Non-boolean columns fall
   through to `.getObject` exactly as the default would.

   Use with `next.jdbc.result-set/as-maps-adapter` to wrap any map builder:
   (rs/as-maps-adapter rs/as-kebab-maps db/boolean-coerce-column-reader)"
  [^ResultSet rs ^ResultSetMetaData rsmeta ^Integer i]
  (if (= Types/BOOLEAN (.getColumnType rsmeta i))
    (let [v (.getBoolean rs i)]
      (if (.wasNull rs) nil v))
    (.getObject rs i)))

(def ^:private as-kebab-maps*
  "Builder-fn equivalent to `rs/as-kebab-maps` but with BOOLEAN columns
   coerced to real `Boolean` values via `boolean-coerce-column-reader`."
  (rs/as-maps-adapter rs/as-kebab-maps boolean-coerce-column-reader))

(def ^:private as-unqualified-kebab-maps*
  "Builder-fn equivalent to `rs/as-unqualified-kebab-maps` but with BOOLEAN
   columns coerced to real `Boolean` values."
  (rs/as-maps-adapter rs/as-unqualified-kebab-maps boolean-coerce-column-reader))

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
  (read-column-by-index [v _ _] (.toInstant v))
  java.sql.Array
  ;; SQL arrays (e.g. TEXT[] columns) arrive as PgArray, which is not seqable.
  ;; Decode to a Clojure vector so callers can treat them as ordinary seqs.
  (read-column-by-label [v _] (vec (.getArray v)))
  (read-column-by-index [v _ _] (vec (.getArray v)))
  PGobject
  (read-column-by-label [v _]
    (if (= "jsonb" (.getType v))
      (json/parse-string (.getValue v) csk/->kebab-case-keyword)
      (.getValue v)))
  (read-column-by-index [v _ _]
    (if (= "jsonb" (.getType v))
      (json/parse-string (.getValue v) csk/->kebab-case-keyword)
      (.getValue v))))

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
  (let [cfg     (migratus-config ds)
        pending (migratus/pending-list cfg)]
    (if (seq pending)
      (log/info "Pending migrations to apply:" (mapv :id pending))
      (log/info "No pending migrations"))
    (migratus/migrate cfg)
    (log/info "Migrations complete")))

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
  "Runs a HoneySQL map (already formatted) against the datasource.
   Uses `as-unqualified-kebab-maps*` so BOOLEAN columns come back as real
   `java.lang.Boolean` (not PG's text-protocol \"t\"/\"f\" strings). See the
   `boolean-coerce-column-reader` docstring for the why."
  [ds sql-map]
  (jdbc/execute! ds sql-map {:return-keys true
                             :builder-fn as-unqualified-kebab-maps*}))

(defn execute-one!
  "Like execute! but returns a single row."
  [ds sql-map]
  (jdbc/execute-one! ds sql-map {:return-keys true
                                 :builder-fn as-unqualified-kebab-maps*}))

(defn query
  "Runs a read query and returns all rows.
   Uses `as-kebab-maps*` so BOOLEAN columns come back as real
   `java.lang.Boolean` (not \"t\"/\"f\" strings)."
  [ds sql-map]
  (jdbc/execute! ds sql-map {:builder-fn as-kebab-maps*}))

(defn query-one
  "Returns a single row or nil.
   Uses `as-kebab-maps*` so BOOLEAN columns come back as real `Boolean`."
  [ds sql-map]
  (jdbc/execute-one! ds sql-map {:builder-fn as-kebab-maps*}))

(defn query-unqualified
  "Like `query` but returns rows with unqualified kebab-case keys.
   Useful for aggregate queries whose computed columns and aliases have no
   reliable table qualifier. Uses `as-unqualified-kebab-maps*` so BOOLEAN
   columns come back as real `Boolean`."
  [ds sql-map]
  (jdbc/execute! ds sql-map {:builder-fn as-unqualified-kebab-maps*}))

(defn query-one-unqualified
  "Like `query-one` but returns a row with unqualified kebab-case keys."
  [ds sql-map]
  (jdbc/execute-one! ds sql-map {:builder-fn rs/as-unqualified-kebab-maps}))
