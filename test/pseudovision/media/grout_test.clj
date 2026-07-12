(ns pseudovision.media.grout-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-http.client :as http]
            [pseudovision.media.grout :as sut]))

;; ---------------------------------------------------------------------------
;; client / enabled?
;; ---------------------------------------------------------------------------

(deftest client-nil-without-base-url
  (testing "no base-url => nil client (disabled)"
    (is (nil? (sut/client {})))
    (is (nil? (sut/client {:base-url ""})))
    (is (nil? (sut/client {:base-url "   "})))))

(deftest client-trims-trailing-slash-and-defaults
  (let [c (sut/client {:base-url "http://grout:8080/"})]
    (is (= "http://grout:8080" (:base-url c)))
    (is (true? (:enabled? c)))
    (is (= 5000 (:timeout-ms c)))))

(deftest client-respects-explicit-disable
  (is (false? (:enabled? (sut/client {:base-url "http://grout:8080" :enabled? false})))))

(deftest enabled?-predicate
  (is (true?  (sut/enabled? (sut/client {:base-url "http://grout:8080"}))))
  (is (false? (sut/enabled? (sut/client {:base-url "http://grout:8080" :enabled? false}))))
  (is (false? (sut/enabled? nil))))

;; ---------------------------------------------------------------------------
;; query — param mapping (snake_case) + response passthrough
;; ---------------------------------------------------------------------------

(defn- capture-get
  "Redefs clj-http.client/get to capture the request and return `resp`."
  [captured resp]
  (fn [url opts]
    (reset! captured {:url url :opts opts})
    resp))

(deftest query-maps-params-to-snake-case
  (let [captured (atom nil)
        client   (sut/client {:base-url "http://grout:8080"})]
    (with-redefs [http/get (capture-get captured {:status 200 :body {:count 0 :items []}})]
      (sut/query client {:channel "britannia"
                         :tags    ["daytime" "fun"]
                         :min-ms  65000
                         :max-ms  90000
                         :kind    "filler"
                         :random  true
                         :limit   1}))
    (let [qp (get-in @captured [:opts :query-params])]
      (is (= "http://grout:8080/grout/media" (:url @captured)))
      (is (= "britannia"    (get qp "channel")))
      (is (= "daytime,fun"  (get qp "tags")))
      (is (= 65000          (get qp "min_ms")))
      (is (= 90000          (get qp "max_ms")))
      (is (= "filler"       (get qp "kind")))
      (is (= true           (get qp "random")))
      (is (= 1              (get qp "limit"))))))

(deftest query-maps-offset
  (let [captured (atom nil)
        client   (sut/client {:base-url "http://grout:8080"})]
    (with-redefs [http/get (capture-get captured {:status 200 :body {:items []}})]
      (sut/query client {:offset 40 :limit 10}))
    (is (= 40 (get-in @captured [:opts :query-params "offset"])))))

(deftest query-omits-nil-params
  (let [captured (atom nil)
        client   (sut/client {:base-url "http://grout:8080"})]
    (with-redefs [http/get (capture-get captured {:status 200 :body {:items []}})]
      (sut/query client {:tags []}))
    (let [qp (get-in @captured [:opts :query-params])]
      (is (not (contains? qp "channel")))
      (is (not (contains? qp "tags")))
      (is (not (contains? qp "min_ms")))
      (is (not (contains? qp "random"))))))

(deftest query-disabled-client-skips-request
  (with-redefs [http/get (fn [& _] (throw (Exception. "should not call http")))]
    (is (nil? (sut/query nil {:channel "x"})))
    (is (nil? (sut/query (sut/client {:base-url "http://g" :enabled? false}) {})))))

;; ---------------------------------------------------------------------------
;; find-filler — returns items vector, degrades gracefully
;; ---------------------------------------------------------------------------

(deftest find-filler-returns-items
  (let [items  [{:id "a" :path "/data/a.mp4" :duration-ms 65000}]
        client (sut/client {:base-url "http://grout:8080"})]
    (with-redefs [http/get (fn [_ _] {:status 200 :body {:count 1 :items items}})]
      (is (= items (sut/find-filler client {:channel "britannia"}))))))

(deftest find-filler-empty-on-error
  (let [client (sut/client {:base-url "http://grout:8080"})]
    (testing "network error => []"
      (with-redefs [http/get (fn [_ _] (throw (java.net.ConnectException. "refused")))]
        (is (= [] (sut/find-filler client {:channel "britannia"})))))
    (testing "non-2xx => []"
      (with-redefs [http/get (fn [_ _] {:status 500 :body "boom"})]
        (is (= [] (sut/find-filler client {:channel "britannia"})))))
    (testing "disabled => []"
      (is (= [] (sut/find-filler nil {:channel "britannia"}))))))

(deftest find-filler-applies-default-limit
  (let [captured (atom nil)
        client   (sut/client {:base-url "http://grout:8080"})]
    (with-redefs [http/get (capture-get captured {:status 200 :body {:items []}})]
      (sut/find-filler client {:channel "britannia"}))
    (is (= 50 (get-in @captured [:opts :query-params "limit"])))))

;; ---------------------------------------------------------------------------
;; list-all / list-programs — paginated sweep for content sync
;; ---------------------------------------------------------------------------

(deftest list-all-empty-when-disabled
  (with-redefs [http/get (fn [& _] (throw (Exception. "should not query")))]
    (is (= [] (sut/list-all nil {})))))

(deftest list-all-pages-until-short-page
  (let [client (sut/client {:base-url "http://grout:8080"})
        offsets (atom [])
        ;; page size is 200: full, full, then a short final page.
        pages  {0   (vec (repeat 200 {:id "a"}))
                200 (vec (repeat 200 {:id "b"}))
                400 (vec (repeat 30  {:id "c"}))}]
    (with-redefs [sut/query (fn [_ opts]
                              (swap! offsets conj (:offset opts))
                              {:items (get pages (:offset opts) [])})]
      (let [result (sut/list-all client {:kind "program"})]
        (is (= 430 (count result)))
        (is (= [0 200 400] @offsets) "walks offsets by page size, stops on the short page")))))

(deftest list-all-honours-max-pages-safety-valve
  (let [client (sut/client {:base-url "http://grout:8080"})
        calls  (atom 0)]
    ;; Every page is full (200), so only max-pages stops the loop.
    (with-redefs [sut/query (fn [_ _] (swap! calls inc) {:items (vec (repeat 200 {:id "x"}))})]
      (is (= 400 (count (sut/list-all client {} 2))))
      (is (= 2 @calls)))))

(deftest list-programs-queries-kind-program
  (let [captured (atom nil)
        client   (sut/client {:base-url "http://grout:8080"})]
    (with-redefs [sut/query (fn [_ opts] (reset! captured opts) {:items []})]
      (sut/list-programs client))
    (is (= "program" (:kind @captured)))))

;; ---------------------------------------------------------------------------
;; point lookups
;; ---------------------------------------------------------------------------

(deftest by-hash-guards-blank-and-disabled
  (with-redefs [http/get (fn [& _] (throw (Exception. "should not call")))]
    (is (nil? (sut/by-hash (sut/client {:base-url "http://g"}) "")))
    (is (nil? (sut/by-hash nil "abc")))))

(deftest by-hash-hits-endpoint
  (let [captured (atom nil)
        client   (sut/client {:base-url "http://grout:8080"})]
    (with-redefs [http/get (capture-get captured {:status 200 :body {:id "x"}})]
      (is (= {:id "x"} (sut/by-hash client "deadbeef"))))
    (is (= "http://grout:8080/grout/by-hash/deadbeef" (:url @captured)))))
