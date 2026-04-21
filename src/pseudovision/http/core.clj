(ns pseudovision.http.core
  (:require [reitit.ring                            :as ring]
            [reitit.openapi                         :as openapi]
            [reitit.swagger-ui                      :as swagger-ui]
            [reitit.coercion.malli                  :as malli-coercion]
            [reitit.ring.coercion                   :as rrc]
            [reitit.ring.middleware.parameters      :as parameters]
            [reitit.ring.middleware.muuntaja        :as muuntaja-mw]
            [ring.adapter.jetty      :as jetty]
            [pseudovision.http.middleware     :as mw]
            [pseudovision.http.schemas        :as s]
            [pseudovision.http.api.channels   :as ch]
            [pseudovision.http.api.schedules  :as sc]
            [pseudovision.http.api.playouts   :as pl]
            [pseudovision.http.api.epg        :as epg]
            [pseudovision.http.api.m3u        :as m3u]
            [pseudovision.http.api.media      :as med]
            [pseudovision.http.api.streaming  :as streaming]
            [pseudovision.http.api.tags       :as tags]
            [pseudovision.http.api.test       :as test]
            [pseudovision.http.api.ffmpeg     :as ffmpeg]
            [pseudovision.http.api.logos      :as logos]))

(defn- routes [ctx]
  [""
   ;; ── OpenAPI spec ────────────────────────────────────────────────────────
   ;; Served at /openapi.json; Swagger UI at /swagger-ui/ consumes it.
   ;; Routes without :openapi / :parameters / :responses metadata still appear
   ;; in the spec as bare path+method entries; they'll gain detail as handlers
   ;; are annotated incrementally.
   ["/openapi.json"
    {:get {:no-doc  true
           :openapi {:info {:title       "Pseudovision API"
                            :version     "0.1.0"
                            :description "Pseudovision REST API"}}
           :handler (openapi/create-openapi-handler)}}]

   ;; ── Health ──────────────────────────────────────────────────────────────
   ["/health"
    {:get (fn [_] {:status 200 :body {:status "ok"}})}]

   ;; ── Channels ────────────────────────────────────────────────────────────
   ["/api/channels"
    {:tags    ["channels"]
     :get     {:summary     "List channels (optionally filter by UUID)"
               :parameters  {:query [:map
                                     [:uuid {:optional true} :uuid]]}
               :responses   {200 {:body [:or s/Channel [:vector s/Channel]]}
                             404 {:body s/APIError}}
               :handler     (ch/list-channels-handler ctx)}
     :post    {:summary     "Create a channel"
               :parameters  {:body s/ChannelCreate}
               :responses   {201 {:body s/Channel}
                             400 {:body s/CoercionError}}
               :handler     (ch/create-channel-handler ctx)}}]
   ["/api/channels/:id"
    {:tags    ["channels"]
     :parameters {:path [:map [:id s/ChannelId]]}
     :get     {:summary     "Get a channel by id"
               :responses   {200 {:body s/Channel}
                             404 {:body s/APIError}}
               :handler     (ch/get-channel-handler ctx)}
     :put     {:summary     "Update a channel"
               :parameters  {:body s/ChannelUpdate}
               :responses   {200 {:body s/Channel}
                             404 {:body s/APIError}
                             400 {:body s/CoercionError}}
               :handler     (ch/update-channel-handler ctx)}
     :delete  {:summary     "Delete a channel"
               :responses   {204 {}}
               :handler     (ch/delete-channel-handler ctx)}}]

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
   ["/api/media/sources/:id"
    {:delete (med/delete-source-handler ctx)}]
   ["/api/media/libraries"
    {:get  (med/list-all-libraries-handler ctx)}]
   ["/api/media/sources/:id/libraries"
    {:get  (med/list-libraries-handler  ctx)
     :post (med/create-library-handler ctx)}]
   ["/api/media/sources/:id/libraries/discover"
    {:post (med/discover-libraries-handler ctx)}]
   ["/api/media/libraries/:id/items"
    {:get (med/list-library-items-handler ctx)}]
   ["/api/media/libraries/:id/scan"
    {:post (med/trigger-scan-handler ctx)}]
   ["/api/media/items/:id"
    {:get (med/get-media-item-handler ctx)}]
   ["/api/media/items/:id/playback-url"
    {:get (med/get-item-playback-url-handler ctx)}]
   ["/api/media/items/:id/stream"
    {:get (med/redirect-to-stream-handler ctx)}]
   ["/api/media/collections"
     {:get  (med/list-collections-handler  ctx)
      :post (med/create-collection-handler ctx)}]

   ;; ── Tags ────────────────────────────────────────────────────────────────
   ["/api/tags"
     {:get (tags/list-all-tags-handler ctx)}]
   ["/api/media-items/:id/tags"
     {:get  (tags/get-tags-handler ctx)
      :post (tags/add-tags-handler ctx)}]
   ["/api/media-items/:id/tags/:tag"
     {:delete (tags/delete-tag-handler ctx)}]

   ;; ── FFmpeg Profiles ─────────────────────────────────────────────────────
   ["/api/ffmpeg/profiles"
    {:get  (ffmpeg/list-profiles-handler ctx)
     :post (ffmpeg/create-profile-handler ctx)}]
   ["/api/ffmpeg/profiles/:id"
    {:get    (ffmpeg/get-profile-handler ctx)
     :put    (ffmpeg/update-profile-handler ctx)
     :delete (ffmpeg/delete-profile-handler ctx)}]

   ;; ── Version & Health ────────────────────────────────────────────────────
   ["/api/version"
    {:get (fn [_] {:status 200
                   :body {:git-commit (System/getenv "GIT_COMMIT")
                          :git-timestamp (System/getenv "GIT_TIMESTAMP")
                          :version-tag (System/getenv "VERSION_TAG")}})}]

   ;; ── Test Utilities ──────────────────────────────────────────────────────
   ["/api/test/info"
    {:get (test/test-info-handler ctx)}]
   ["/api/test/collection"
    {:post (test/create-test-collection-handler ctx)}]
   ["/api/test/channels"
    {:get  (test/list-test-channels-handler ctx)
     :post (test/create-test-channel-handler ctx)}]
   ["/api/test/channels/:identifier"
    {:delete (test/delete-test-channel-handler ctx)}]
   ["/api/test/channels/:identifier/artwork"
    {:post (test/add-test-artwork-handler ctx)}]

   ;; ── Output formats ──────────────────────────────────────────────────────
   ["/xmltv"          {:get (epg/xmltv-handler ctx)}]
   ["/epg.xml"        {:get (epg/xmltv-handler ctx)}]   ; alias
   ["/media/devices/X-Plex-Client-Profile-Extra"
    {:get (m3u/hdhr-device-handler ctx)}]
   ["/lineup.json"    {:get (m3u/hdhr-lineup-handler ctx)}]
   ["/lineup_status.json" {:get (m3u/hdhr-status-handler ctx)}]
   ["/iptv/channels.m3u" {:get (m3u/m3u-handler ctx)}]

   ;; ── Streaming ───────────────────────────────────────────────────────────
   ["/stream/:uuid"   {:get (streaming/stream-handler ctx)}]
   ["/stream/:uuid/:segment" {:get (streaming/segment-handler ctx)}]

   ;; ── Artwork ─────────────────────────────────────────────────────────────
   ["/logos/:uuid" {:get (logos/logos-handler ctx)}]

   ;; ── Debug ───────────────────────────────────────────────────────────────
   ["/api/debug/stream/:uuid" {:get (streaming/stream-debug-handler ctx)}]])

