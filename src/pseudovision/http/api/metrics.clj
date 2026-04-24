(ns pseudovision.http.api.metrics
  (:require [pseudovision.db.metrics :as db]
            [taoensso.timbre :as log]))

(defn- parse-instant [s]
  (when s
    (try (java.time.Instant/parse s)
         (catch Exception _ nil))))

(defn- parse-int [s]
  (when s
    (try (Integer/parseInt (str s))
         (catch Exception _ nil))))

(defn list-channel-views-handler
  [{:keys [db]}]
  (fn [req]
    (let [qp         (get-in req [:parameters :query])
          channel-id (parse-int (:channel-id qp))
          from       (parse-instant (:from qp))
          to         (parse-instant (:to qp))
          limit      (parse-int (:limit qp))]
      (log/debug "Listing channel views" {:channel-id channel-id :from from :to to})
      {:status 200
       :body   (db/list-channel-views db {:channel-id channel-id
                                          :from from
                                          :to to
                                          :limit limit})})))

(defn list-media-item-views-handler
  [{:keys [db]}]
  (fn [req]
    (let [qp            (get-in req [:parameters :query])
          channel-id    (parse-int (:channel-id qp))
          media-item-id (parse-int (:media-item-id qp))
          from          (parse-instant (:from qp))
          to            (parse-instant (:to qp))
          limit         (parse-int (:limit qp))]
      (log/debug "Listing media item views"
                 {:channel-id channel-id :media-item-id media-item-id :from from :to to})
      {:status 200
       :body   (db/list-media-item-views db {:channel-id    channel-id
                                             :media-item-id media-item-id
                                             :from from
                                             :to to
                                             :limit limit})})))
