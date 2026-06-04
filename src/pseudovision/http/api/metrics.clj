(ns pseudovision.http.api.metrics
  (:require [pseudovision.db.metrics      :as db]
            [pseudovision.util.pagination :as pagination]
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
          cursor     (parse-instant (:cursor qp))
          limit      (or (parse-int (:limit qp)) 100)
          items      (db/list-channel-views db {:channel-id channel-id
                                                :from from
                                                :to to
                                                :cursor cursor
                                                :limit limit})]
      (log/debug "Listing channel views" {:channel-id channel-id :from from :to to :limit limit})
      {:status 200
       :body (pagination/cursor-pagination-response 
               items 
               limit 
               (fn [last-item]
                 (when-let [started-at (:channel-views/started-at last-item)]
                   (str started-at))))})))

(defn list-media-item-views-handler
  [{:keys [db]}]
  (fn [req]
    (let [qp            (get-in req [:parameters :query])
          channel-id    (parse-int (:channel-id qp))
          media-item-id (parse-int (:media-item-id qp))
          from          (parse-instant (:from qp))
          to            (parse-instant (:to qp))
          cursor        (parse-instant (:cursor qp))
          limit         (or (parse-int (:limit qp)) 100)
          items         (db/list-media-item-views db {:channel-id    channel-id
                                                      :media-item-id media-item-id
                                                      :from from
                                                      :to to
                                                      :cursor cursor
                                                      :limit limit})]
      (log/debug "Listing media item views"
                 {:channel-id channel-id :media-item-id media-item-id :from from :to to :limit limit})
      {:status 200
       :body (pagination/cursor-pagination-response 
               items 
               limit 
               (fn [last-item]
                 (when-let [started-at (:media-item-views/started-at last-item)]
                   (str started-at))))})))
