(ns pseudovision.main
  (:require [aero.core          :as aero]
            [clojure.java.io    :as io]
            [clojure.tools.cli  :as cli]
            [integrant.core     :as ig]
            [pseudovision.system :as system]
            [taoensso.timbre    :as log])
  (:gen-class))

(def ^:private cli-options
  [["-c" "--config PATH"
    "Path to an EDN config file (may be repeated; files are deep-merged)"
    :multi    true
    :default  []
    :update-fn conj]
   ["-l" "--log-level LEVEL"
    "Log level: trace|debug|info|warn|error"
    :default  nil
    :parse-fn keyword]
   ["-h" "--help"]])

(defn- deep-merge [& maps]
  (apply merge-with
         (fn [l r] (if (map? l) (deep-merge l r) r))
         maps))

(defn- load-config [paths]
  (if (seq paths)
    (apply deep-merge (map #(aero/read-config (io/file %)) paths))
    (aero/read-config (io/resource "config.edn"))))

(defn- usage [summary]
  (println "Usage: pseudovision [options]\n")
  (println summary))

(defn -main [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (:help options)
      (do (usage summary) (System/exit 0))

      (seq errors)
      (do (doseq [e errors] (println e))
          (System/exit 1))

      :else
      (let [config     (cond-> (load-config (:config options))
                         (:log-level options)
                         (assoc :log-level (:log-level options)))
            sys-config (system/->system-config config)
            system     (ig/init (ig/prep sys-config))]
        (log/info "Pseudovision started on port" (get-in config [:server :port]))
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. ^Runnable #(do (log/info "Shutting down…")
                                                  (ig/halt! system))))
        ;; Block until JVM shutdown
        (.. Thread currentThread join)))))
