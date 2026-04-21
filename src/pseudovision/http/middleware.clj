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

(def exception-middleware
  "Reitit exception middleware that converts coercion failures into the
   application-standard {:error :in :humanized} envelope. Non-coercion
   exceptions re-raise to the outer wrap-error-handler."
  (reitit-exception/create-exception-middleware
   (merge
    reitit-exception/default-handlers
    {:reitit.coercion/request-coercion  (coercion-error-response 400)
     :reitit.coercion/response-coercion (coercion-error-response 500)})))

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
