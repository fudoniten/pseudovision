(ns pseudovision.http.api.grout
  "HTTP endpoint for syncing Grout long-form content into Pseudovision's
   catalog.

   Grout owns filler AND long-form `program` content on a filesystem shared with
   Pseudovision. Filler is pulled lazily at playout-build time, but content must
   be visible to the catalog aggregate and the daily-slot resolver BEFORE a
   schedule is built — so it is materialised by an explicit sync that mirrors
   each Grout program into a `program`-kind media item with metadata + tags.

   This endpoint triggers that sync on demand. It is idempotent and best-effort
   (a no-op when Grout is disabled), and is intended to be driven by a scheduled
   job (e.g. a Kubernetes CronJob) as well as manually."
  (:require [pseudovision.media.grout        :as grout]
            [pseudovision.media.grout-source :as grout-source]
            [taoensso.timbre                 :as log]))

(defn sync-grout-handler
  "POST /api/sync/grout — pull every `program` item from Grout and upsert it
   into the catalog. Returns a GroutSyncResult summary. Always 200: a disabled
   or unreachable Grout yields a truthful zero-count result rather than an
   error, since content sync is best-effort."
  [{:keys [db grout]}]
  (fn [_req]
    (when-not (grout/enabled? grout)
      (log/info "Grout content sync requested but Grout is disabled"))
    (let [result (grout-source/sync-programs! db grout)]
      (log/info "Grout content sync request complete" result)
      {:status 200 :body result})))
