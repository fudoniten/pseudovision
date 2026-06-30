(ns pseudovision.http.api.catalog
  "Catalog aggregate endpoint that produces the CatalogProfile wire shape
   consumed by Tunarr Scheduler → Tunabrain."
  (:require [pseudovision.db.catalog        :as catalog-db]
            [pseudovision.db.channels       :as channels-db]
            [taoensso.timbre                :as log]))

(defn- resolve-channel
  "Resolves the `channel` query parameter to a channel map.
   Accepts an integer id, a string name, or a channel number."
  [ds channel-ref]
  (when channel-ref
    (if (integer? channel-ref)
      (channels-db/get-channel ds channel-ref)
      (or (channels-db/get-channel-by-number ds (str channel-ref))
          (channels-db/get-channel ds (try (Long/parseLong (str channel-ref))
                                           (catch Exception _ nil)))
          ;; Try exact name match
          (let [chs (channels-db/list-channels ds)]
            (first (filter #(= (:channels/name %) channel-ref) chs)))))))

(defn catalog-aggregate-handler
  "GET /api/catalog/aggregate

   Query params:
     - tag     (optional) — the dimension tag the catalog is scoped to, e.g.
                            \"channel:goldenreels\". This is what actually
                            filters the profile. Channel membership lives in
                            metadata_tags (Tunarr Scheduler writes the channel
                            dimension there during sync), and TS — which owns
                            the dimension value — passes it here verbatim.
     - channel (optional) — integer id, number, or name. Resolved only to
                            label :channel_scope in the response; it does NOT
                            drive filtering, because a channel's display name
                            is not reliably the same string as its dimension
                            tag value.

   Filtering is strictly tag-driven. When `tag` matches nothing the response is
   a truthful empty profile (total_items 0) — the handler never substitutes the
   full catalog, so a missed filter can never masquerade as the whole library.

   Returns the CatalogProfile JSON (§1 of the handoff spec)."
  [{:keys [db]}]
  (fn [req]
    (let [qp          (get-in req [:parameters :query])
          channel-ref (:channel qp)
          tag-filter  (:tag qp)
          ch          (when channel-ref (resolve-channel db channel-ref))
          channel-name (when ch (:channels/name ch))]
      (cond
        ;; A channel ref was supplied but resolves to nothing — surface it
        ;; instead of silently producing an unscoped or mislabelled profile.
        (and channel-ref (nil? ch))
        (do (log/warn "Catalog aggregate: channel not found"
                      {:channel-ref channel-ref})
            {:status 422 :body {:error "Channel not found"
                                :channel-ref channel-ref}})

        :else
        (do
          (when (and channel-ref (not tag-filter))
            (log/warn "Catalog aggregate: channel supplied without a tag filter; returning the full catalog (filtering is tag-driven, pass ?tag=channel:<value>)"
                      {:channel-ref channel-ref :resolved-channel channel-name}))
          (log/info "Catalog aggregate request"
                    {:channel-ref channel-ref
                     :resolved-channel channel-name
                     :tag-filter tag-filter})
          {:status 200
           :body (catalog-db/build-catalog-profile
                   db {:channel-name channel-name :tag-filter tag-filter})})))))

(defn catalog-count-handler
  "POST /api/catalog/count  (Phase 7, deferred — minimal stub)

   Body: {:filters {...}} → {:count N}
   For now returns the total playable item count so the contract is honoured."
  [{:keys [db]}]
  (fn [_req]
    (let [;; Stub: ignore filters and return total count
          total (or (:total_items (catalog-db/count-playable-items db nil)) 0)]
      {:status 200 :body {:count total}})))
