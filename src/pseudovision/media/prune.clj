(ns pseudovision.media.prune
  "Prunes Jellyfin items that were deleted from Jellyfin.

   Pruning runs at the end of a Jellyfin library scan, and only when that
   scan fetched the library's complete listing. It works in two steps so a
   bad scan can never wipe the catalog:

     1. Mark. An item that the listing no longer contains gets
        state = 'unavailable' and missing_since = now. That hides it from the
        catalog and from new playout builds, and nothing is deleted.
     2. Delete. A later complete scan that still doesn't list the item, at
        least `:grace-hours` after it was marked, deletes it, along with the
        playout events that reference it.

   An item that shows up again before step 2 goes straight back to normal.

   Safeguards:
     - No pruning at all unless the scan fetched every page and the count
       matched Jellyfin's TotalRecordCount (see jellyfin/fetch-all-items).
     - An empty listing for a library PV holds items for is refused.
     - Too many missing items at once (`:max-missing-count`, or more than
       `:max-missing-fraction` of the library once over `:guard-floor`
       items) is refused unless the scan was forced. A refused scan marks
       and deletes nothing.
     - An item, or any item under it, that a schedule slot or filler preset
       pins directly is never deleted, because deleting it would silently
       turn the slot into a catalog slot. It stays marked and is reported
       as blocked.
     - An item with a child that is still listed is never deleted (deleting
       a show cascades to its episodes)."
  (:require [clojure.string          :as str]
            [next.jdbc               :as jdbc]
            [next.jdbc.result-set    :as rs]
            [pseudovision.util.sql   :as sql-util]
            [taoensso.timbre         :as log])
  (:import [java.time Duration Instant]))

(def default-opts
  {:grace-hours          20
   :max-missing-count    500
   :max-missing-fraction 0.2
   :guard-floor          10})

;; ---------------------------------------------------------------------------
;; Planning (pure)
;; ---------------------------------------------------------------------------

(defn- refusal
  "Returns a reason string when pruning `missing` of `known` items looks like
   a broken listing rather than real deletions, or nil when it's safe."
  [known missing seen-count {:keys [max-missing-count max-missing-fraction guard-floor]}]
  (cond
    (zero? missing) nil

    (and (zero? seen-count) (pos? known))
    (str "Jellyfin listed no items, but PV has " known " for this library")

    (> missing max-missing-count)
    (str missing " items are missing, more than the limit of " max-missing-count)

    (and (> missing guard-floor)
         (> (/ (double missing) known) max-missing-fraction))
    (format "%d of %d items (%.0f%%) are missing, more than the limit of %.0f%%"
            missing known (* 100.0 (/ (double missing) known))
            (* 100.0 max-missing-fraction))))

(defn plan
  "Decides what to do with each known item after a complete listing.

   `known` is a seq of `{:id :remote-key :missing-since}` for every item in
   the library path that has a remote key. `seen` is the set of Jellyfin ids
   in the listing. `now` is an Instant.

   Returns
     {:restore       [ids]  ; marked missing before, listed again
      :mark          [ids]  ; newly missing
      :delete        [ids]  ; missing for longer than the grace period
      :waiting       [ids]  ; missing, still inside the grace period
      :missing       n      ; total not in the listing
      :refused       reason-or-nil}

   When refused, :mark and :delete are empty; restores still happen, since
   putting an item back is always safe."
  [known seen ^Instant now opts]
  (let [{:keys [grace-hours force?] :as opts} (merge default-opts opts)
        cutoff     (.minus now (Duration/ofMinutes (long (* 60 grace-hours))))
        listed?    #(contains? seen (:remote-key %))
        {listed true unlisted false} (group-by (comp boolean listed?) known)
        restore    (into [] (comp (filter :missing-since) (map :id)) listed)
        mark       (into [] (comp (remove :missing-since) (map :id)) unlisted)
        expired?   #(and (:missing-since %)
                         (not (.isAfter ^Instant (:missing-since %) cutoff)))
        delete     (into [] (comp (filter expired?) (map :id)) unlisted)
        waiting    (into [] (comp (filter :missing-since) (remove expired?) (map :id))
                         unlisted)
        missing    (count unlisted)
        reason     (when-not force?
                     (refusal (count known) missing (count seen) opts))]
    (cond-> {:restore restore
             :mark    mark
             :delete  delete
             :waiting waiting
             :missing missing
             :refused reason}
      reason (assoc :mark [] :delete []))))

