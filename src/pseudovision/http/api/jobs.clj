(ns pseudovision.http.api.jobs
  "HTTP handlers for async job tracking.

   Wire-compatible with the Tunarr Scheduler `/api/jobs` endpoints so a single
   UI can poll either backend identically:

     GET /api/jobs          → {:jobs [<job> ...]}   (newest-first)
     GET /api/jobs/:job-id  → {:job  <job>}         (404 when unknown)"
  (:require [pseudovision.jobs.runner :as runner]))

(defn list-jobs-handler
  "List all tracked async jobs, newest-first."
  [{:keys [jobs]}]
  (fn [_]
    {:status 200
     :body   {:jobs (runner/list-jobs jobs)}}))

(defn get-job-handler
  "Fetch a single job's status, progress, and (once finished) result."
  [{:keys [jobs]}]
  (fn [req]
    (let [job-id (get-in req [:parameters :path :job-id])]
      (if-let [job (runner/job-info jobs job-id)]
        {:status 200 :body {:job job}}
        {:status 404 :body {:error "Job not found"}}))))
