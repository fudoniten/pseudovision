(ns pseudovision.http.middleware
  (:require [cheshire.core   :as json]
            [taoensso.timbre :as log]))

(defn wrap-json-body
  "Parses application/json request bodies into Clojure maps."
  [handler]
  (fn [req]
    (if (some-> (get-in req [:headers "content-type"])
                (clojure.string/starts-with? "application/json"))
      (let [body (-> req :body slurp (json/parse-string true))]
        (handler (assoc req :body-params body)))
      (handler req))))

(defn wrap-json-response
  "Serialises map response bodies to JSON and sets Content-Type."
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (if (map? (:body resp))
        (-> resp
            (update :body json/generate-string)
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
