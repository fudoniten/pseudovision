(ns pseudovision.jobs.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [pseudovision.jobs.runner :as runner]))

(defn- await-status
  "Poll the runner until `job-id` reaches a terminal status or we run out of
   attempts. Returns the final public job view."
  [r job-id]
  (loop [remaining 200]
    (let [job (runner/job-info r job-id)]
      (if (or (contains? #{:succeeded :failed} (:status job))
              (zero? remaining))
        job
        (do (Thread/sleep 5)
            (recur (dec remaining)))))))

(deftest submit-runs-task-and-records-result
  (testing "a submitted job runs, succeeds, and exposes its result"
    (let [r   (runner/create {})
          {:keys [id status type]} (runner/submit! r {:type :playout/rebuild
                                                      :metadata {:channel-id 7}}
                                                   (fn [_report] {:events-generated 42}))]
      (is (string? id))
      (is (= :playout/rebuild type))
      (is (contains? #{:queued :running} status) "initial status is not yet terminal")
      (let [final (await-status r id)]
        (is (= :succeeded (:status final)))
        (is (= {:events-generated 42} (:result final)))
        (is (= {:channel-id 7} (:metadata final)))
        (is (string? (:created-at final)))
        (is (string? (:started-at final)))
        (is (string? (:completed-at final)))
        (is (int? (:duration-ms final)))))))

(deftest failed-task-records-error
  (testing "an exception in the task surfaces as :failed with an :error"
    (let [r (runner/create {})
          {:keys [id]} (runner/submit! r :playout/rebuild
                                       (fn [_report] (throw (ex-info "boom" {}))))
          final (await-status r id)]
      (is (= :failed (:status final)))
      (is (= "boom" (get-in final [:error :message])))
      (is (string? (get-in final [:error :type]))))))

(deftest report-progress-updates-job
  (testing "the report-progress callback is reflected in the job view"
    (let [r       (runner/create {})
          gate    (promise)
          {:keys [id]} (runner/submit! r :playout/rebuild
                                       (fn [report]
                                         (report {:phase "rebuilding" :total 10 :completed 3})
                                         @gate
                                         {:done true}))]
      ;; Spin until progress lands, then release the task.
      (loop [n 200]
        (when (and (pos? n) (nil? (:progress (runner/job-info r id))))
          (Thread/sleep 5)
          (recur (dec n))))
      (is (= {:phase "rebuilding" :total 10 :completed 3}
             (:progress (runner/job-info r id))))
      (deliver gate :go)
      (is (= :succeeded (:status (await-status r id)))))))

(deftest list-jobs-is-newest-first
  (testing "list-jobs returns jobs ordered newest-first"
    (let [r (runner/create {})]
      (let [a (:id (runner/submit! r :playout/rebuild (fn [_] :a)))]
        (Thread/sleep 5)
        (let [b (:id (runner/submit! r :playout/rebuild (fn [_] :b)))]
          (await-status r a)
          (await-status r b)
          (is (= [b a] (mapv :id (runner/list-jobs r)))))))))

(deftest unknown-job-is-nil
  (is (nil? (runner/job-info (runner/create {}) "no-such-id"))))
