(ns pseudovision.http.core
  (:require [reitit.ring             :as ring]
            [ring.adapter.jetty      :as jetty]
            [pseudovision.http.middleware     :as mw]
            [pseudovision.http.api.channels   :as ch]
            [pseudovision.http.api.schedules  :as sc]
            [pseudovision.http.api.playouts   :as pl]
            [pseudovision.http.api.epg        :as epg]
            [pseudovision.http.api.m3u        :as m3u]
            [pseudovision.http.api.media      :as med]))

(defn- routes [ctx]
  [""
   {:middleware mw/default-middleware}

   ;; ── Health ──────────────────────────────────────────────────────────────
   ["/health"
    {:get (fn [_] {:status 200 :body {:status "ok"}})}]

   ;; ── Channels ────────────────────────────────────────────────────────────
   ["/api/channels"
    {:get  (ch/list-channels-handler  ctx)
     :post (ch/create-channel-handler ctx)}]
   ["/api/channels/:id"
    {:get    (ch/get-channel-handler    ctx)
     :put    (ch/update-channel-handler ctx)
     :delete (ch/delete-channel-handler ctx)}]

   ;; ── Schedules ───────────────────────────────────────────────────────────
   ["/api/schedules"
    {:get  (sc/list-schedules-handler  ctx)
     :post (sc/create-schedule-handler ctx)}]
   ["/api/schedules/:id"
    {:get    (sc/get-schedule-handler    ctx)
     :put    (sc/update-schedule-handler ctx)
     :delete (sc/delete-schedule-handler ctx)}]
   ["/api/schedules/:schedule-id/slots"
    {:get  (sc/list-slots-handler  ctx)
     :post (sc/create-slot-handler ctx)}]
   ["/api/schedules/:schedule-id/slots/:id"
    {:get    (sc/get-slot-handler    ctx)
     :put    (sc/update-slot-handler ctx)
     :delete (sc/delete-slot-handler ctx)}]

   ;; ── Playouts ────────────────────────────────────────────────────────────
   ["/api/channels/:channel-id/playout"
    {:get  (pl/get-playout-handler    ctx)
     :post (pl/rebuild-playout-handler ctx)}]
   ["/api/channels/:channel-id/playout/events"
    {:get  (pl/list-events-handler  ctx)
     :post (pl/inject-event-handler ctx)}]   ; manual event injection
   ["/api/channels/:channel-id/playout/events/:id"
    {:put    (pl/update-event-handler ctx)
     :delete (pl/delete-event-handler ctx)}]

   ;; ── Media ───────────────────────────────────────────────────────────────
   ["/api/media/sources"
    {:get  (med/list-sources-handler  ctx)
     :post (med/create-source-handler ctx)}]
   ["/api/media/sources/:id/libraries"
    {:get  (med/list-libraries-handler  ctx)
     :post (med/create-library-handler ctx)}]
   ["/api/media/libraries/:id/scan"
    {:post (med/trigger-scan-handler ctx)}]
   ["/api/media/collections"
    {:get  (med/list-collections-handler  ctx)
     :post (med/create-collection-handler ctx)}]

   ;; ── Output formats ──────────────────────────────────────────────────────
   ["/xmltv"          {:get (epg/xmltv-handler ctx)}]
   ["/epg.xml"        {:get (epg/xmltv-handler ctx)}]   ; alias
   ["/media/devices/X-Plex-Client-Profile-Extra"
    {:get (m3u/hdhr-device-handler ctx)}]
   ["/lineup.json"    {:get (m3u/hdhr-lineup-handler ctx)}]
   ["/lineup_status.json" {:get (m3u/hdhr-status-handler ctx)}]
   ["/iptv/channels.m3u" {:get (m3u/m3u-handler ctx)}]])

(defn make-handler [ctx]
  (ring/ring-handler
   (ring/router (routes ctx))
   (ring/create-default-handler
    {:not-found          (fn [_] {:status 404 :body {:error "Not found"}})
     :method-not-allowed (fn [_] {:status 405 :body {:error "Method not allowed"}})})))

(defn start-server!
  [{:keys [port db ffmpeg media scheduling] :as _opts}]
  (let [ctx     {:db db :ffmpeg ffmpeg :media media :scheduling scheduling}
        handler (make-handler ctx)]
    (jetty/run-jetty handler {:port port :join? false})))

(defn stop-server! [server]
  (.stop server))