;; ---------------------------------------------------------------------------
;; Database
;; ---------------------------------------------------------------------------

(def ^:private unqualified {:builder-fn rs/as-unqualified-kebab-maps})

(defn- known-items [ds library-path-id]
  (jdbc/execute! ds
                 ["SELECT id, remote_key, missing_since
                     FROM media_items
                    WHERE library_path_id = ? AND remote_key IS NOT NULL"
                  library-path-id]
                 unqualified))

(defn- id-array [ids]
  (into-array Integer (map int ids)))

(defn- subtree-cte
  "WITH RECURSIVE sub(root, id): every item under each root, roots included."
  []
  (str "WITH RECURSIVE sub(root, id) AS ("
       "  SELECT id, id FROM media_items WHERE id = ANY(?)"
       "  UNION"
       "  SELECT sub.root, mi.id FROM media_items mi JOIN sub ON mi.parent_id = sub.id"
       ") "))

(defn- blocked-roots
  "Of `ids`, returns {id reason} for those that must not be deleted: a
   schedule slot or filler preset pins the item or something under it, or an
   item under it is still listed in Jellyfin."
  [tx ids]
  (if (empty? ids)
    {}
    (->> (jdbc/execute! tx
                        [(str (subtree-cte)
                              "SELECT sub.root AS root,"
                              "  bool_or(EXISTS (SELECT 1 FROM schedule_slots s WHERE s.media_item_id = sub.id)) AS slot,"
                              "  bool_or(EXISTS (SELECT 1 FROM filler_presets f WHERE f.media_item_id = sub.id)) AS filler,"
                              "  bool_or(sub.id <> sub.root AND mi.missing_since IS NULL) AS live_child"
                              " FROM sub JOIN media_items mi ON mi.id = sub.id"
                              " GROUP BY sub.root")
                         (id-array ids)]
                        unqualified)
         (keep (fn [{:keys [root slot filler live-child]}]
                 (when-let [reason (cond slot       "pinned by a schedule slot"
                                         filler     "pinned by a filler preset"
                                         live-child "has items still in Jellyfin")]
                   [root reason])))
         (into {}))))

(defn- titles
  "{id title} for the given items, for logging and the scan report."
  [tx ids]
  (if (empty? ids)
    {}
    (->> (jdbc/execute! tx
                        ["SELECT mi.id, COALESCE(m.title, mi.remote_key) AS title
                            FROM media_items mi
                            LEFT JOIN metadata m ON m.media_item_id = mi.id
                           WHERE mi.id = ANY(?)"
                         (id-array ids)]
                        unqualified)
         (into {} (map (juxt :id :title))))))

(defn- set-missing! [tx ids missing-since state]
  (when (seq ids)
    (jdbc/execute-one! tx
                       ["UPDATE media_items SET state = ?, missing_since = ? WHERE id = ANY(?)"
                        (sql-util/->pg-enum "media_item_state" state)
                        missing-since
                        (id-array ids)])))

