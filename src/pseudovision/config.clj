(ns pseudovision.config)

;; Converts the flat application config map (loaded by aero) into the
;; map of Integrant keys consumed by system/->system-config.

(defn- parse-log-level
  "Convert log level to keyword if it's a string."
  [level]
  (cond
    (keyword? level) level
    (string? level)  (keyword level)
    :else            :info))

(defn ->system-config
  [{:keys [log-level server database ffmpeg media scheduling]}]
  {:pseudovision/logger    {:level     (parse-log-level (or log-level :info))}
   :pseudovision/db        {:jdbc-url  (:jdbc-url database)
                            :username  (:username database)
                            :password  (:password database)}
   :pseudovision/ffmpeg    {:ffmpeg-path  (:ffmpeg-path  ffmpeg)
                            :ffprobe-path (:ffprobe-path ffmpeg)}
   :pseudovision/media     (merge {:scan-concurrency 4
                                   :probe-timeout-ms 30000}
                                  media)
   :pseudovision/scheduling (merge {:lookahead-hours 72
                                    :rebuild-interval-minutes 60}
                                   scheduling)
   :pseudovision/http      {:port (or (some-> server :port (Integer/parseInt)) 8080)}})
