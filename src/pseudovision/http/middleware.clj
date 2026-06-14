(ns pseudovision.http.middleware
  (:require [cheshire.core     :as json]
            [cheshire.generate :as json-gen]
            [camel-snake-kebab.core :as csk]
            [muuntaja.core     :as m]
            [reitit.ring.middleware.exception :as reitit-exception]
            [taoensso.timbre   :as log])
  (:import [java.time Instant]
           [com.fasterxml.jackson.core JsonGenerator]))

(json-gen/add-encoder Instant
                      (fn [inst jg] (.writeString jg (.toString inst))))

;; ---------------------------------------------------------------------------
;; Muuntaja — JSON request decoding & content negotiation
;;
;; Configured to match the previous custom wrap-json-body behaviour:
;;   - request JSON bodies are decoded with kebab-case keyword keys
;;     (populated onto the request as :body-params)
;;   - response JSON bodies are encoded with kebab-case string keys, and
;;     java.time.Instant values render as ISO-8601 strings.
;;
;; Response encoding is still performed by `wrap-json-response` below so the
;; muuntaja instance only handles the request side of the content negotiation.
;; ---------------------------------------------------------------------------

(def muuntaja
  (m/create
   (-> m/default-options
       (assoc-in [:formats "application/json" :decoder-opts]
                 {:decode-key-fn csk/->kebab-case-keyword})
       (assoc-in [:formats "application/json" :encoder-opts]
                 {:encode-key-fn csk/->kebab-case-string
                  :encoders {Instant
                             (fn [^Instant v ^JsonGenerator gen]
                               (.writeString gen (.toString v)))}}))))

;; ---------------------------------------------------------------------------
;; Response encoding (legacy wrap-json-response retained)
;; ---------------------------------------------------------------------------

(defn wrap-json-response
  "Serialises Clojure collection response bodies (maps, vectors, lists) to JSON
   with kebab-case keys and sets Content-Type. String, nil, and InputStream bodies
   are passed through unchanged."
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (if (coll? (:body resp))
        (-> resp
            (update :body #(json/generate-string % {:key-fn csk/->kebab-case-string}))
            (assoc-in [:headers "Content-Type"] "application/json; charset=utf-8"))
        resp))))

;; ---------------------------------------------------------------------------
;; Error handling
;; ---------------------------------------------------------------------------

(defn wrap-error-handler
  "Returns a 500 JSON response for unhandled exceptions."
  [handler]
  (fn [req]
    (try
      (handler req)
      (catch Exception e
        (log/error e "Unhandled exception" {:uri (:uri req)})
        (log/error "  type:" (-> e .getClass .getName))
        (log/error "  msg:" (ex-message e))
        {:status  500
         :headers {"Content-Type" "application/json; charset=utf-8"}
         :body    (json/generate-string {:error "Internal server error"})}))))

(defn- coercion-error-response
  "Translates a reitit coercion failure into a structured 400 response."
  [status]
  (fn [exception _request]
    (let [data (ex-data exception)]
      {:status status
       :body   {:error     "Request coercion failed"
                :in        (:in data)
                :humanized (:humanized data)}})))

(defn- sql-state
  "Returns the SQLState string for `e` (or any cause in its chain) if it is a
   java.sql.SQLException, else nil."
  [^Throwable e]
  (loop [^Throwable t e]
    (cond
      (nil? t)                      nil
      (instance? java.sql.SQLException t) (.getSQLState ^java.sql.SQLException t)
      :else                         (recur (.getCause t)))))

(defn- unhandled-exception-response
  "Logs the full exception — including message and (for SQL errors) SQLState —
   and returns a structured 500. The reitit default handler only reports the
   exception class, which hides the actual cause (e.g. the Postgres error
   message), so we override it here."
  [^Throwable e _request]
  (log/error e "Unhandled exception in handler"
             {:class    (.getName (.getClass e))
              :message  (ex-message e)
              :sqlstate (sql-state e)})
  {:status 500
   :body   (cond-> {:error   "Internal server error"
                    :class   (.getName (.getClass e))
                    :message (ex-message e)}
             (sql-state e) (assoc :sqlstate (sql-state e)))})

(def exception-middleware
  "Reitit exception middleware that converts coercion failures into the
   application-standard {:error :in :humanized} envelope. Any other exception
   is logged in full (message + SQLState) and rendered as a structured 500 by
   `unhandled-exception-response`, overriding reitit's default handler which
   would otherwise report only the exception class."
  (reitit-exception/create-exception-middleware
   (merge
    reitit-exception/default-handlers
    {:reitit.coercion/request-coercion  (coercion-error-response 400)
     :reitit.coercion/response-coercion (coercion-error-response 500)
     ::reitit-exception/default         unhandled-exception-response})))

;; ---------------------------------------------------------------------------
;; Logging
;; ---------------------------------------------------------------------------

(defn wrap-request-logging
  "Logs each incoming request at debug level."
  [handler]
  (fn [req]
    (log/debug "→" (:request-method req) (:uri req))
    (let [resp (handler req)]
      (log/debug "←" (:status resp) (:uri req))
      resp)))