(defn- delete-items!
  "Deletes the items (children cascade) and every playout event that plays
   them or anything under them. Returns {:events n :playouts #{ids}}."
  [tx ids]
  (if (empty? ids)
    {:events 0 :playouts #{}}
    (let [events (jdbc/execute! tx
                                [(str (subtree-cte)
                                      "DELETE FROM playout_events"
                                      " WHERE media_item_id IN (SELECT id FROM sub)"
                                      " RETURNING playout_id")
                                 (id-array ids)]
                                unqualified)]
      (jdbc/execute-one! tx ["DELETE FROM media_items WHERE id = ANY(?)" (id-array ids)])
      {:events   (count events)
       :playouts (into (sorted-set) (map :playout-id) events)})))

(defn- sample
  "Up to 20 titles, for the report."
  [title-map ids]
  (->> ids (map #(get title-map % (str "#" %))) (take 20) vec))

(defn prune-library-path!
  "Applies pruning for one library path after a complete listing.

   `seen` is the set of Jellyfin item ids the scan listed. `opts` merges over
   `default-opts`; pass `:force? true` to skip the count/fraction guard (never
   the completeness check, which is the caller's job). `:now` may be passed
   for tests.

   Returns a report map for the scan result."
  [ds library-path-id seen opts]
  (let [now   (or (:now opts) (Instant/now))
        known (known-items ds library-path-id)
        p     (plan known seen now opts)]
    (when (:refused p)
      (log/warn "Refusing to prune library; nothing marked or deleted"
                {:library-path-id library-path-id
                 :reason          (:refused p)
                 :missing         (:missing p)
                 :known           (count known)}))
    (jdbc/with-transaction [tx ds]
      (set-missing! tx (:restore p) nil "normal")
      (set-missing! tx (:mark p) (java.sql.Timestamp/from now) "unavailable")
      (let [blocked   (blocked-roots tx (:delete p))
            deletable (vec (remove blocked (:delete p)))
            names     (titles tx (concat (:restore p) (:mark p) (:delete p)))
            removed   (delete-items! tx deletable)
            report    {:status          (if (:refused p) "refused" "ok")
                       :reason          (:refused p)
                       :known           (count known)
                       :listed          (count seen)
                       :missing         (:missing p)
                       :restored        (count (:restore p))
                       :marked          (count (:mark p))
                       :waiting         (count (:waiting p))
                       :deleted         (count deletable)
                       :blocked         (count blocked)
                       :events-deleted  (:events removed)
                       :playouts        (vec (:playouts removed))
                       :restored-titles (sample names (:restore p))
                       :marked-titles   (sample names (:mark p))
                       :deleted-titles  (sample names deletable)
                       :blocked-titles  (mapv (fn [[id why]] (str (get names id (str "#" id)) " (" why ")"))
                                              (take 20 blocked))}]
        (doseq [id (:restore p)]
          (log/info "Prune: item is back in Jellyfin, restored" {:id id :title (names id)}))
        (doseq [id (:mark p)]
          (log/info "Prune: item missing from Jellyfin, hidden until confirmed" {:id id :title (names id)}))
        (doseq [id deletable]
          (log/info "Prune: item still missing after the grace period, deleted" {:id id :title (names id)}))
        (doseq [[id why] blocked]
          (log/warn "Prune: missing item not deleted" {:id id :title (names id) :reason why}))
        (when (some pos? ((juxt :restored :marked :deleted :blocked) report))
          (log/info "Prune complete"
                    (assoc (dissoc report :restored-titles :marked-titles
                                   :deleted-titles :blocked-titles)
                           :library-path-id library-path-id)))
        report))))

(defn list-missing
  "Items in a library that are currently marked missing, oldest first."
  [ds library-id]
  (jdbc/execute! ds
                 ["SELECT mi.id, mi.kind::text AS kind, mi.remote_key, mi.missing_since, m.title
                     FROM media_items mi
                     JOIN library_paths lp ON lp.id = mi.library_path_id
                     LEFT JOIN metadata m ON m.media_item_id = mi.id
                    WHERE lp.library_id = ? AND mi.missing_since IS NOT NULL
                    ORDER BY mi.missing_since, mi.id"
                  library-id]
                 unqualified))

(defn summarize
  "One line for logs and job results."
  [{:keys [status reason marked deleted restored blocked]}]
  (if (= status "refused")
    (str "prune refused: " reason)
    (str/join ", " [(str marked " newly missing") (str deleted " deleted")
                    (str restored " restored") (str blocked " blocked")])))
