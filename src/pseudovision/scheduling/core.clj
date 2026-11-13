(ns pseudovision.scheduling.core
  "Playout build engine.

   Entry points:
     (build! db opts playout)     — core engine. Resume/extend by default
                                    (preserving the near term); pass
                                    :reset-cursor? true for a full rebuild.
     (rebuild-from-now! …)        — reset: wipe the future and regenerate from now
     (rebuild-horizon! …)         — extend the timeline forward to a horizon
     (ensure-horizon! …)          — daily top-up for one channel (extend)
     (ensure-all-horizons! …)     — daily top-up across every schedule-backed channel

   The engine walks the schedule's slots in order, advancing a cursor
   through the content collections and emitting playout_events rows.

   Slot fill modes:
     :once  — emit one item then advance to next slot
     :count — emit exactly N items then advance
     :block — fill a fixed duration, pad tail with filler, then advance
     :flood — fill until the next fixed-anchor slot, then advance

   Slot anchors:
     :fixed      — slot starts at a specific wall-clock time of day
     :sequential — slot starts immediately when the previous one ends"
  (:require [next.jdbc                   :as jdbc]
            [honey.sql                 :as sql]
            [honey.sql.helpers         :as h]
            [pseudovision.db.core      :as db-core]
            [pseudovision.db.channels  :as channels-db]
            [pseudovision.db.filler    :as filler-db]
            [pseudovision.db.media     :as media-db]
            [pseudovision.db.playouts  :as playout-db]
            [pseudovision.db.schedules :as schedules-db]
            [pseudovision.db.collections :as col-db]
            [pseudovision.media.grout        :as grout]
            [pseudovision.media.grout-source :as grout-source]
            [pseudovision.scheduling.cursor      :as cursor]
            [pseudovision.scheduling.enumerators :as enum]
            [pseudovision.scheduling.occurrences :as occ]
            [pseudovision.scheduling.filler      :as filler]
            [pseudovision.scheduling.packing     :as packing]
            [pseudovision.scheduling.validate    :as validate]
            [pseudovision.util.sql  :as sql-util]
            [pseudovision.util.time :as t]
            [taoensso.timbre        :as log])
  (:import [java.time Duration Instant]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- collection-key
  "Returns a stable string key identifying the content source for a slot.
   Used to key enumerator states in the cursor so each slot's position is
   tracked independently.  Channel-catalog slots (no collection-id, no
   media-item-id) get a key scoped to the channel so two different channels
   that both fall back to the channel-catalog path maintain separate
   enumerator positions."
  [slot channel]
  (cond
    (:schedule-slots/collection-id slot)
    (str "collection:" (:schedule-slots/collection-id slot))
    (:schedule-slots/media-item-id slot)
    (str "item:" (:schedule-slots/media-item-id slot))
    :else
    (str "channel-catalog:" (or (:channels/id channel) "unknown"))))

(defn- filter-by-tags
  "Keeps the items that carry ALL of `required-tags` and NONE of
   `excluded-tags`. An item's tags include those inherited from its season and
   show (`media-db/effective-tags-by-item`): channel and category tags are
   written on the show, so checking only an episode's own metadata stripped
   every episode from a `channel:<slug>`-filtered slot."
  [db items required-tags excluded-tags]
  (let [tag-map (media-db/effective-tags-by-item db (keep :media-items/id items))]
    (filterv (fn [item]
               (let [item-tags (get tag-map (:media-items/id item) #{})]
                 (and (every? #(contains? item-tags %) required-tags)
                      (not-any? #(contains? item-tags %) excluded-tags))))
             items)))

(defn- distinct-items
  "`items` with repeats of the same media item dropped, first one kept. A
   pool is a set of things to play; an item listed twice (two versions, or a
   collection that reaches it twice) aired twice as often, and a short
   duplicate version could fill every gap on its own."
  [items]
  (let [seen (volatile! #{})]
    (filterv (fn [item]
               (let [id (:media-items/id item)]
                 (cond (nil? id)            true
                       (contains? @seen id) false
                       :else                (do (vswap! seen conj id) true))))
             items)))

(defn- playable?
  "True when an item has a known, positive duration.

   Media that has not been probed yet carries a zero duration
   (media_versions.duration defaults to INTERVAL '0'), and a slot whose item
   has no media_versions row at all resolves to a nil duration. Either way the
   item has no playable length: emitting it would not advance the build's
   wall-clock cursor, and because the collection enumerators loop forever the
   block/flood fill loops would never terminate (a silent infinite loop). Such
   items are therefore excluded from scheduling — mirroring the filler
   bin-packer, which already skips non-positive-duration items."
  [item]
  (when-let [^Duration d (:media-versions/duration item)]
    (pos? (.getSeconds d))))

(def ^:private min-program-seconds
  "Shortest runtime a movie or episode can plausibly have. Anything shorter is
   a failed probe on a real file, not a real program: the 2026-11-10 audit
   found The Godfather Part II probed at 1 second (and aired 769 two-minute
   blips on Spotlight in a week), Key Largo at 15 s, Brave at 43 s, Zootopia
   at 53 s. The floor is deliberately low so short films stored as movies
   (a few minutes long) still play."
  60)

(defn- broken-probe?
  "True when a movie or episode carries a positive but implausibly short
   duration (see `min-program-seconds`). Other kinds (Grout programs, music
   videos, trailers, clips) are legitimately short and are never flagged."
  [item]
  (let [^Duration d (:media-versions/duration item)
        kind        (some-> (:media-items/kind item) name)]
    (boolean
      (and d
           (#{"movie" "episode"} kind)
           (pos? (.getSeconds d))
           (< (.getSeconds d) min-program-seconds)))))

(defn- load-items
  "Returns the ordered seq of playable media items for a slot.
   Filters by required/excluded tags if specified, and drops items with no
   usable (positive) duration so they can't stall the build.

   Resolution paths, in order:
     1. `:schedule-slots/collection-id` — the slot is pinned to a specific
        collection, resolved through `col-db/resolve-collection`.
     2. `:schedule-slots/media-item-id` — the slot is pinned to one media
        item, fetched directly.
     3. Channel-catalog fallback (the new path) — when a slot has neither a
        collection-id nor a media-item-id, the schedule is implicitly asking
        for anything tagged for this channel.  The slot is resolved against
        the channel's `channel:<slug>` dimension tag through
        `media-db/resolve-playable-by-channel-tag`.  The lookup uses
        `:channels/slug`, NOT `:channels/name`, because the production
        `metadata_tags` namespace stores curated lowercase slugs (e.g.
        `channel:spectrum` for the channel whose display name is
        'Sitcom Spectrum') — the display-name form returned zero events
        on every rebuild for the four (auto) channels (verified live
        2026-07-17; see the #145 regression notes).  If `:channels/slug`
        is nil, this branch THROWS an `ex-info` carrying `:channel-id`,
        `:channel-name`, and a `:fix` hint rather than silently returning
        an empty vector.  Loud error is the contract — the pre-fix code's
        silent zero-event build was the bug we just fixed; if the data
        layer breaks again, the deploy log shows it immediately.  This is
        the contract for (auto) schedules whose slots carry no explicit
        content source — the pre-fix `:else []` branch silently returned
        an empty vector and every such slot produced zero events (see
        pitfall-51 in the pseudovision-ecosystem-development skill).  A
        slot whose `required-tags` filter strips every catalog item is
        still legitimately empty and produces zero events — the fallback
        only adds the resolution path, not content."
  [db slot channel]
  (let [required-tags (or (:schedule-slots/required-tags slot) [])
        excluded-tags (or (:schedule-slots/excluded-tags slot) [])
        slot-id       (:schedule-slots/id slot)
        items (cond
                (:schedule-slots/collection-id slot)
                (let [collection-id (:schedule-slots/collection-id slot)
                      coll (media-db/get-collection db collection-id)]
                  (log/info "Loading collection for slot"
                           {:slot-id slot-id
                            :collection-id collection-id
                            :collection-name (:collections/name coll)
                            :collection-kind (:collections/kind coll)})
                  (col-db/resolve-collection db coll))

                (:schedule-slots/media-item-id slot)
                (let [media-id (:schedule-slots/media-item-id slot)
                      item (media-db/get-playable-item db media-id)]
                  (log/info "Loading single media item for slot"
                           {:slot-id slot-id
                            :media-id media-id
                            :media-name (when item (:media-items/name item))})
                  (if item [item] []))

                :else
                ;; Channel-catalog fallback — see `load-items` docstring.
                ;; Reads the *slug* field (NOT the display name) because the
                ;; production tags are stored as curated slugs (e.g.
                ;; `channel:spectrum` for "Sitcom Spectrum"); using the
                ;; display name returned 0 items on every rebuild for the
                ;; four (auto) channels (verified live 2026-07-17 from the
                ;; PV pod logs).  There is intentionally no fallback to the
                ;; display name — if a channel has no slug, this branch
                ;; throws loud so the operator fixes the data instead of
                ;; letting the build silently emit zero events again.
                ;;
                ;; Test fixtures and dev scripts sometimes pass `{}` or
                ;; `nil` as the channel; those are "no channel in scope"
                ;; cases, not the data-layer bug PR #145 was created to
                ;; catch.  Both are treated as "no fallback available"
                ;; and the function returns [] silently — the pre-fix
                ;; contract for those callers.
                (cond
                  ;; No channel object: there is no slug to look up.
                  (or (nil? channel) (nil? (:channels/id channel)))
                  (do (log/warn "Slot has no content source and no channel; emitting nothing"
                                {:slot-id slot-id})
                      [])

                  ;; Channel object provided but lacks the curated slug:
                  ;; this is the data-layer bug; refuse to silently emit
                  ;; zero events and throw so deploy logs make it obvious.
                  (nil? (:channels/slug channel))
                  (do (log/error "Slot is on an auto schedule but its channel has no slug — refusing to silently emit zero events. Set channels.slug for this channel and rerun the rebuild."
                                {:slot-id slot-id
                                 :channel-id (:channels/id channel)
                                 :channel-name (:channels/name channel)})
                      (throw (ex-info "channel has no slug; cannot run channel-catalog fallback"
                                      {:slot-id slot-id
                                       :channel-id (:channels/id channel)
                                       :channel-name (:channels/name channel)
                                       :fix "SET channels.slug = '<curated-slug>' WHERE id = <channel-id>"})))

                  :else
                  (let [slug       (:channels/slug channel)
                        channel-tag (str "channel:" slug)]
                    (log/info "Loading channel-catalog items for slot"
                              {:slot-id slot-id
                               :channel-id (:channels/id channel)
                               :channel-slug slug
                               :channel-tag channel-tag})
                    (media-db/resolve-playable-by-channel-tag db channel-tag))))
        ;; Apply tag filters if specified
        tagged (if (or (seq required-tags) (seq excluded-tags))
                 (do
                   (log/info "Filtering items by tags"
                            {:slot-id slot-id
                             :required required-tags
                             :excluded excluded-tags
                             :before-count (count items)})
                   (let [filtered (filter-by-tags db (vec items) required-tags excluded-tags)]
                     (log/info "Items after tag filtering"
                              {:slot-id slot-id
                               :after-count (count filtered)
                               :filtered-out (- (count items) (count filtered))})
                     filtered))
                 (vec items))
        active   (filterv media-db/active? tagged)
        _        (when (< (count active) (count tagged))
                   (log/info "Skipping items marked missing from their media source"
                             {:slot-id slot-id
                              :dropped (- (count tagged) (count active))}))
        playable (distinct-items (filterv playable? active))
        ;; Pools (collections and the channel catalog) drop movies/episodes
        ;; whose probe failed. A slot pinned to one item is a deliberate
        ;; choice and is left alone.
        pooled?  (not (:schedule-slots/media-item-id slot))
        broken   (when pooled? (filterv broken-probe? playable))
        playable (if (seq broken)
                   (filterv (complement broken-probe?) playable)
                   playable)]
    (when (< (+ (count playable) (count broken)) (count active))
      (log/warn "Skipping unplayable items with zero/unknown duration"
                {:slot-id slot-id
                 :dropped (- (count active) (count playable) (count broken))}))
    (when (seq broken)
      (log/warn "Skipping movies/episodes with an implausibly short probed duration (failed probe?); rescan these files"
                {:slot-id slot-id
                 :dropped (count broken)
                 :items   (mapv (fn [i] {:id       (:media-items/id i)
                                         :name     (:media-items/name i)
                                         :duration (str (:media-versions/duration i))})
                                (take 10 broken))}))
    (log/info "Loaded items for slot (final)"
             {:slot-id slot-id
              :count (count playable)})
    playable))

(defn- memoized
  "Looks `k` up in the per-build cache atom `cache`, calling `(f)` and storing
   its result on a miss. Without a cache (unit tests calling the emitters
   directly) it just calls `(f)`. A throw is not cached."
  [cache k f]
  (if cache
    (if-let [hit (find @cache k)]
      (val hit)
      (let [v (f)]
        (swap! cache assoc k v)
        v))
    (f)))

(defn- slot-items
  "load-items, cached for the length of one build (opts :items-cache).

   A day-based build visits every slot once per day it airs, and each visit
   used to resolve its collection from scratch: a smart category collection is
   a multi-join query over every tagged show, plus a tag lookup for every
   candidate item when the slot has tag filters. Fourteen days of a dozen
   strips repeated that a couple of hundred times with identical results,
   since nothing a build does changes collection membership. The cache key is
   the slot's content source and tag filters, not the slot row itself, because
   override clipping hands the emitters copies of a slot with a different
   block duration."
  [db slot channel opts]
  (let [coll-id (:schedule-slots/collection-id slot)
        item-id (:schedule-slots/media-item-id slot)
        k       [coll-id item-id
                 (when-not (or coll-id item-id) (:channels/id channel))
                 (vec (:schedule-slots/required-tags slot))
                 (vec (:schedule-slots/excluded-tags slot))]]
    (memoized (:items-cache opts) k #(load-items db slot channel))))

(defn- item-duration
  "Returns the item's playback duration, or zero if the item has not been probed."
  [item]
  (or (some-> (:media-versions/duration item))
      (Duration/ofSeconds 0)))

(defn- events-end
  "Returns the finish time of the last event in `events`, or `default` when
   `events` is empty.  Used to advance the cursor past injected filler."
  [events default]
  (if (seq events)
    (:finish-at (last events))
    default))

(defn- point-filler-window
  "For an inline (pre/mid/post) filler insert starting at `from`, returns the
   instant the fill should run until.  Unlike gap filler (tail/fallback), the
   length of an inline insert is dictated by the preset, not by an external
   deadline:
     - duration mode      → from + preset duration
     - pad_to_minute mode → next n-minute boundary
     - count modes        → `ceil` itself (nil when there is none); fill-gap
                            stops after N items or at `ceil`, whichever is
                            first
   `ceil` (may be nil) caps the window so the insert can't overrun the
   surrounding block."
  [preset from ceil]
  (let [target (case (:filler-presets/mode preset)
                 "duration"      (if-let [d (:filler-presets/duration preset)]
                                   (t/add-duration from d)
                                   (or ceil from))
                 "pad_to_minute" (if-let [n (:filler-presets/pad-to-nearest-minute preset)]
                                   (t/ceil-to-n-minutes from n)
                                   (or ceil from))
                 ;; count / random_count: fill-gap counts items, stopping
                 ;; early at the ceiling if there is one.
                 ceil)]
    (if (and ceil target (.isAfter ^Instant target ^Instant ceil)) ceil target)))

(defn- lay-out-filler
  "Turns an ordered seq of filler items into back-to-back event maps starting at
   `from`.  Stamps guide-group / slot-id so the rows share the content event
   column set (guide_group is NOT NULL)."
  [from items role playout-id guide slot-id]
  (loop [t from, items (seq items), events []]
    (if (empty? items)
      events
      (let [it  (first items)
            dur (or (:media-versions/duration it) (Duration/ofSeconds 0))
            fin (t/add-duration t dur)]
        (recur fin (rest items)
               (conj events {:playout-id    playout-id
                             :media-item-id (:media-items/id it)
                             :kind          (sql-util/->pg-enum "event_kind" (name role))
                             :start-at      t
                             :finish-at     fin
                             :guide-group   guide
                             :slot-id       slot-id
                             :is-manual     false}))))))

(def ^:private generic-filler-penalty
  "Recency-style penalty on Grout clips that aren't mapped to any channel.
   Grout hands those to every channel, so without it a sitcom channel's breaks
   drew a science talk show as readily as its own clips. With it the packer
   takes a channel clip over a generic one that fills the gap as well, until
   the channel clip has aired about twice within the recency window. Generic
   clips still fill gaps that nothing channel-specific fits as tightly."
  2.0)

(defn- generic-penalties
  "item-id -> `generic-filler-penalty` for the generic Grout clips in `items`."
  [items]
  (into {}
        (keep (fn [it]
                (when (:grout/generic? it)
                  [(:media-items/id it) generic-filler-penalty])))
        items))

(defn- pack-filler
  "Variety-oriented fill of [from, until] using the bin-packer.  Selection is
   biased away from items that aired recently anywhere (global recency, carried
   in opts as :filler-airings-atom + :recency-window) and seeded per-gap so
   different breaks differ while rebuilds stay stable.  Records the chosen
   airings back into the recency atom so later gaps in this build space away
   from them too.  Returns [events cursor] (the packer keeps no enumerator
   state, so the cursor is returned unchanged)."
  [cursor slot role from until items playout-id opts]
  (let [airings-atom (:filler-airings-atom opts)
        window       (:recency-window opts)
        recency      (merge-with +
                               (if (and airings-atom window)
                                 (packing/airing-penalties @airings-atom from window)
                                 {})
                               (generic-penalties items))
        seed         (bit-xor (long (get opts :seed 0)) (.getEpochSecond ^Instant from))
        target       (t/duration-between from until)
        picked       (packing/pack target items
                                   :seed seed
                                   :recency recency
                                   :tolerance (:filler-tolerance opts))
        events       (lay-out-filler from picked role playout-id
                                     (:next-guide-group cursor)
                                     (:schedule-slots/id slot))]
    (when (and airings-atom window)
      (swap! airings-atom
             (fn [m] (reduce (fn [acc ev]
                               (update acc (:media-item-id ev) (fnil conj []) (:finish-at ev)))
                             m events))))
    [events cursor]))

(defn- grout-candidates
  "Grout filler candidates for the gap [from, to] on `channel`.

   Inside a build (opts :grout-pools set) the channel's whole pool for the
   preset's tags is fetched once and cached, then cut down to the clips no
   longer than the gap, which is what Grout's max_ms did per request. Outside a
   build it asks Grout directly, as before."
  [db opts channel from to preset]
  (if-let [pools (:grout-pools opts)]
    (let [pool   (memoized pools [:filler (:channels/id channel)
                                  (vec (:filler-presets/grout-tags preset))]
                           #(grout-source/grout-filler-pool db (:grout opts) channel preset))
          gap-ms (when (and from to) (.toMillis (Duration/between ^Instant from ^Instant to)))]
      (if (and gap-ms (pos? gap-ms))
        (filterv #(<= (.toMillis ^Duration (item-duration %)) gap-ms) pool)
        pool))
    (grout-source/grout-filler-items db (:grout opts) channel from to preset)))

(defn- bumper-candidates-fn
  "The bumper lookup handed to filler/fill-gap-with-bumper: inside a build, a
   filter over the channel's cached bumper pool; otherwise nil (fill-gap-with-
   bumper then asks Grout itself)."
  [db opts channel]
  (when-let [pools (:grout-pools opts)]
    (fn [min-ms max-ms]
      (filterv #(<= min-ms (.toMillis ^Duration (item-duration %)) max-ms)
               (memoized pools [:bumper (:channels/id channel)]
                         #(grout-source/grout-bumper-pool db (:grout opts) channel))))))

(defn- grout-channel-fallback
  "Last-resort gap fill for a `role` that has NO filler preset configured on the
   slot or channel.  Rather than leave dead air, pull channel-mapped clips
   straight from Grout — any media Grout has tagged for this channel that fits
   the gap [from, to] — and pack the window with them.

   This is the \"filler mapped to channel\" contract: Grout already scopes filler
   by channel, so a PV channel doesn't need a per-channel filler collection or a
   `fallback_filler_id` wired up for its gaps to fill.  A synthetic empty preset
   (no grout-tags) makes the query channel + gap-duration only, i.e. \"any clip
   for this channel that fits.\"

   Best-effort: returns [[] cursor] (a genuine gap, exactly as before) when Grout
   is disabled/unreachable, the window is empty, or Grout returns nothing."
  [db cursor slot channel role from to playout-id opts]
  (let [grout (:grout opts)]
    (if (and to
             (grout/enabled? grout)
             (.isBefore ^Instant from ^Instant to))
      (let [items (filterv playable? (grout-candidates db opts channel from to {}))]
        (if (seq items)
          (do (log/info "Filling gap with channel-mapped Grout filler (no preset configured)"
                        {:slot-id (:schedule-slots/id slot)
                         :channel (:channels/name channel)
                         :role    role
                         :candidates (count items)})
              (pack-filler cursor slot role from to items playout-id opts))
          [[] cursor]))
      [[] cursor])))

(defn- unconfigured-filler
  "Filler for a `role` with no preset on the slot or channel. The gap-filling
   roles (:tail, :fallback) fall back to channel-mapped Grout filler so the gap
   doesn't become dead air. Inline roles (:pre, :mid, :post) emit nothing: they
   decorate content, they don't pad dead air.

   Post-roll used to take the Grout fallback too. Every caller asks for
   post-roll first and tail filler right after it, so post-roll took the whole
   remainder of each block and tail got nothing. A random block keeps post-roll
   next to the last program and scatters only the tail, so on channels without
   presets (every channel Tunarr Scheduler syncs) its leftover could never be
   spread between programs."
  [db cursor slot channel role from to playout-id opts]
  (if (#{:tail :fallback} role)
    (grout-channel-fallback db cursor slot channel role from to playout-id opts)
    [[] cursor]))

(defn- apply-filler*
  "Resolves the filler preset for `role`, loads its items, emits filler events,
   and returns [filler-events updated-cursor].  When no preset is configured for
   the role on this slot/channel, gap-filling roles (:tail, :fallback)
   fall back to channel-mapped Grout filler (see grout-channel-fallback); other
   roles, or a disabled/empty Grout, yield [[] cursor] (a genuine gap).

   The fill window depends on the role:
     - gap roles (:tail, :fallback) fill the interval [from, to] exactly.
     - inline roles (:pre, :mid, :post) fill a preset-sized chunk starting at
       `from`, with `to` acting as an upper bound (it may be nil for slots that
       have no natural deadline, e.g. count-mode slots).

   For duration-mode presets, when packing is enabled (opts :pack-filler?, set
   by build!), the gap is bin-packed for variety; otherwise the original
   cursor-managed enumerator fill is used (count mode always uses it).

   Small tail gaps (≤ 15 seconds) are preferentially filled with auto-generated
   bumper items sourced from Grout, when a suitable one exists for the
   channel."
  [db cursor slot channel role from to playout-id opts]
  (or
   ;; --- Bumper priority for small tail gaps ---
   ;; `or`'d with the regular-filler branch below so a real [events cursor]
   ;; short-circuits it; a nil (no small gap, or Grout had nothing in range)
   ;; falls through to regular filler.
   (when (and (= role :tail) to)
     (when-let [bumper-events (filler/fill-gap-with-bumper
                               db (:grout opts) channel from to playout-id
                               (bumper-candidates-fn db opts channel))]
       (let [guide   (:next-guide-group cursor)
             slot-id (:schedule-slots/id slot)
             events  (mapv #(assoc % :guide-group guide :slot-id slot-id)
                           bumper-events)]
         (log/debug "Injected bumper for small gap"
                    {:channel (:channels/name channel)
                     :gap-secs (t/duration->seconds (t/duration-between from to))
                     :count (count events)})
         [events cursor])))
   ;; --- Regular filler ---
   (if-let [preset-ref (filler/resolve-filler-preset role slot channel)]
     (if-let [preset (filler-db/get-filler-preset db (:id preset-ref))]
       ;; Drop unplayable (zero/unknown duration) filler items up front so the
       ;; enumerator and fill-gap below share the same item vector and the
       ;; duration-mode fill loop can't spin on a zero-length item.
       (let [until (if (#{:pre :mid :post} role)
                     (point-filler-window preset from to)
                     to)
             ;; A preset carrying grout-tags draws its candidates from Grout
             ;; (queried by channel + tags + the gap duration), ingested on the
             ;; fly as local-path media items; otherwise use its local collection
             ;; / media item.  Both paths yield the same candidate shape, so the
             ;; packer / enumerator below are unchanged.
             items (filterv playable?
                            (if (seq (:filler-presets/grout-tags preset))
                              (grout-candidates db opts channel from until preset)
                              (filler-db/load-filler-items db preset)))]
         (if (and (:pack-filler? opts)
                  (= "duration" (:filler-presets/mode preset)))
           (pack-filler cursor slot role from until items playout-id opts)
           (let [ckey    (str "filler:" (name role) ":" (:filler-presets/id preset))
                 e       (cursor/get-enumerator cursor ckey items :random {:seed (get opts :seed 0)})
                 result  (filler/fill-gap from until preset items e playout-id)
                 ;; fill-gap emits the bare content columns; stamp the same
                 ;; guide-group / slot-id that content events carry so every row
                 ;; bulk-inserted in build! has an identical column set.
                 guide   (:next-guide-group cursor)
                 slot-id (:schedule-slots/id slot)
                 events  (mapv #(assoc % :guide-group guide :slot-id slot-id)
                               (:events result))
                 cursor' (cursor/save-enumerator cursor ckey (:enumerator result))]
             [events cursor'])))
       ;; preset-ref resolved but the row vanished — treat as unconfigured.
       (unconfigured-filler db cursor slot channel role from to playout-id opts))
     (unconfigured-filler db cursor slot channel role from to playout-id opts))))

(defn- apply-filler
  "apply-filler*, with the filler cut so it never runs past `to` (when there
   is one). Every caller moves the cursor to `to` afterwards whatever the
   filler's length, so filler that ran long used to overlap the next program:
   a count-mode preset ignored the gap, the packer rounded clip lengths down to
   whole seconds, and a bumper could be a second longer than its gap. Clamping
   here covers every filler path at once."
  [db cursor slot channel role from to playout-id opts]
  (let [[events cursor'] (apply-filler* db cursor slot channel role from to playout-id opts)
        clamped          (validate/clamp-to events to)]
    (when (not= (count clamped) (count events))
      (log/debug "Dropped filler that started past its gap"
                 {:role role :dropped (- (count events) (count clamped))}))
    [clamped cursor']))

(defn- next-fixed-start
  "Returns the next wall-clock Instant when `slot` would fire on or after `after`.
   Respects the slot's days_of_week bitmask: skips days whose bit is not set.
   A nil or 0 mask means every day (preserves original behaviour)."
  [slot after zone-id]
  (let [tod      (:schedule-slots/start-time slot)   ; Duration = offset from midnight
        dow-mask (:schedule-slots/days-of-week slot)] ; nil → every day
    (t/next-dow-occurrence tod dow-mask after zone-id)))

;; ---------------------------------------------------------------------------
;; Random blocks: draw content (searching only after a few misses), then scatter filler
;; ---------------------------------------------------------------------------

(def ^:private random-max-misses
  "How many draws in a row that don't fit the time left a random block makes
   before it stops drawing and searches the pool for something that fits.

   Drawing keeps the choice random, so a short item is picked about as often
   as its share of the pool. The search after the misses fills the time a
   mixed pool would otherwise hand to filler (three movies in a row after an
   episode left most of a two-hour block to filler). The search used to be
   what made a 2-minute trailer air in every gap; the repeat window now keeps
   anything it finds from coming back within `effective-repeat-window`."
  3)

(def default-content-repeat-window
  "How long an item picked by a random block stays ineligible on the same
   channel, unless the build options give :content-repeat-window. It is capped
   per pool (see effective-repeat-window)."
  (t/hours->duration 48))

(defn- content-key
  "The id an item's airings are recorded under."
  [item]
  (:media-items/id item))

(defn effective-repeat-window
  "The repeat window for a pool: the configured `window`, capped at half the
   pool's total runtime. A small pool (a 5-episode random strip) would
   otherwise run out of eligible items within a day and turn into filler; the
   cap lets it rotate as often as its size allows, while a large pool gets the
   full window."
  ^Duration [^Duration window items]
  (let [half (.dividedBy ^Duration (reduce (fn [^Duration acc it] (.plus acc ^Duration (item-duration it)))
                                           Duration/ZERO items)
                         2)]
    (if (neg? (compare half window)) half window)))

(defn- aired-within?
  "Whether `airings` (a seq of Instants) holds one within `window` of `at`."
  [airings ^Instant at ^Duration window]
  (let [w (.toMillis window)]
    (boolean (some (fn [^Instant a]
                     (< (Math/abs (- (.toEpochMilli a) (.toEpochMilli at))) w))
                   airings))))

(defn- search-fit
  "An item from the pool of `e` that fits [t, end) and is off cooldown, picked
   at random (seeded by `t`, so rebuilds repeat it), or nil when none does.
   Looks at the pool directly, so the enumerator's pass order is untouched."
  [e ^Instant t ^Instant end airings window]
  (let [fits (filterv (fn [item]
                        (and (not (.isAfter ^Instant (t/add-duration t (item-duration item)) end))
                             (not (and window
                                       (aired-within? (get airings (content-key item)) t window)))))
                      (:items e))]
    (when (seq fits)
      (nth fits (.nextInt (java.util.Random. (.toEpochMilli t)) (count fits))))))

(defn plan-random-content
  "Chooses the content for a random-order window [from, end).

   Draws from the enumerator in order and keeps a draw when it fits the time
   left and hasn't aired within `window` of where it would start (`airings`:
   item-id -> start Instants, this build's earlier picks included). A draw on
   cooldown is passed over. A draw that doesn't fit counts as a miss; after
   `max-misses` misses in a row the pool is searched for an item that fits and
   is off cooldown (see `search-fit`), and the plan stops, leaving the rest of
   the window to filler, only when there is none. Until the first item is
   placed every draw is tried, so the whole pool is walked once before a block
   is left all filler. A full pool's worth of draws without a pick also stops
   it.

   Returns {:items [item ...] :enumerator e' :end Instant}, where :end is where
   the planned content finishes."
  [e ^Instant from ^Instant end {:keys [airings window max-misses]
                                 :or   {airings {} max-misses random-max-misses}}]
  (let [pool-n (count (:items e))]
    (loop [t       from
           e       e
           drawn   0
           misses  0
           airings airings
           picked  []]
      (cond
        (or (not (.isBefore t end))
            (zero? pool-n)
            (>= drawn pool-n))
        {:items picked :enumerator e :end t}

        (and (seq picked) (>= misses max-misses))
        (if-let [item (search-fit e t end airings window)]
          (recur (t/add-duration t (item-duration item)) e 0 0
                 (update airings (content-key item) (fnil conj []) t)
                 (conj picked item))
          {:items picked :enumerator e :end t})

        :else
        (let [[item e'] (enum/next-item e)
              to        (t/add-duration t (item-duration item))]
          (cond
            (and window (aired-within? (get airings (content-key item)) t window))
            (recur t e' (inc drawn) misses airings picked)

            (.isAfter ^Instant to end)
            (recur t e' (inc drawn) (inc misses) airings picked)

            :else
            (recur to e' 0 0
                   (update airings (content-key item) (fnil conj []) t)
                   (conj picked item))))))))

(defn- event-duration
  ^Duration [ev]
  (Duration/between ^Instant (:start-at ev) ^Instant (:finish-at ev)))

(defn retime-events
  "Lays `events` out back to back from `from`, keeping each one's length."
  [^Instant from events]
  (loop [t from, evs (seq events), out []]
    (if-not evs
      out
      (let [ev  (first evs)
            fin (t/add-duration t (event-duration ev))]
        (recur fin (next evs) (conj out (assoc ev :start-at t :finish-at fin)))))))

(defn scatter-filler
  "Spreads `filler` events at random across the breaks after each of the
   `content` events, so a random block's leftover time becomes several short
   breaks between programs rather than one long break at the end. `trailing`
   events (post-roll) stay right after the last content event. The block still
   opens on content. Filler keeps its packed order within a break. Seeded, so
   rebuilds reproduce the same layout. Returns the events retimed from `from`."
  [^Instant from content trailing filler seed]
  (if (empty? content)
    (retime-events from (concat trailing filler))
    (let [n      (count content)
          rng    (java.util.Random. (long seed))
          groups (reduce (fn [g ev] (update g (.nextInt rng n) conj ev))
                         (vec (repeat n []))
                         filler)
          seq'   (mapcat (fn [i c]
                           (concat [c]
                                   (when (= i (dec n)) trailing)
                                   (nth groups i)))
                         (range) content)]
      (retime-events from seq'))))

(defn- series-in-order?
  "Whether a slot plays one show's episodes in a fixed order: a sequential
   order over a pool whose items all come from the same show (`:_top-id`, set
   by the show and category resolvers). Such a slot must not pass over an
   episode that doesn't fit the time left. Mixed pools (a channel catalog,
   a manual collection) keep passing over what doesn't fit, which is what
   stops one long item from stranding the shorter ones."
  [order items]
  (and (not (#{:random :shuffle} order))
       (seq items)
       (some? (:_top-id (first items)))
       (every? #(= (:_top-id (first items)) (:_top-id %)) items)))

(defn- random-plan?
  "Whether a block/flood slot fills through plan-random-content: random or
   shuffle order, and no mid-roll preset (mid-roll is sized per insert, which
   the up-front plan can't account for)."
  [slot order]
  (and (#{:random :shuffle} order)
       (nil? (filler/resolve-filler-preset :mid slot nil))))

(defn- content-event [playout-id item from to guide slot]
  {:playout-id    playout-id
   :media-item-id (:media-items/id item)
   :kind          (sql-util/->pg-enum "event_kind" "content")
   :start-at      from
   :finish-at     to
   :guide-group   guide
   :slot-id       (:schedule-slots/id slot)
   :is-manual     false})

(defn- emit-random-window
  "Fills [from, end) for a random-order block or flood slot: content from
   plan-random-content, then filler for the time left, scattered between the
   programs. `mode` is :block (post + tail filler, honouring tail_mode) or
   :flood (fallback filler). `lead-events` (pre-roll) come first and `cur` is
   the cursor after them. Returns [events cursor]; the caller sets :next-start
   and the guide group."
  [db cur slot channel playout-id opts mode from end lead-events]
  (let [items     (slot-items db slot channel opts)
        ckey      (collection-key slot channel)
        order     (keyword (or (:schedule-slots/playback-order slot) "chronological"))
        e         (cursor/get-enumerator cur ckey items order
                                         {:seed (get opts :seed 0)})
        guide     (:next-guide-group cur)
        atom*     (:content-airings-atom opts)
        window    (some-> (get opts :content-repeat-window default-content-repeat-window)
                          (effective-repeat-window items))
        plan      (plan-random-content e from end {:airings (if atom* @atom* {})
                                                   :window  window})
        content   (:items plan)
        e'        (:enumerator plan)
        c-events  (retime-events from
                                 (map (fn [it] (assoc (content-event playout-id it from
                                                                     (t/add-duration from (item-duration it))
                                                                     guide slot)
                                                      ::content true))
                                      content))
        c-end     (:end plan)
        base      (cursor/save-enumerator cur ckey e')
        tail-mode (:schedule-slots/tail-mode slot "none")
        seed      (bit-xor (long (get opts :seed 0)) (.getEpochSecond ^Instant from))
        [events cur']
        (cond
          (not (.isBefore ^Instant c-end ^Instant end))
          [c-events base]

          (= :flood mode)
          (let [[fill c] (apply-filler db base slot channel :fallback c-end end playout-id opts)]
            [(scatter-filler from c-events [] fill seed) c])

          ;; tail_mode none: the next draw runs into the end of the block and
          ;; replays in full next time (enumerator saved unadvanced).
          (and (= "none" tail-mode) (seq items))
          (let [[item _] (enum/next-item e')]
            [(conj c-events (content-event playout-id item c-end end guide slot)) base])

          (= "offline" tail-mode)
          [c-events base]

          :else
          (let [[post c1] (apply-filler db base slot channel :post c-end end playout-id opts)
                post-end  (events-end post c-end)
                [tail c2] (apply-filler db c1 slot channel :tail post-end end playout-id opts)]
            [(scatter-filler from c-events post tail seed) c2]))]
    ;; Record where each program finally landed (after the scatter) so later
    ;; random windows in this build keep their distance from it.
    (when atom*
      (swap! atom* (fn [m] (reduce (fn [acc ev]
                                     (update acc (:media-item-id ev) (fnil conj []) (:start-at ev)))
                                   m (filter ::content events)))))
    (log/info "random window filled"
              {:slot-id        (:schedule-slots/id slot)
               :mode           mode
               :content-events (count content)
               :filler-events  (- (count events) (count content))
               :repeat-window  (str window)})
    [(into (vec lead-events) (map #(dissoc % ::content)) events) cur']))

;; ---------------------------------------------------------------------------
;; Event emission for each fill mode
;; ---------------------------------------------------------------------------

(defn emit-once
  "Emit one item, return [events cursor]."
  [db cursor slot channel playout-id opts]
  (let [items   (slot-items db slot channel opts)
        ckey    (collection-key slot channel)
        order   (keyword (or (:schedule-slots/playback-order slot) "chronological"))
        enum-opts {:seed (get opts :seed 0)
                   :batch-size (or (:schedule-slots/marathon-batch-size slot) 5)}]
    (if (empty? items)
      (do (log/warn "emit-once: no items for slot; skipping"
                    {:slot-id (:schedule-slots/id slot)})
          [[] cursor])
      (let [e         (cursor/get-enumerator cursor ckey items order enum-opts)
            [item e'] (enum/next-item e)
            dur       (item-duration item)
            from      (:next-start cursor)
            to        (t/add-duration from dur)
            event     {:playout-id    playout-id
                       :media-item-id (:media-items/id item)
                       :kind          (sql-util/->pg-enum "event_kind" "content")
                       :start-at      from
                       :finish-at     to
                       :guide-group   (:next-guide-group cursor)
                       :slot-id       (:schedule-slots/id slot)
                       :is-manual     false}
            cursor'   (-> cursor
                          (assoc :next-start to)
                          (cursor/save-enumerator ckey e')
                          (cursor/bump-guide-group))]
        (log/info "emit-once: emitted item"
                 {:slot-id (:schedule-slots/id slot)
                  :media-name (:media-items/name item)
                  :duration (str dur)
                  :start-at (str from)
                  :finish-at (str to)})
        [[event] cursor']))))

(defn emit-count
  "Emit exactly item-count items, return [events cursor].
   Injects pre-roll filler before the first item, mid-roll filler between
   items, and post-roll filler after the last item, when configured.  A count
   slot has no time deadline, so inline filler is sized purely by its preset."
  [db cursor slot channel playout-id opts]
  (let [items  (slot-items db slot channel opts)
        ckey   (collection-key slot channel)
        order  (keyword (or (:schedule-slots/playback-order slot) "chronological"))
        enum-opts {:seed (get opts :seed 0)
                   :batch-size (or (:schedule-slots/marathon-batch-size slot) 5)}
        n      (or (:schedule-slots/item-count slot) 1)
        guide  (:next-guide-group cursor)]
    (if (empty? items)
      (do (log/warn "emit-count: no items for slot; skipping"
                    {:slot-id (:schedule-slots/id slot)})
          [[] cursor])
      (let [start              (:next-start cursor)
            [pre-events cur0]  (apply-filler db cursor slot channel
                                             :pre start nil playout-id opts)
            pre-end            (events-end pre-events start)]
        (loop [i      0
               from   pre-end
               cur    cur0
               e      (cursor/get-enumerator cur0 ckey items order enum-opts)
               events (vec pre-events)]
          (if (>= i n)
            (let [base               (cursor/save-enumerator cur ckey e)
                  [post-events cur1] (apply-filler db base slot channel
                                                   :post from nil playout-id opts)
                  cursor'            (-> cur1
                                         (assoc :next-start (events-end post-events from))
                                         (cursor/bump-guide-group))]
              [(into events post-events) cursor'])
            (let [[item e'] (enum/next-item e)
                  dur       (item-duration item)
                  to        (t/add-duration from dur)
                  content   {:playout-id    playout-id
                             :media-item-id (:media-items/id item)
                             :kind          (sql-util/->pg-enum "event_kind" "content")
                             :start-at      from
                             :finish-at     to
                             :guide-group   guide
                             :slot-id       (:schedule-slots/id slot)
                             :is-manual     false}
                  ;; mid-roll filler sits between items, never after the last.
                  [mid-events curm] (if (< (inc i) n)
                                      (apply-filler db cur slot channel
                                                    :mid to nil playout-id opts)
                                      [[] cur])]
              (recur (inc i) (events-end mid-events to) curm e'
                     (into events (into [content] mid-events))))))))))
(defn emit-block
  "Fill a fixed-duration block.  Injects pre-roll filler before the first item,
   mid-roll filler between items, post-roll filler after the last item, and
   pads any remaining time with tail filler.  Returns [events cursor]."
  [db cursor slot channel playout-id opts]
  (let [items      (slot-items db slot channel opts)
        ckey       (collection-key slot channel)
        order      (keyword (or (:schedule-slots/playback-order slot) "chronological"))
        enum-opts  {:seed (get opts :seed 0)
                    :batch-size (or (:schedule-slots/marathon-batch-size slot) 5)}
        block-dur  (:schedule-slots/block-duration slot)
        series?    (series-in-order? order items)
        from       (:next-start cursor)
        block-end  (t/add-duration from block-dur)
        guide      (:next-guide-group cursor)
        ;; Pre-roll filler fires once, before the first content item, bounded
        ;; by the block end so it can't consume the whole block.
        [pre-events cur0] (apply-filler db cursor slot channel
                                        :pre from block-end playout-id opts)]
    (if (random-plan? slot order)
      (let [[events c] (emit-random-window db cur0 slot channel playout-id opts :block
                                           (events-end pre-events from) block-end
                                           pre-events)]
        [events (-> c (assoc :next-start block-end) (cursor/bump-guide-group))])
      (loop [cursor-time (events-end pre-events from)
             cur         cur0
             e           (cursor/get-enumerator cur0 ckey items order enum-opts)
             skipped     0
             events      (vec pre-events)]
        (let [remaining (t/duration-between cursor-time block-end)]
          (cond
            ;; Block finished
            (or (.isNegative remaining) (.isZero remaining))
            (let [c' (-> cur
                         (assoc :next-start block-end)
                         (cursor/save-enumerator ckey e)
                         (cursor/bump-guide-group))]
              (log/info "emit-block: block filled"
                       {:slot-id (:schedule-slots/id slot)
                        :block-duration (str block-dur)
                        :total-events (count events)
                        :fill-mode "completed"})
              [events c'])

            ;; Either no content at all, or a full pass over the pool found
            ;; nothing short enough to still fit the block. How the remainder is
            ;; handled depends on tail_mode.
            (or (empty? (:items e))
                (>= skipped (count (:items e))))
            (let [tail-mode (:schedule-slots/tail-mode slot "none")]
              (cond
                ;; tail_mode none, with content still waiting: trim the next item
                ;; to fill the block exactly, then replay it in full at the next
                ;; block start (enumerator saved unadvanced).
                (and (= "none" tail-mode) (seq (:items e)))
                (let [[item _] (enum/next-item e)
                      trimmed  {:playout-id    playout-id
                                :media-item-id (:media-items/id item)
                                :kind          (sql-util/->pg-enum "event_kind" "content")
                                :start-at      cursor-time
                                :finish-at     block-end
                                :guide-group   guide
                                :slot-id       (:schedule-slots/id slot)
                                :is-manual     false}
                      c' (-> (cursor/save-enumerator cur ckey e)
                             (assoc :next-start block-end)
                             (cursor/bump-guide-group))]
                  [(conj events trimmed) c'])

                ;; tail_mode offline: leave the remainder dark (no filler).
                (= "offline" tail-mode)
                (let [c' (-> (cursor/save-enumerator cur ckey e)
                             (assoc :next-start block-end)
                             (cursor/bump-guide-group))]
                  [events c'])

                ;; tail_mode filler (or none with nothing left to trim): post-roll
                ;; then pad the rest with tail filler.
                :else
                (let [base-cursor (cursor/save-enumerator cur ckey e)
                      [post-events cur1] (apply-filler db base-cursor slot channel
                                                       :post cursor-time block-end
                                                       playout-id opts)
                      post-end (events-end post-events cursor-time)
                      [tail-events cur2] (apply-filler db cur1 slot channel
                                                       :tail post-end block-end
                                                       playout-id opts)
                      c' (-> cur2
                             (assoc :next-start block-end)
                             (cursor/bump-guide-group))]
                  [(into events (into (vec post-events) tail-events)) c'])))

            :else
            (let [[item e'] (enum/next-item e)
                  dur       (item-duration item)
                  to        (t/add-duration cursor-time dur)]
              (cond
                ;; A series in order: the episode that doesn't fit leads the next
                ;; block instead of being passed over. Skipping it aired the
                ;; series out of order and left a double-length episode unaired
                ;; until the next full pass. End the block here (enumerator
                ;; unadvanced). An episode longer than the whole block can never
                ;; air in it, so that one is passed over.
                (and (.isAfter to block-end)
                     series?
                     (not (pos? (compare dur block-dur))))
                (recur cursor-time cur e (count (:items e)) events)

                (.isAfter to block-end)
                ;; Too long for what's left of the block. Skip past it and keep
                ;; packing shorter content — abandoning the block on the first
                ;; overflow stranded the shorter episodes still in the pool (the
                ;; "one episode then dead air" symptom). The enumerator advances
                ;; (e') so the oversized item comes around on a later block;
                ;; `skipped` bounds the scan to one pool cycle. The final remainder
                ;; is handled per tail_mode in the branch above.
                (recur cursor-time cur e' (inc skipped) events)

                :else
                ;; Item fits — emit it, then inject mid-roll filler before the
                ;; next item (only when more content remains).
                (let [content {:playout-id    playout-id
                               :media-item-id (:media-items/id item)
                               :kind          (sql-util/->pg-enum "event_kind" "content")
                               :start-at      cursor-time
                               :finish-at     to
                               :guide-group   guide
                               :slot-id       (:schedule-slots/id slot)
                               :is-manual     false}
                      [mid-events curm] (if (seq (:items e'))
                                          (apply-filler db cur slot channel
                                                        :mid to block-end playout-id opts)
                                          [[] cur])]
                  (recur (events-end mid-events to) curm e' 0
                         (into events (into [content] mid-events))))))))))))

(defn emit-flood
  "Fill from now until the next fixed-anchor slot.
   Returns [events cursor]."
  [db cursor slot channel playout-id {:keys [flood-end] :as opts}]
  ;; Flood fills from :next-start up to flood-end (the next fixed-anchor time).
  (let [items  (slot-items db slot channel opts)
        ckey   (collection-key slot channel)
        order  (keyword (or (:schedule-slots/playback-order slot) "chronological"))
        enum-opts {:seed (get opts :seed 0)
                   :batch-size (or (:schedule-slots/marathon-batch-size slot) 5)}
        guide  (:next-guide-group cursor)
        series? (series-in-order? order items)
        end    (or flood-end
                   (t/add-duration (:next-start cursor) (t/hours->duration 2)))]
    (if (random-plan? slot order)
      (let [[events c] (emit-random-window db cursor slot channel playout-id opts :flood
                                           (:next-start cursor) end [])]
        [events (-> c (assoc :next-start end) (cursor/bump-guide-group))])
      (loop [cursor-time (:next-start cursor)
             e           (cursor/get-enumerator cursor ckey items order enum-opts)
             skipped     0
             events      []]
        (let [remaining (t/duration-between cursor-time end)]
          (cond
            (or (.isNegative remaining) (.isZero remaining))
            (let [c' (-> cursor
                         (assoc :next-start end)
                         (cursor/save-enumerator ckey e)
                         (cursor/bump-guide-group))]
              (log/info "emit-flood: horizon reached"
                       {:slot-id (:schedule-slots/id slot)
                        :total-events (count events)
                        :fill-reason "time-exhausted"
                        :flood-duration (str (t/duration-between (:next-start cursor) end))})
              [events c'])

            ;; Either there is no content at all, or a full pass over the pool
            ;; found nothing short enough to still fit the shrinking window. Pad
            ;; the remainder [cursor-time, end) with fallback filler.
            (or (empty? (:items e))
                (>= skipped (count (:items e))))
            (let [base-cursor (cursor/save-enumerator cursor ckey e)
                  [fill-events cursor'] (apply-filler db base-cursor slot channel
                                                      :fallback cursor-time end
                                                      playout-id opts)
                  c' (-> cursor'
                         (assoc :next-start end)
                         (cursor/bump-guide-group))]
              (log/info "emit-flood: content packed, remainder padded"
                       {:slot-id (:schedule-slots/id slot)
                        :total-events (count (into events fill-events))
                        :fill-reason (if (empty? (:items e)) "items-exhausted" "no-item-fits")
                        :content-events (count events)
                        :filler-events (count fill-events)})
              [(into events fill-events) c'])

            :else
            (let [[item e'] (enum/next-item e)
                  dur       (item-duration item)
                  to        (t/add-duration cursor-time dur)]
              (cond
                ;; A series in order: the episode that doesn't fit leads the next
                ;; window (see emit-block). One longer than this whole window is
                ;; passed over.
                (and (.isAfter to end)
                     series?
                     (not (pos? (compare dur (t/duration-between (:next-start cursor) end)))))
                (recur cursor-time e (count (:items e)) events)

                (.isAfter to end)
                ;; This item is too long for what's left of the window. Rather
                ;; than abandoning the rest of the slot to filler — which stranded
                ;; every shorter item still waiting in the pool and produced the
                ;; "one episode then dead air" symptom — skip past it and keep
                ;; packing. The enumerator advances (e') so the oversized item
                ;; comes around again on a later pass; `skipped` bounds the scan to
                ;; one full pool cycle so a window that nothing fits can't spin.
                (recur cursor-time e' (inc skipped) events)

                :else
                (recur to e' 0
                       (conj events {:playout-id    playout-id
                                     :media-item-id (:media-items/id item)
                                     :kind          (sql-util/->pg-enum "event_kind" "content")
                                     :start-at      cursor-time
                                     :finish-at     to
                                     :guide-group   guide
                                     :slot-id       (:schedule-slots/id slot)
                                     :is-manual     false}))))))))))

;; ---------------------------------------------------------------------------
;; Slot dispatch
;; ---------------------------------------------------------------------------

(defn- next-slot-start
  "If the next slot is fixed-anchor, returns its next fire time;
   otherwise returns nil."
  [slots slot-idx now zone-id]
  (let [next-idx (inc slot-idx)
        next     (when (< next-idx (count slots)) (nth slots next-idx))]
    (when (= "fixed" (some-> next :schedule-slots/anchor))
      (next-fixed-start next now zone-id))))

(defn- process-slot
  "Processes one slot, advancing cursor and collecting events.
   Returns [events cursor].

   If the slot has a days_of_week mask and the current cursor time falls on a
   non-firing day, no events are emitted and next-start is fast-forwarded to
   the slot's next valid occurrence.  The enumerator position is left unchanged
   so sequential shows (e.g. Mad Men MWF) resume from exactly where they left
   off on the next airing day."
  [db cursor slot channel playout-id slots opts]
  (let [zone-id  (get opts :zone-id (t/default-zone-id))
        dow-mask (:schedule-slots/days-of-week slot)
        now      (:next-start cursor)]
    (log/info "Processing slot"
             {:slot-index (:schedule-slots/slot-index slot)
              :slot-id (:schedule-slots/id slot)
              :fill-mode (:schedule-slots/fill-mode slot)
              :anchor (:schedule-slots/anchor slot)
              :cursor-time (str now)})
    (if (and (= "fixed" (:schedule-slots/anchor slot))
             (not (t/fires-on-day? dow-mask now zone-id)))
      ;; This slot doesn't air today -- advance to its next valid fire time.
      (let [next-fire (next-fixed-start slot now zone-id)]
        (log/info "Slot skipped (days_of_week)"
                 {:slot-index (:schedule-slots/slot-index slot)
                  :slot-id (:schedule-slots/id slot)
                  :next-fire (str next-fire)})
        [[] (assoc cursor :next-start next-fire)])
      ;; Normal path -- slot fires today (or is sequential).
      (let [fill (keyword (or (:schedule-slots/fill-mode slot) "once"))
            [events cursor'] (case fill
                               :once  (emit-once  db cursor slot channel playout-id opts)
                               :count (emit-count db cursor slot channel playout-id opts)
                               :block (emit-block db cursor slot channel playout-id opts)
                               :flood (let [flood-end (next-slot-start
                                                       slots
                                                       (:schedule-slots/slot-index slot)
                                                       now
                                                       zone-id)]
                                        (emit-flood db cursor slot channel playout-id
                                                    (assoc opts :flood-end flood-end)))
                               (do (log/warn "Unknown fill mode, skipping slot" {:fill fill})
                                   [[] cursor]))]
        (log/info "Slot processed"
                 {:slot-index (:schedule-slots/slot-index slot)
                  :slot-id (:schedule-slots/id slot)
                  :fill-mode fill
                  :events-generated (count events)
                  :new-cursor-time (str (:next-start cursor'))})
        [events cursor']))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(def ^:private default-max-hole
  "The longest stretch without events that a resume build accepts before the
   saved cursor. Days-of-week slots can legitimately leave up to a day empty;
   anything longer means events were lost after the cursor was saved."
  (t/hours->duration 24))

(defn covered-until
  "Given event spans [[start finish] ...] sorted by start, returns the end of
   the timeline's continuous coverage from `from`: the finish of the last event
   reached before a gap longer than `max-hole`. Returns `from` if nothing is
   scheduled within `max-hole` of it."
  [spans ^Instant from max-hole]
  (reduce (fn [^Instant edge [^Instant start ^Instant finish]]
            (cond
              (.isAfter start (.plus edge ^Duration max-hole)) (reduced edge)
              (.isAfter finish edge) finish
              :else edge))
          from
          spans))


;; ---------------------------------------------------------------------------
;; Day-based build loop (all-fixed schedules)
;; ---------------------------------------------------------------------------

(defn day-based?
  "Whether `slots` can be built day by day: every slot is fixed-anchor with a
   start time. Schedules synced from TS's quarterly grid always are. Schedules
   with sequential slots keep the slot-cycle loop."
  [slots]
  (and (seq slots)
       (every? #(and (= "fixed" (:schedule-slots/anchor %))
                     (:schedule-slots/start-time %))
               slots)))

(defn- emit-occurrence
  "Emit one slot occurrence starting at the cursor. Unlike process-slot there is
   no days-of-week check here: occurrences/next-occurrence only returns slots
   that air on that date."
  [db cursor slot channel playout-id next-start opts]
  (case (keyword (or (:schedule-slots/fill-mode slot) "once"))
    :once  (emit-once  db cursor slot channel playout-id opts)
    :count (emit-count db cursor slot channel playout-id opts)
    :block (emit-block db cursor slot channel playout-id opts)
    :flood (emit-flood db cursor slot channel playout-id (assoc opts :flood-end next-start))
    (do (log/warn "Unknown fill mode, skipping slot" {:slot-id (:schedule-slots/id slot)})
        [[] cursor])))

(defn- build-by-day
  "Fill [cursor, horizon] by repeatedly asking which slot occurrence comes next
   (see pseudovision.scheduling.occurrences): fill any gap before it with
   fallback filler, emit it, and move on. Each day's slots are chosen by date,
   so a slot that doesn't air today never moves the cursor, and override slots
   replace the base strips they overlap.

   Returns `{:events .. :cursor .. :stalled? bool}`. A slot error throws
   ex-info carrying the events and cursor so far under :events / :cursor."
  [db initial-cur slots channel playout-id horizon opts]
  (let [zone-id     (get opts :zone-id (t/default-zone-id))
        on-progress (:on-progress opts)
        from-s      (.getEpochSecond ^Instant (:next-start initial-cur))
        span-s      (max 1 (- (.getEpochSecond ^Instant horizon) from-s))]
    (loop [cursor initial-cur
           events []]
      (let [start (:next-start cursor)
            o     (when-not (.isAfter ^Instant start ^Instant horizon)
                    (occ/next-occurrence slots start zone-id))]
        (when on-progress
          (on-progress {:phase       "building"
                        :built-until (str start)
                        :horizon     (str horizon)
                        :events      (count events)
                        :percent     (-> (- (.getEpochSecond ^Instant start) from-s)
                                         (* 100) (quot span-s) (max 0) (min 100))}))
        (cond
          (.isAfter ^Instant start ^Instant horizon)
          {:events events :cursor cursor :stalled? false}

          (nil? o)
          (do (log/warn "No slot airs within a week of the cursor; stopping"
                        {:playout-id playout-id :at (str start)})
              {:events events :cursor cursor :stalled? true})

          (.isAfter ^Instant (:start o) ^Instant horizon)
          {:events events :cursor cursor :stalled? false}

          :else
          (let [slot     (:slot o)
                occ-at   (:start o)
                [gap-events cur1]
                (if (.isAfter ^Instant occ-at ^Instant start)
                  (let [[evs c] (apply-filler db cursor {} channel :fallback start occ-at
                                              playout-id opts)]
                    [evs (assoc c :next-start occ-at)])
                  [[] cursor])
                following (occ/next-occurrence slots (.plusSeconds ^Instant occ-at 1) zone-id)
                [slot-events cur2]
                (try
                  (emit-occurrence db cur1 slot channel playout-id (:start following) opts)
                  (catch Exception e
                    (throw (ex-info (str "Build failed at slot " (:schedule-slots/id slot)
                                         ": " (ex-message e))
                                    {:events  (into events gap-events)
                                     :cursor  cur1
                                     :slot-id (:schedule-slots/id slot)}
                                    e))))
                ;; Never let an occurrence leave the cursor where it was (an
                ;; empty pool with no filler emits nothing); skip to its end.
                cur3 (if (.isAfter ^Instant (:next-start cur2) ^Instant occ-at)
                       cur2
                       (assoc cur2 :next-start (or (:end o) (.plusSeconds ^Instant occ-at 60))))]
            (recur cur3 (into events (into gap-events slot-events)))))))))

(defn- write-events!
  "Writes a build's events and checks the result before the build commits.

   Filler that runs into the event after it is trimmed to end where that event
   starts (see validate/trim-filler-overruns). After the insert, the playout's
   timeline from `from` on is checked in the database, so the check also sees
   rows this build didn't write: an event left playing across the edge, or a
   second copy written by another build. Any overlap left at that point throws,
   which rolls the build back and leaves the previous timeline in place; the
   caller records the message on the playout. Returns the number of events
   written."
  [tx playout-id ^Instant from events]
  (let [overruns (validate/overlaps events)
        evs      (validate/trim-filler-overruns events)]
    (when (seq overruns)
      (log/info "Trimmed overlapping events before writing"
                {:playout-id playout-id
                 :overlaps   (count overruns)
                 :max-secs   (apply max (map #(/ (.toMillis ^Duration (:overlap %)) 1000.0)
                                             overruns))
                 :sample     (validate/describe (first overruns))}))
    (playout-db/bulk-insert-events! tx evs)
    (when-let [found (seq (playout-db/find-overlaps tx playout-id from))]
      (let [f   (first found)
            msg (str "Build rejected: " (count found)
                     (if (>= (count found) 50) "+" "")
                     " overlapping events, first a " (:kind f)
                     " at " (:start-at f) " running "
                     (:overlap-secs f) "s into a " (:next-kind f)
                     " at " (:next-start-at f))]
        (log/error msg {:playout-id playout-id :overlaps (take 10 found)})
        (throw (ex-info msg {:playout-id playout-id
                             :overlaps   (vec (take 10 found))}))))
    (count evs)))

(def ^:private rewind-lookback
  "How far back a rewound enumerator looks for the last item it aired."
  (t/hours->duration (* 24 120)))

(def default-filler-tolerance
  "Leftover time the filler packer treats as a full break unless the build
   options give :filler-tolerance. Without slack the packer ranked candidate
   breaks by leftover milliseconds first and recency second, and millisecond
   ties almost never happen, so the tightest-fitting break won whatever it
   repeated and the recency window (and the generic-clip penalty) barely
   mattered. Ten seconds lets recency choose among breaks that all fill their
   gap to within a few seconds."
  (Duration/ofSeconds 10))

(defn- filler-recency-window
  "How long an item's past airing keeps penalising it in the filler packer.
   An explicit `:recency-window` opt wins, then the channel's own
   channels.filler_recency_hours (a short window lets a channel with little
   filler, like Enigma TV, repeat it sooner), then the 4-hour default."
  [opts channel]
  (or (:recency-window opts)
      (some-> (:channels/filler-recency-hours channel) long t/hours->duration)
      (t/hours->duration 4)))

(defn build!
  "Builds the playout, replacing non-manual events from the rebuild point to the
   horizon.  Runs inside a transaction; saves the updated cursor on success.

   Two modes:

   • Resume / extend (default) — pick up from the playout's saved cursor and
     extend the timeline forward to the horizon, WITHOUT disturbing the events
     already scheduled before the cursor.  The delete boundary is the cursor's
     own edge (where the previous build stopped), not `now`, so the near-term
     timeline — and every show's rotation position — is preserved.  This is the
     path the daily top-up uses (see ensure-horizon!): each show keeps advancing
     from where it left off.  A cursor that has fallen into the past (the
     channel ran dry) is fast-forwarded to `now`, so the resumed build fills
     from now rather than replaying stale past times; its enumerator states are
     kept so rotation still continues.  Without this fast-forward the
     delete-then-resume left a gap between now and the old horizon.

   • Reset (opts :reset-cursor? true) — discard the saved cursor and generate a
     fresh timeline from now: the schedule restarts at its first slot and every
     collection from the top.  To avoid cutting off the program currently on
     screen, the fresh timeline (and the delete boundary) begins at the finish
     of the in-progress event rather than exactly now."
  [db opts playout]
  (let [channel-id  (:playouts/channel-id playout)
        schedule-id (:playouts/schedule-id playout)
        playout-id  (:playouts/id playout)
        seed        (:playouts/seed playout 0)
        channel     (when channel-id
                      (channels-db/get-channel db channel-id))
        schedule    (when schedule-id
                      (schedules-db/get-schedule db schedule-id))
        slots       (when schedule-id
                      (schedules-db/list-slots db schedule-id))
        now         (t/now)
        reset?      (boolean (:reset-cursor? opts))
        saved-cursor (cursor/<-json (:playouts/cursor playout))
        ;; On a resume build the timeline is extended forward from where the last
        ;; build stopped — the saved cursor's edge — rather than from now, so the
        ;; already-generated near-term events (and rotation state) survive. A
        ;; cursor that has slipped into the past (dry channel) is clamped up to
        ;; now so we fill from now instead of the stale past edge.
        ;;
        ;; The cursor is only trusted as far as the timeline actually reaches.
        ;; If events after now were lost (a hole of more than :max-hole), the
        ;; cursor has overshot: resuming from it would build nothing, or build
        ;; past the hole and never fill it. Resume from the start of the hole
        ;; instead.
        hole-edge   (atom nil)
        resume-edge (let [e (:next-start saved-cursor)
                          e (if (and e (.isAfter ^Instant e now)) e now)]
                      (if (and saved-cursor (not reset?) (.isAfter ^Instant e now))
                        (let [covered (covered-until
                                       (playout-db/list-event-spans-after db playout-id now)
                                       now
                                       (get opts :max-hole default-max-hole))]
                          (if (.isAfter ^Instant e ^Instant covered)
                            (do (log/warn "Saved cursor is past a hole in the timeline; resuming from the hole"
                                          {:playout-id playout-id
                                           :cursor     (str e)
                                           :hole-start (str covered)})
                                (reset! hole-edge covered)
                                covered)
                            e))
                        e))
        ;; Start the new timeline after any event that is playing at the edge.
        ;; On a reset that keeps the program on air. On a resume it matters as
        ;; much: a cursor fast-forwarded to `now`, or pulled back to a hole, can
        ;; land inside an event, which the delete below keeps (it starts before
        ;; the edge) and the new timeline would then be written over its tail.
        edge         (if reset? now resume-edge)
        rebuild-from (or (some-> (playout-db/get-event-straddling db playout-id edge)
                                 :playout-events/finish-at)
                         edge)
        horizon     (t/add-duration now (t/hours->duration
                                         (get opts :lookahead-hours 72)))
        opts'       (assoc opts :seed seed)
        started-ms  (System/currentTimeMillis)]
    (log/info "Build starting"
             {:playout-id playout-id
              :channel-id channel-id
              :schedule-id schedule-id
              :schedule-name (:schedules/name schedule)
              :slot-count (count slots)
              :reset-cursor? reset?
              :rebuild-from (str rebuild-from)
              :lookahead-hours (get opts :lookahead-hours 72)
              :horizon (str horizon)})
    (if (or (nil? schedule) (empty? slots))
      (do (log/warn "No schedule or slots; nothing to build"
                    {:playout-id playout-id})
          :no-schedule)
      (let [;; A reset, or a resume pulled back to a hole, rebuilds time the
            ;; saved cursor had already built past. Its enumerators sit at the
            ;; old edge: dropping them restarted every series at its first
            ;; episode, and keeping them as they are would skip what was
            ;; built but never aired. Instead they are marked to rewind to the
            ;; last item that aired before the rebuild point (see
            ;; cursor/get-enumerator), read once the old events are deleted.
            rewind?      (boolean (and saved-cursor (or reset? @hole-edge)))
            initial-cur  (cond
                           (and saved-cursor (not reset?))
                           ;; Resume from the saved cursor, but move its
                           ;; wall-clock edge to `rebuild-from` (now for a dry
                           ;; channel) so a stale past position doesn't replay old
                           ;; times. Enumerator states are kept, so rotation
                           ;; continues from where it left off.
                           (cond-> (assoc saved-cursor :next-start rebuild-from)
                             rewind? cursor/mark-rewind)

                           rewind?
                           (cursor/mark-rewind
                            (assoc (cursor/init rebuild-from)
                                   :enumerator-states (:enumerator-states saved-cursor)))

                           :else
                           (cursor/init rebuild-from))
            window       (filler-recency-window opts channel)]
        (try
          (jdbc/with-transaction [tx db]
            ;; One build of a playout at a time, across processes too. A second
            ;; build waits here and then deletes and rewrites what the first
            ;; one committed instead of writing a second copy beside it.
            (playout-db/lock-for-build! tx playout-id)
            ;; Remove auto-generated events from the rebuild point forward. On a
            ;; reset that's the in-progress event's finish (preserving what's on
            ;; air); on a resume it's the saved cursor's edge (clamped up to now for
            ;; a dry channel), so the already-built near-term timeline survives.
            (playout-db/delete-non-manual-events-after! tx playout-id rebuild-from)

            ;; Seed global filler recency from what is already scheduled on every
            ;; OTHER channel across the build window. This playout's own future
            ;; filler was just deleted above, so it won't double-count; in-build
            ;; picks are appended to this atom as we go (see pack-filler).
            (let [airings (playout-db/recent-filler-airings
                           tx (t/sub-duration now window) horizon)
                  repeat-window (get opts :content-repeat-window
                                     default-content-repeat-window)
                  opts'   (assoc opts' :pack-filler? (get opts :pack-filler? true)
                                       :filler-tolerance (get opts :filler-tolerance
                                                              default-filler-tolerance)
                                       :recency-window window
                                       :filler-airings-atom (atom airings)
                                       ;; What this channel aired recently, for
                                       ;; the random-block repeat window (see
                                       ;; plan-random-content). Events after
                                       ;; rebuild-from were just deleted; this
                                       ;; build's picks are added as it goes.
                                       :content-repeat-window repeat-window
                                       :content-airings-atom
                                       (atom (playout-db/recent-content-airings
                                              tx playout-id
                                              (t/sub-duration rebuild-from repeat-window)))
                                       ;; Per-build caches: collection contents
                                       ;; and Grout pools don't change during
                                       ;; a build (see slot-items and
                                       ;; grout-candidates).
                                       :items-cache (atom {})
                                       :grout-pools (atom {}))]
            (let [initial-cur (cond-> initial-cur
                                rewind? (cursor/with-rewind-airings
                                          (playout-db/recent-content-airings
                                           tx playout-id
                                           (t/sub-duration rebuild-from rewind-lookback))))]
            (if (day-based? slots)
              (let [{:keys [events cursor stalled?]}
                    (try
                      (build-by-day tx initial-cur slots channel playout-id horizon opts')
                      (catch clojure.lang.ExceptionInfo e
                        (let [{:keys [events cursor]} (ex-data e)]
                          (when (and events cursor)
                            (playout-db/bulk-insert-events! tx (validate/trim-filler-overruns events))
                            (playout-db/update-playout! tx playout-id
                                                        {:cursor        (cursor/->json cursor)
                                                         :last-built-at now
                                                         :build-success false
                                                         :build-message (ex-message e)})))
                        (throw e)))]
                (let [written (write-events! tx playout-id rebuild-from events)]
                  (playout-db/update-playout! tx playout-id
                                              {:cursor         (cursor/->json cursor)
                                               :last-built-at  now
                                               :build-success  (not stalled?)
                                               :build-message  (when stalled?
                                                                 "Build stopped: no slot airs within a week")})
                  (log/info "Build complete" {:playout-id  playout-id
                                              :mode        :by-day
                                              :events      written
                                              :elapsed-ms  (- (System/currentTimeMillis) started-ms)
                                              :item-pools  (count @(:items-cache opts'))
                                              :grout-pools (count @(:grout-pools opts'))})
                  written))
              (loop [cursor       initial-cur
                     slot-idx     0
                     cycle-anchor nil
                     events       []]
                (let [slot  (nth slots slot-idx nil)
                      start (:next-start cursor)]
                  (cond
                    (or (nil? slot) (.isAfter start horizon))
                    ;; Done; flush events and save cursor
                    (let [written (write-events! tx playout-id rebuild-from events)]
                      (playout-db/update-playout! tx playout-id
                                                  {:cursor         (cursor/->json cursor)
                                                   :last-built-at  now
                                                   :build-success  true
                                                   :build-message  nil})
                      (log/info "Build complete" {:playout-id playout-id
                                                  :events     written
                                                  :elapsed-ms (- (System/currentTimeMillis) started-ms)})
                      written)

                    ;; Safety net: if a full pass over every slot left the cursor
                    ;; exactly where it started, the schedule can't make forward
                    ;; progress (e.g. every slot resolves to no schedulable
                    ;; content). Without this, build! would loop forever and the
                    ;; HTTP request would hang with no response. Abort and record
                    ;; the failure instead.
                    (and (zero? slot-idx)
                         (some? cycle-anchor)
                         (not (.isAfter start cycle-anchor)))
                    (do
                      (log/warn "Build stalled: timeline did not advance over a full slot cycle; aborting"
                                {:playout-id playout-id :at (str start) :slots (count slots)})
                      (let [written (write-events! tx playout-id rebuild-from events)]
                        (playout-db/update-playout! tx playout-id
                                                    {:cursor         (cursor/->json cursor)
                                                     :last-built-at  now
                                                     :build-success  false
                                                     :build-message  "Build stalled: no schedulable content advanced the timeline"})
                        written))

                    ;; Process this slot
                    :else
                    (let [[cursor''' next-slot-idx next-cycle-anchor next-events]
                          (try
                            (let [[new-events cursor'] (process-slot
                                                         tx cursor slot channel
                                                         playout-id slots opts')
                                  fill-mode   (keyword (or (:schedule-slots/fill-mode slot) "once"))
                                  zone-id     (get opts' :zone-id (t/default-zone-id))
                                  next-slot   (nth slots (mod (inc slot-idx) (count slots)) nil)
                                  gap-end     (when (and (not= fill-mode :flood)
                                                         (= "fixed" (:schedule-slots/anchor next-slot)))
                                                (t/time-of-day-on-same-day
                                                 (:schedule-slots/start-time next-slot)
                                                 (:next-start cursor')
                                                 zone-id))
                                  [gap-events cursor'']
                                  (if gap-end
                                    (let [[evs c] (apply-filler tx cursor' {} channel
                                                                :fallback (:next-start cursor') gap-end
                                                                playout-id opts')]
                                      ;; apply-filler doesn't move :next-start, so
                                      ;; without this the fixed slot would start at
                                      ;; the old edge, on top of the gap filler,
                                      ;; instead of at its anchor time.
                                      [evs (cond-> c
                                             (.isAfter ^Instant gap-end (:next-start c))
                                             (assoc :next-start gap-end))])
                                    [[] cursor'])
                                  cursor''' (cursor/advance-slot cursor'' (count slots))]
                              [cursor''' (mod (inc slot-idx) (count slots))
                               (if (zero? slot-idx) start cycle-anchor)
                               (into events (into new-events gap-events))])
                            (catch Exception e
                              (log/error "Error processing slot; build failed"
                                        {:playout-id playout-id
                                         :slot-idx slot-idx
                                         :slot-id (:schedule-slots/id slot)
                                         :error (str e)
                                         :error-type (class e)})
                              (let [error-msg (str "Build failed at slot " slot-idx ": " (ex-message e))]
                                (playout-db/bulk-insert-events! tx (validate/trim-filler-overruns events))
                                (playout-db/update-playout! tx playout-id
                                                            {:cursor         (cursor/->json cursor)
                                                             :last-built-at  now
                                                             :build-success  false
                                                             :build-message  error-msg})
                                (throw (ex-info error-msg
                                               {:slot-idx slot-idx
                                                :slot-id (:schedule-slots/id slot)
                                                :playout-id playout-id
                                                :cause e})))))]
                      (recur cursor''' next-slot-idx next-cycle-anchor next-events)))))))))
          (catch Exception e
            ;; A slot error records build_success=false inside the transaction
            ;; and then throws, which rolls that record back along with the
            ;; build. Record the failure again outside the transaction so the
            ;; playout row shows it instead of a stale build_success=true.
            (try
              (playout-db/update-playout! db playout-id
                                          {:last-built-at now
                                           :build-success false
                                           :build-message (or (ex-message e) (str e))})
              (catch Exception e2
                (log/error e2 "Could not record build failure" {:playout-id playout-id})))
            (throw e)))))))

(defonce ^:private playout-locks (java.util.concurrent.ConcurrentHashMap.))

(defn- with-playout-lock
  "Runs `(f)` while holding this process's lock for `playout-id`.

   Two builds of one playout must not overlap: each deletes the future and
   inserts its own events in a separate transaction, and neither sees the
   other's uncommitted rows, so both sets survive as overlapping events. That
   happened when TS gave up waiting on a slow build and the next run started
   another. The second build now waits, then reads the playout (and cursor)
   the first one saved."
  [playout-id f]
  (let [lock (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap playout-locks (long playout-id)
                               (reify java.util.function.Function
                                 (apply [_ _] (Object.))))]
    (locking lock (f))))

(defn rebuild-from-now!
  "Wipe the auto-generated timeline from now onward and regenerate it fresh,
   starting the schedule over from its first slot.  Used when configuration
   changes (or to recover a playout whose cursor has run ahead).

   Passes :reset-cursor? so build! discards the saved cursor instead of
   resuming from it — without this the rebuild would silently restart wherever
   the previous build left off (typically the old horizon), leaving a gap
   between now and that point.

   `opts` (optional) is merged into the build options — notably :grout, the
   Grout client used to resolve Grout-backed filler presets.

   Returns number of events generated."
  ([ds playout-id horizon-days] (rebuild-from-now! ds playout-id horizon-days nil))
  ([ds playout-id horizon-days opts]
   (with-playout-lock playout-id
     (fn []
       (let [playout (playout-db/get-playout ds playout-id)]
         (if playout
           (let [result (build! ds (merge opts {:lookahead-hours (* horizon-days 24)
                                                :reset-cursor?   true}) playout)]
             (if (= result :no-schedule) 0 result))
           0))))))

(defn rebuild-horizon!
  "Extend a playout's timeline forward to `new-horizon-days` from now, preserving
   the events already scheduled before the saved cursor (and every show's
   rotation).  Resumes from the saved cursor via build!'s default (non-reset)
   mode — see build!'s docstring for the resume/extend semantics, including how a
   dry channel is refilled from now.  `current-horizon-days` is accepted for call
   compatibility but ignored; the horizon is always measured from now.
   `opts` (optional) is merged into the build options (see rebuild-from-now!).
   Returns number of events generated."
  ([ds playout-id current-horizon-days new-horizon-days]
   (rebuild-horizon! ds playout-id current-horizon-days new-horizon-days nil))
  ([ds playout-id _current-horizon-days new-horizon-days opts]
   (with-playout-lock playout-id
     (fn []
       (let [playout (playout-db/get-playout ds playout-id)]
         (if playout
           (let [result (build! ds (merge opts {:lookahead-hours (* new-horizon-days 24)}) playout)]
             (if (= result :no-schedule) 0 result))
           0))))))

(defn ensure-horizon!
  "Ensure one channel's playout timeline reaches at least `horizon-days` from now,
   without disturbing what's already scheduled in the near term.

   This is the per-channel daily top-up: it extends the timeline forward from
   wherever the last build stopped (advancing each show's rotation) up to
   `now + horizon-days`.  Existing upcoming events are preserved, so repeated
   calls are idempotent — once the timeline already reaches the horizon a further
   call generates nothing.  A channel that has run dry is refilled from now.

   Thin wrapper over rebuild-horizon! (the extend path); named for intent at the
   call site.  `opts` is merged into the build options (notably :grout).
   Returns number of events generated."
  ([ds playout-id horizon-days] (ensure-horizon! ds playout-id horizon-days nil))
  ([ds playout-id horizon-days opts]
   (rebuild-horizon! ds playout-id 0 horizon-days opts)))

(defn ensure-all-horizons!
  "Top up EVERY schedule-backed channel's playout timeline to at least
   `horizon-days` from now (see ensure-horizon!).  This is the bulk daily entry
   point: one call keeps all channels stocked with upcoming content, preserving
   near-term events and rotation.  Channels with no attached schedule are skipped,
   since PV has nothing to build them from.

   `opts` (optional) is merged into each channel's build options (notably
   :grout).  Per-channel failures are isolated: one channel erroring does not
   abort the rest; its entry carries an :error string instead of :events-generated.

   Returns a summary:
     {:channels N
      :horizon-days D
      :events-generated TOTAL
      :results [{:channel-id .. :playout-id .. :events-generated N}
                {:channel-id .. :playout-id .. :error \"...\"} ...]}"
  ([ds horizon-days] (ensure-all-horizons! ds horizon-days nil))
  ([ds horizon-days opts]
   (let [playouts (playout-db/list-playouts-with-schedule ds)
         results  (mapv
                   (fn [p]
                     (let [playout-id (:playouts/id p)
                           channel-id (:playouts/channel-id p)]
                       (try
                         {:channel-id       channel-id
                          :playout-id       playout-id
                          :events-generated (ensure-horizon! ds playout-id horizon-days opts)}
                         (catch Exception e
                           (log/error e "ensure-all-horizons: channel failed"
                                      {:playout-id playout-id :channel-id channel-id})
                           {:channel-id channel-id
                            :playout-id playout-id
                            :error      (.getMessage e)}))))
                   playouts)
         total    (reduce + 0 (keep :events-generated results))]
     (log/info "ensure-all-horizons complete"
               {:channels (count results) :events-generated total})
     {:channels         (count results)
      :horizon-days     horizon-days
      :events-generated total
      :results          results})))
