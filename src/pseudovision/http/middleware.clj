(ns pseudovision.http.middleware
  (:require [clojure.string    :as str]
            [cheshire.core     :as json]
            [cheshire.generate :as json-gen]
            [camel-snake-kebab.core :as csk]
            [taoensso.timbre   :as log])
  (:import [java.time Instant]
           [java.io InputStream]))

(json-gen/add-encoder Instant
                      (fn [inst jg] (.writeString jg (.toString inst))))

(defn wrap-json-body
  "Parses application/json request bodies into Clojure maps with kebab-case keys."
  [handler]
  (fn [req]
    (let [content-type (get-in req [:headers "content-type"])]
      (if (some-> content-type (str/starts-with? "application/json"))
        (let [body (-> req :body slurp (json/parse-string csk/->kebab-case-keyword))]
          (handler (assoc req :body-params body)))
        (do
          (log/debug "Skipping JSON body parsing"
                     {:content-type content-type
                      :headers (:headers req)})
          (handler req))))))

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

(defn wrap-request-logging
  "Logs each incoming request at debug level."
  [handler]
  (fn [req]
    (log/debug "→" (:request-method req) (:uri req))
    (let [resp (handler req)]
      (log/debug "←" (:status resp) (:uri req))
      resp)))

(def default-middleware
  "Ordered middleware stack applied to every route."
  [wrap-error-handler
   wrap-request-logging
   wrap-json-body
   wrap-json-response])
