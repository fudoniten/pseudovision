(ns pseudovision.jobs.runner
  "In-memory asynchronous job runner.

   Jobs are long-running background tasks (e.g. rebuilding a channel's playout
   timeline) that would otherwise block an HTTP request for minutes. Submitting
   a job returns immediately with a job record; callers poll
   `GET /api/jobs/:job-id` for status, progress, and the final result.

   The public job shape and status vocabulary are kept deliberately compatible
   with the Tunarr Scheduler service (`fudoniten/tunarr-scheduler`) so a single
   UI (Marquee) can render jobs from either backend identically:

     {:id           \"<uuid>\"
      :type         :playout/rebuild        ; namespaced keyword
      :status       :queued|:running|:succeeded|:failed
      :metadata     {...}                   ; optional, caller-supplied
      :progress     {...}|<number>          ; optional, task-reported
      :created-at   \"<iso-8601>\"
      :started-at   \"<iso-8601>\"          ; once running
      :completed-at \"<iso-8601>\"          ; once finished
      :duration-ms  <int>                   ; once running
      :result       <any>                   ; on :succeeded
      :error        {:message \"...\" :type \"...\"}} ; on :failed"
  (:require [taoensso.timbre :as log]
            [clojure.stacktrace :refer [print-stack-trace]])
  (:import (java.time Duration Instant)
           (java.util UUID)))

(defprotocol IJobRunner
  (add-job! [self job-id job])
  (job-info [self job-id]
    "Public (wire-shaped) view of a single job, or nil if unknown.")
  (get-job [self job-id]
    "Raw internal job record, or nil if unknown.")
  (list-jobs [self]
    "Public views of all tracked jobs, newest-first.")
  (submit! [self config task-fn]
    "Submit a job. Returns the initial public job view. See `submit-job!`.")
  (close [self]
    "Drop all tracked jobs."))

(defn now [] (Instant/now))

(defn- format-ts [^Instant inst]
  (when inst (.toString inst)))

(defn- duration-ms
  "Elapsed runtime: started-at → completed-at for finished jobs, started-at →
   now for jobs still running."
  [{:keys [started-at completed-at]}]
  (when started-at
    (.toMillis (Duration/between ^Instant started-at
                                 ^Instant (or completed-at (now))))))

(defn- ->public-job
  "Project an internal job record onto the stable wire shape, omitting keys
   that are not yet meaningful (e.g. :result before completion)."
  [job]
  (when job
    (let [{:keys [id type status created-at started-at completed-at
                  metadata result error progress]} job]
      (cond-> {:id         id
               :type       type
               :status     status
               :created-at (format-ts created-at)}
        metadata                                 (assoc :metadata metadata)
        (some? progress)                         (assoc :progress progress)
        started-at                               (assoc :started-at  (format-ts started-at)
                                                        :duration-ms (duration-ms job))
        completed-at                             (assoc :completed-at (format-ts completed-at))
        (contains? #{:succeeded :failed} status) (assoc :result result)
        (= :failed status)                       (assoc :error error)))))

(defmulti ^:private update-job! (fn [o & _] (class o)))

(defn- normalize-config
  "Accept either a bare keyword job type or a map with a :type keyword."
  [config]
  (let [config (if (keyword? config) {:type config} config)]
    (when-not (and (map? config) (keyword? (:type config)))
      (throw (ex-info "Job config must be a map or keyword with a keyword :type"
                      {:config config})))
    config))

;; ---------------------------------------------------------------------------
;; Standard progress shape
;;
;; Jobs that process a known set of items report :progress as a map:
;;
;;   {:phase "rebuilding" :total 340 :skipped 88 :completed 12 :failed 1
;;    :current-item {:id "..." :name "..."}}
;;
;; The report-progress callback handed to each task accepts either a map
;; (which replaces the job's :progress wholesale) or a function of the current
;; progress map (applied atomically, for concurrent counter updates). The
;; helpers below produce atomic updates against the standard shape.
;; ---------------------------------------------------------------------------

(defn start-items!
  "Initialize item-based progress tracking for a job phase."
  [report-progress phase total skipped]
  (report-progress {:phase phase :total total :skipped skipped :completed 0 :failed 0}))

(defn item-started!
  "Record the item currently being processed."
  [report-progress {:keys [id name]}]
  (report-progress #(assoc % :current-item {:id id :name name})))

(defn item-completed!
  "Atomically count one item as completed."
  [report-progress]
  (report-progress #(-> % (update :completed (fnil inc 0)) (dissoc :current-item))))

(defn item-failed!
  "Atomically count one item as failed."
  [report-progress]
  (report-progress #(-> % (update :failed (fnil inc 0)) (dissoc :current-item))))

(defn submit-job!
  "Submit an asynchronous job. Returns the initial public job view immediately;
   the task runs on a background thread.

   `config` is either a bare keyword job type or a map with a :type keyword and
   optional :metadata (stored alongside the job record).

   `task-fn` is called with a single argument, a `report-progress` function.
   Calling it with a map replaces the job's :progress; calling it with a
   function updates :progress atomically. Whatever `task-fn` returns becomes the
   job's :result on success."
  [runner config task-fn]
  (let [{:keys [type metadata]} (normalize-config config)
        job-id  (str (UUID/randomUUID))
        new-job {:id         job-id
                 :type       type
                 :status     :queued
                 :metadata   metadata
                 :created-at (now)}
        report-progress (fn [progress]
                          (update-job! runner job-id
                                       (fn [job]
                                         (assoc job :progress
                                                (if (fn? progress)
                                                  (progress (or (:progress job) {}))
                                                  progress)))))]
    (log/info "creating job" {:job-id job-id :type type})
    (add-job! runner job-id new-job)
    (future
      (try
        (update-job! runner job-id merge {:status :running :started-at (now)})
        (log/info "job running" {:job-id job-id :type type})
        (let [result (task-fn report-progress)]
          (update-job! runner job-id merge {:status       :succeeded
                                            :completed-at (now)
                                            :result       result})
          (log/info "job succeeded" {:job-id job-id :type type}))
        (catch Throwable t
          (log/error t "job failed" {:job-id job-id :type type})
          (log/info (with-out-str (print-stack-trace t)))
          (update-job! runner job-id merge {:status       :failed
                                            :completed-at (now)
                                            :error        {:message (.getMessage t)
                                                           :type    (.getName (class t))}}))))
    (->public-job new-job)))

(def ^:private max-tracked-jobs
  "Retain only the most recent jobs to bound memory; older records are evicted."
  100)

(defrecord JobRunner [jobs]
  IJobRunner
  (add-job! [_ job-id job]
    (swap! jobs (fn [m]
                  (let [m' (assoc m job-id job)]
                    (if (> (count m') max-tracked-jobs)
                      (into {} (take-last max-tracked-jobs
                                          (sort-by (comp :created-at val) m')))
                      m')))))
  (get-job [_ job-id]
    (get @jobs job-id))
  (job-info [self job-id]
    (->public-job (get-job self job-id)))
  (list-jobs [_]
    (->> @jobs
         vals
         (sort-by :created-at #(compare %2 %1))
         (mapv ->public-job)))
  (submit! [self config task-fn]
    (submit-job! self config task-fn))
  (close [_] (reset! jobs {})))

(defn create
  "Create a new in-memory job runner."
  [_]
  (->JobRunner (atom {})))

(defn shutdown!
  "Drop all tracked jobs."
  [runner]
  (close runner)
  nil)

(defmethod update-job! JobRunner
  [runner id f & args]
  (swap! (:jobs runner) update id (fn [job] (apply f job args))))
