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

;; ---------------------------------------------------------------------------
;; GET /api/media/items/:id — display-title wiring
;;
;; Regression: the api/media.clj display-title-for-item fallback chain was
;; originally called with the *qualified-keyed* map from db/query-one (which
;; has keys like :m/title), not the unqualified one (which has :name, etc).
;; That made every branch of the fallback chain miss, so every item returned
;; :name "Unknown" — even Jellyfin items with a real metadata.title. The fix
;; is to unqualify-keys first, then pass the unqualified map to the display-
;; title fallback.
;; ---------------------------------------------------------------------------

(defn- stub-handler [item-row]
  (with-redefs [pseudovision.db.media/get-media-item (fn [_ _] item-row)]
    (let [handler ((requiring-resolve 'pseudovision.http.api.media/get-media-item-handler)
                   {:db nil})
          resp    (handler (mock/request :get "/api/media/items/1"))]
      ;; The handler already returns the response map directly (not via the
      ;; respond middleware in this test path), so the body is a plain map.
      {:status (:status resp) :body (:body resp)})))

(deftest get-media-item-returns-metadata-title-when-present
  (testing "Jellyfin item with metadata.title → that title is returned"
    (let [{:keys [status body]} (stub-handler
                                  {:media-items/id 1
                                   :m/title "Rear.Window.1954..."
                                   :media-items/remote-key "fc7619..."
                                   :media-items/kind "movie"})]
      (is (= 200 status))
      ;; Response has :title from the metadata column (unqualified-keyed map
      ;; via unqualify-keys) and :name from display-title-for-item.
      (is (= "Rear.Window.1954..." (:name body))))))

(deftest get-media-item-falls-back-to-remote-key
  (testing "Grout filler item with no metadata.title → remote-key is returned"
    (let [{:keys [status body]} (stub-handler
                                  {:media-items/id 1649133
                                   :m/title nil
                                   :media-items/remote-key "grout:5c707253-..."
                                   :media-items/kind "other_video"})]
      (is (= 200 status))
      (is (= "grout:5c707253-..." (:name body))))))

(deftest get-media-item-final-fallback-is-unknown
  (testing "no metadata.title AND no remote-key → literal \"Unknown\""
    (let [{:keys [status body]} (stub-handler
                                  {:media-items/id 1
                                   :m/title nil
                                   :media-items/remote-key nil
                                   :media-items/kind "other_video"})]
      (is (= 200 status))
      (is (= "Unknown" (:name body))))))

;; ---------------------------------------------------------------------------
;; The Jellyfin API key must never leave Pseudovision
;; ---------------------------------------------------------------------------

(def ^:private jellyfin-source
  {:media-sources/id 10
   :media-sources/name "jellyfin"
   :media-sources/kind "jellyfin"
   :media-sources/connection-config {:connections [{:uri "http://jellyfin:8096"}]
                                     :api-key "secret-key"}})

(deftest list-sources-omits-api-key
  (with-redefs [db/count-media-sources (fn [_] 1)
                db/list-media-sources  (fn [_ _] [jellyfin-source])]
    (let [handler ((requiring-resolve 'pseudovision.http.api.media/list-sources-handler)
                   {:db nil})
          source  (-> (handler {:parameters {:query {}}}) :body :items first)]
      (testing "connection URIs are still returned"
        (is (= [{:uri "http://jellyfin:8096"}]
               (get-in source [:connection-config :connections]))))
      (testing "the API key is not"
        (is (not (contains? (:connection-config source) :api-key)))))))

(deftest playback-url-points-at-the-pseudovision-proxy
  (with-redefs [db/get-media-item-with-source
                (fn [_ _] (merge jellyfin-source
                                 {:media-items/id 42
                                  :media-items/remote-key "abc123"}))]
    (let [handler ((requiring-resolve 'pseudovision.http.api.media/get-item-playback-url-handler)
                   {:db nil})
          resp    (handler {:scheme     :http
                            :headers    {"host" "pseudovision.local"}
                            :parameters {:path {:id "42"}}})]
      (is (= 200 (:status resp)))
      (is (= "http://pseudovision.local/api/media/items/42/stream"
             (get-in resp [:body :url])))
      (is (not (re-find #"api_key|secret-key" (get-in resp [:body :url])))))))

;; ---------------------------------------------------------------------------
;; Scan failures must be visible, and the key must be rotatable via the API
;; ---------------------------------------------------------------------------

(deftest scan-all-fails-the-job-when-a-library-fails
  (with-redefs [db/list-libraries
                (fn [_ _]
                  [{:libraries/id 1 :libraries/name "movies" :libraries/media-source-id 10}
                   {:libraries/id 2 :libraries/name "shows"  :libraries/media-source-id 10}])
                db/get-media-source
                (fn [_ _] {:media-sources/kind "jellyfin"})
                jellyfin/scan-library!
                (fn [_ _ library]
                  (when (= "shows" (:libraries/name library))
                    (throw (ex-info "Jellyfin rejected the API key (HTTP 401)" {}))))]
    (let [r       (runner/create {})
          handler (http/make-handler {:db nil :ffmpeg {} :media {} :scheduling {} :jobs r})
          job-id  (-> (handler (mock/request :post "/api/media/scan-all"))
                      parse-json-body
                      (get-in [:job :id]))
          info    (await-terminal r job-id)]
      (is (= :failed (:status info)))
      (is (re-find #"1 of 2 .*rejected the API key" (get-in info [:error :message])))
      (testing "the per-library summary is kept on the failed job"
        (is (= 1 (get-in info [:result :scanned])))
        (is (= 1 (get-in info [:result :failed])))))))

(deftest check-server-says-why-it-failed
  (testing "a rejected key is reported as such, not as a connection failure"
    (with-redefs [clj-http.client/get (fn [& _] {:status 401 :body nil})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected the API key \(HTTP 401\)"
                            (jellyfin/check-server "http://jellyfin:8096" "bad-key")))))
  (testing "an unreachable server is a connection failure"
    (with-redefs [clj-http.client/get (fn [& _] (throw (java.net.ConnectException. "refused")))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot connect to Jellyfin server: refused"
                            (jellyfin/check-server "http://jellyfin:8096" "key")))))
  (testing "success returns the server info"
    (with-redefs [clj-http.client/get (fn [& _] {:status 200 :body {:ServerName "jf"}})]
      (is (= "jf" (:ServerName (jellyfin/check-server "http://jellyfin:8096" "key")))))))

(deftest update-source-rotates-the-api-key
  (let [written (atom nil)]
    (with-redefs [db/get-media-source     (fn [_ _] jellyfin-source)
                  db/update-media-source! (fn [_ _ attrs]
                                            (reset! written attrs)
                                            (assoc jellyfin-source
                                                   :media-sources/connection-config
                                                   (:connection-config attrs)))]
      (let [handler ((requiring-resolve 'pseudovision.http.api.media/update-source-handler)
                     {:db nil})
            resp    (handler {:parameters {:path {:id 10} :body {:api-key "new-key"}}})]
        (is (= 200 (:status resp)))
        (testing "the new key is merged into the existing connection config"
          (is (= {:connections [{:uri "http://jellyfin:8096"}] :api-key "new-key"}
                 (:connection-config @written))))
        (testing "the response still omits the key"
          (is (not (contains? (get-in resp [:body :connection-config]) :api-key))))))))

(deftest update-source-404s-for-a-missing-source-when-rotating
  (with-redefs [db/get-media-source     (fn [_ _] nil)
                db/update-media-source! (fn [& _] (throw (AssertionError. "should not write")))]
    (let [handler ((requiring-resolve 'pseudovision.http.api.media/update-source-handler)
                   {:db nil})]
      (is (= 404 (:status (handler {:parameters {:path {:id 99} :body {:api-key "k"}}})))))))
