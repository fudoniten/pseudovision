(ns pseudovision.cleanup
  "Background cleanup tasks for stream resources."
  (:require [pseudovision.http.api.streaming :as streaming]
            [taoensso.timbre :as log]
            [clojure.java.io :as io])
  (:import [java.util.concurrent Executors TimeUnit]
           [java.io File]))

(defn- cleanup-empty-stream-directories
  "Removes empty stream directories from /tmp/pseudovision/streams/.
   Returns count of directories removed."
  []
  (let [streams-dir (io/file "/tmp/pseudovision/streams")]
    (if (.exists streams-dir)
      (let [stream-dirs (.listFiles streams-dir)
            empty-dirs (filter (fn [^File dir]
                                (.isDirectory dir)
                                (empty? (.listFiles dir)))
                              stream-dirs)]
        (doseq [^File dir empty-dirs]
          (log/info "Removing empty stream directory" {:path (.getPath dir)})
          (.delete dir))
        (count empty-dirs))
      0)))

(defn- cleanup-old-stream-directories
  "Removes stream directories that haven't been accessed in over 1 hour.
   Returns count of directories removed."
  []
  (let [streams-dir (io/file "/tmp/pseudovision/streams")
        one-hour-ago (- (System/currentTimeMillis) (* 60 60 1000))]
    (if (.exists streams-dir)
      (let [stream-dirs (.listFiles streams-dir)
            old-dirs (filter (fn [^File dir]
                              (and (.isDirectory dir)
                                   (< (.lastModified dir) one-hour-ago)))
                            stream-dirs)]
        (doseq [^File dir old-dirs]
          (log/info "Removing old stream directory" 
                   {:path (.getPath dir) 
                    :last-modified (.lastModified dir)})
          ;; Delete files in directory first
          (doseq [^File file (.listFiles dir)]
            (.delete file))
          (.delete dir))
        (count old-dirs))
      0)))

(defn run-cleanup-cycle
  "Runs a single cleanup cycle: removes dead streams and cleans up temp directories.
   Returns map with cleanup statistics."
  []
  (try
    (let [dead-streams (streaming/cleanup-dead-streams)
          empty-dirs (cleanup-empty-stream-directories)
          old-dirs (cleanup-old-stream-directories)]
      (when (or (pos? dead-streams) (pos? empty-dirs) (pos? old-dirs))
        (log/info "Cleanup cycle completed" 
                 {:dead-streams dead-streams
                  :empty-directories empty-dirs
                  :old-directories old-dirs}))
      {:dead-streams dead-streams
       :empty-directories empty-dirs
       :old-directories old-dirs})
    (catch Exception e
      (log/error e "Error during cleanup cycle")
      {:error (.getMessage e)})))

(defn start-cleanup-daemon
  "Starts a background daemon that runs cleanup every 60 seconds.
   Returns a map with :executor that can be used to stop the daemon."
  []
  (let [executor (Executors/newSingleThreadScheduledExecutor)]
    (log/info "Starting cleanup daemon (runs every 60 seconds)")
    (.scheduleAtFixedRate executor
                         run-cleanup-cycle
                         60  ; initial delay (seconds)
                         60  ; period (seconds)
                         TimeUnit/SECONDS)
    {:executor executor}))

(defn stop-cleanup-daemon
  "Stops the cleanup daemon.
   daemon should be the map returned by start-cleanup-daemon."
  [{:keys [^java.util.concurrent.ScheduledExecutorService executor]}]
  (when executor
    (log/info "Stopping cleanup daemon")
    (.shutdown executor)
    (when-not (.awaitTermination executor 5 TimeUnit/SECONDS)
      (log/warn "Cleanup daemon did not terminate gracefully, forcing shutdown")
      (.shutdownNow executor))))