(defn make-handler
  "Creates the reitit Ring handler.

   Route data supplies the canonical Reitit middleware chain — parameters,
   Muuntaja (JSON request decoding), the application exception handler, and
   malli coercion for :parameters / :responses. Routes without schemas still
   traverse the chain as a pass-through; :body-params is populated from
   request JSON identically to the old custom middleware.

   Outer wraps — error handling, request logging, JSON response encoding —
   cover the entire dispatch tree so that unmatched routes (404/405) and
   the Swagger UI handler also go through them."
  [ctx]
  (let [dispatch (ring/ring-handler
                  (ring/router
                   (routes ctx)
                   {:data {:muuntaja   mw/muuntaja
                           :coercion   malli-coercion/coercion
                           :middleware [parameters/parameters-middleware
                                        muuntaja-mw/format-negotiate-middleware
                                        muuntaja-mw/format-request-middleware
                                        mw/exception-middleware
                                        rrc/coerce-request-middleware]}})
                  (ring/routes
                   (swagger-ui/create-swagger-ui-handler
                    {:path "/swagger-ui"
                     :url  "/openapi.json"})
                   (ring/create-default-handler
                    {:not-found          (fn [_] {:status 404 :body {:error "Not found"}})
                     :method-not-allowed (fn [_] {:status 405 :body {:error "Method not allowed"}})})))]
    (-> dispatch
        mw/wrap-json-response
        mw/wrap-request-logging
        mw/wrap-error-handler)))

(defn start-server!
  "Assembles the handler context from opts and starts a non-blocking Jetty server."
  [{:keys [port db ffmpeg media scheduling] :as _opts}]
  (let [ctx     {:db db :ffmpeg ffmpeg :media media :scheduling scheduling}
        handler (make-handler ctx)]
    (jetty/run-jetty handler {:port port :join? false})))

(defn stop-server! [server]
  (.stop server))
