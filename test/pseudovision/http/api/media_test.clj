(ns pseudovision.http.api.media-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [cheshire.core     :as json]
            [pseudovision.http.core    :as http]
            [pseudovision.jobs.runner  :as runner]
            [pseudovision.db.media     :as db]
            [pseudovision.media.jellyfin :as jellyfin]))

(defn- parse-json-body [resp]
  (some-> resp :body (json/parse-string true)))

(defn- await-terminal [r job-id]
  (loop [n 0]
    (let [info (runner/job-info r job-id)]
      (if (or (contains? #{:succeeded :failed} (:status info)) (>= n 200))
        info
        (do (Thread/sleep 15) (recur (inc n)))))))

(deftest scan-all-scans-only-jellyfin-libraries
  (testing "POST /api/media/scan-all scans Jellyfin-backed libraries and skips
            non-Jellyfin ones (e.g. the local grout-content library)"
    (let [scanned (atom [])]
      (with-redefs [db/list-libraries
                    (fn [_ _]
                      [{:libraries/id 1 :libraries/name "movies"        :libraries/media-source-id 10}
                       {:libraries/id 2 :libraries/name "grout-content" :libraries/media-source-id 20}])
                    db/get-media-source
                    (fn [_ sid] (case sid
                                  10 {:media-sources/kind "jellyfin"}
                                  20 {:media-sources/kind "local"}))
                    jellyfin/scan-library!
                    (fn [_ _ library] (swap! scanned conj (:libraries/name library)) nil)]
        (let [r       (runner/create {})
              handler (http/make-handler {:db nil :ffmpeg {} :media {} :scheduling {} :jobs r})
              resp    (handler (mock/request :post "/api/media/scan-all"))
              body    (parse-json-body resp)
              job-id  (get-in body [:job :id])
              info    (await-terminal r job-id)]
          (is (= 202 (:status resp)))
          (is (= "media/scan-all" (get-in body [:job :type])))
          (is (= :succeeded (:status info)))
          (is (= ["movies"] @scanned)
              "only the jellyfin library is scanned; grout-content is skipped")
          (is (= 1 (get-in info [:result :scanned])))
          (is (= 1 (get-in info [:result :skipped]))))))))
