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
     - channel (optional) — integer id, number, or name.
     - tag     (optional) — explicit tag to filter by (overrides channel inference).

   Returns the CatalogProfile JSON (§2.1 of the handoff spec)."
  [{:keys [db]}]
  (fn [req]
    (let [qp        (get-in req [:parameters :query])
          channel-ref (:channel qp)
          explicit-tag (:tag qp)
          ch          (when channel-ref (resolve-channel db channel-ref))
          channel-name (when ch (:channels/name ch))
          ;; If caller supplies an explicit tag, use it; otherwise derive from channel name.
          tag-filter (or explicit-tag
                         (catalog-db/channel-name->tag channel-name))]
      (log/info "Catalog aggregate request"
                {:channel-ref channel-ref
                 :resolved-channel channel-name
                 :tag-filter tag-filter})
      (let [profile (catalog-db/build-catalog-profile
                      db {:channel-name channel-name :tag-filter tag-filter})]
        ;; If a tag filter was applied but the scoped profile is empty (no shows
        ;; match), fall back to the full catalog so the caller still gets a
        ;; usable profile. This is a safety net while tag conventions are still
        ;; being standardised.
        (if (and tag-filter
                 (zero? (:total_items profile)))
          (do (log/warn "Tag filter returned empty catalog; falling back to full catalog"
                        {:tag tag-filter})
              {:status 200 :body (catalog-db/build-catalog-profile db {})})
          {:status 200 :body profile})))))

(defn catalog-count-handler
  "POST /api/catalog/count  (Phase 7, deferred — minimal stub)

   Body: {:filters {...}} → {:count N}
   For now returns the total playable item count so the contract is honoured."
  [{:keys [db]}]
  (fn [_req]
    (let [;; Stub: ignore filters and return total count
          total (or (:total_items (catalog-db/count-playable-items db nil)) 0)]
      {:status 200 :body {:count total}})))
