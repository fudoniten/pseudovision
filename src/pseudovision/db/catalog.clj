(ns pseudovision.db.catalog
  "Aggregation queries for building the CatalogProfile that Tunarr Scheduler
   sends to Tunabrain. All queries are deterministic and return summarized
   shapes — no per-episode rows in the main response."
  (:require [honey.sql         :as sql]
            [honey.sql.helpers :as h]
            [pseudovision.db.core :as db]
            [pseudovision.util.sql :as sql-util]))

;; ---------------------------------------------------------------------------
;; Tag-filter helpers (shared with smart-collection logic)
;; ---------------------------------------------------------------------------

(defn- tag-exists-subq
  "Returns an EXISTS subquery for `media_items` aliased as `mi`.
   Checks whether `mi.id` has a metadata tag matching `tag`.
   `name-clause` is a HoneySQL predicate against `mt.name`."
  [name-clause]
  [:exists {:select [1]
            :from   [[:metadata :m2]]
            :join   [[:metadata-tags :mt2] [:= :mt2.metadata-id :m2.id]]
            :where  [:and [:= :m2.media-item-id :mi.id] name-clause]}])

(defn- tag-filter-clause
  "Returns a WHERE clause that limits `mi` to items carrying `tag`.
   Used for channel-scoped catalog slicing."
  [tag]
  (tag-exists-subq [:= :mt2.name tag]))

;; ---------------------------------------------------------------------------
;; Total counts
;; ---------------------------------------------------------------------------

(defn count-playable-items
  "Returns a map with :total_items, :total_episodes, :movie_count.
   Optional `tag-filter` limits the count to items with that tag."
  [ds tag-filter]
  (let [base (-> (h/select [:%count.* :total_items])
                 (h/from [:media-items :mi])
                 (h/where [:= :mi.state (sql-util/->pg-enum "media_item_state" "normal")])
                 (cond->
                   tag-filter (h/where (tag-filter-clause tag-filter))))
        total-result (db/query-one-unqualified ds (sql/format base))
        total-items  (:total-items total-result 0)

        ;; Episodes count
        episode-base (-> (h/select [:%count.* :total_episodes])
                         (h/from [:media-items :mi])
                         (h/where [:and [:= :mi.state (sql-util/->pg-enum "media_item_state" "normal")]
                                        [:= :mi.kind (sql-util/->pg-enum "media_item_kind" "episode")]])
                         (cond->
                           tag-filter (h/where (tag-filter-clause tag-filter))))
        episode-result (db/query-one-unqualified ds (sql/format episode-base))
        total-episodes (:total-episodes episode-result 0)

        ;; Movies count
        movie-base (-> (h/select [:%count.* :movie_count])
                        (h/from [:media-items :mi])
                        (h/where [:and [:= :mi.state (sql-util/->pg-enum "media_item_state" "normal")]
                                       [:= :mi.kind (sql-util/->pg-enum "media_item_kind" "movie")]])
                        (cond->
                          tag-filter (h/where (tag-filter-clause tag-filter))))
        movie-result (db/query-one-unqualified ds (sql/format movie-base))
        movie-count  (:movie-count movie-result 0)]
    {:total_items      total-items
     :total_episodes   total-episodes
     :movie_count      movie-count}))

;; ---------------------------------------------------------------------------
;; Show / movie profiles
;; ---------------------------------------------------------------------------

(defn- show-genres
  "Returns a map {show-id -> [genre-name ...]} for the given show ids.

   Reads from `metadata_tags` filtered to the `genre:` prefix (the canonical
   post-DIMENSION_CLEANUP storage). The `genre:` prefix is stripped from the
   returned values so the result shape stays consistent with the legacy
   `metadata_genres` column."
  [ds show-ids]
  (if (seq show-ids)
    (let [rows (db/query-unqualified ds
                  (-> (h/select :m.media-item-id
                                [[:raw "regexp_replace(mt.name, '^genre:', '')"] :name])
                      (h/from [:metadata-tags :mt])
                      (h/join [:metadata :m] [:= :m.id :mt.metadata-id])
                      (h/where [:and
                                [:in :m.media-item-id show-ids]
                                [:like :mt.name "genre:%"]])
                      (h/order-by :m.media-item-id :mt.name)
                      sql/format))]
      (reduce (fn [acc r]
                (update acc (:media-item-id r)
                        (fnil conj []) (:name r)))
              {} rows))
    {}))

(defn- show-tags
  "Returns a map {show-id -> [tag-name ...]} for the given show ids."
  [ds show-ids]
  (if (seq show-ids)
    (let [rows (db/query-unqualified ds
                  (-> (h/select :m.media-item-id :mt.name)
                      (h/from [:metadata-tags :mt])
                      (h/join [:metadata :m] [:= :m.id :mt.metadata-id])
                      (h/where [:in :m.media-item-id show-ids])
                      (h/order-by :m.media-item-id :mt.name)
                      sql/format))]
      (reduce (fn [acc r]
                (update acc (:media-item-id r)
                        (fnil conj []) (:name r)))
              {} rows))
    {}))

(defn list-show-profiles
  "Returns a seq of per-show / per-movie profile maps.
   Optional `tag-filter` limits results to items with that tag.

   Shape per row (before decoration):
   {:id, :remote_key, :name, :episode_count, :avg_runtime_minutes}"
  [ds tag-filter]
  (let [;; For shows we aggregate episode counts and average episode runtime.
        ;; For movies episode_count is 1 and avg_runtime is the movie's own
        ;; runtime.
        show-base
        (-> (h/select :mi.id :mi.kind :mi.remote-key [:m.title :name]
                     [[:count [:distinct :e.id]] :episode_count]
                     [[:avg [:raw "EXTRACT(EPOCH FROM mv.duration) / 60"]] :avg_runtime_minutes])
            (h/from [:media-items :mi])
            (h/join [:metadata :m] [:= :m.media-item-id :mi.id])
             (h/left-join [:media-items :season]
                          [:and [:= :season.parent-id :mi.id] [:= :season.kind (sql-util/->pg-enum "media_item_kind" "season")]])
             (h/left-join [:media-items :e]
                          [:and
                           [:or [:= :e.parent-id :mi.id]
                                [:= :e.parent-id :season.id]]
                           [:= :e.kind (sql-util/->pg-enum "media_item_kind" "episode")]])
             (h/left-join [:media-versions :mv]
                          [:= :mv.media-item-id :e.id])
            (h/where [:= :mi.state (sql-util/->pg-enum "media_item_state" "normal")])
            (h/where [:= :mi.kind (sql-util/->pg-enum "media_item_kind" "show")])
            (cond->
              tag-filter (h/where (tag-filter-clause tag-filter)))
            (h/group-by :mi.id :mi.remote-key :m.title)
            (h/order-by :mi.id))

        show-rows (db/query-unqualified ds (sql/format show-base))

        ;; Movies are profiled as a single-item "show"
        movie-base
        (-> (h/select :mi.id :mi.kind :mi.remote-key [:m.title :name]
                     [[:count [:distinct :mi.id]] :episode_count]
                     [[:avg [:raw "EXTRACT(EPOCH FROM mv.duration) / 60"]] :avg_runtime_minutes])
            (h/from [:media-items :mi])
            (h/join [:metadata :m] [:= :m.media-item-id :mi.id])
            (h/left-join [:media-versions :mv]
                         [:= :mv.media-item-id :mi.id])
            (h/where [:= :mi.state (sql-util/->pg-enum "media_item_state" "normal")])
            (h/where [:= :mi.kind (sql-util/->pg-enum "media_item_kind" "movie")])
            (cond->
              tag-filter (h/where (tag-filter-clause tag-filter)))
            (h/group-by :mi.id :mi.remote-key :m.title)
            (h/order-by :mi.id))

        movie-rows (db/query-unqualified ds (sql/format movie-base))

        all-rows   (concat show-rows movie-rows)
        all-ids    (map :id all-rows)
        genre-map  (show-genres ds all-ids)
        tag-map    (show-tags ds all-ids)]

    (->> all-rows
         (mapv (fn [row]
                 (let [id        (:id row)
                       remote-key (:remote-key row)
                       kind      (let [k (:kind row)] (if (keyword? k) (name k) (str k)))
                       media-id  (if (seq remote-key)
                                   (str (if (= "movie" kind) "movie:" "series:")
                                        remote-key)
                                   (str (if (= "movie" kind) "movie:" "series:")
                                        id))]
                   {:media_id              media-id
                    :title                 (or (:name row) "Unknown")
                    :genres                (get genre-map id [])
                    :episode_count         (or (:episode-count row) 0)
                    :available_episode_count (or (:episode-count row) 0)
                    :avg_runtime_minutes   (when-let [v (:avg-runtime-minutes row)]
                                             (when (number? v)
                                               (/ (Math/round (* v 100.0)) 100.0)))
                    :tags                  (get tag-map id [])})))
         (filterv #(pos? (:episode_count %))))))

;; ---------------------------------------------------------------------------
;; Genre aggregates
;; ---------------------------------------------------------------------------

(defn list-genre-aggregates
  "Returns a seq of {:genre, :show_count, :episode_count}.

   Reads from `metadata_tags` filtered to the `genre:` prefix (the canonical
   post-DIMENSION_CLEANUP storage). The `genre:` prefix is stripped from the
   returned values so the result shape stays consistent with the legacy
   `metadata_genres` column.

   Optional `tag-filter` limits to items with that tag.
   A show is counted for a genre if its metadata carries that genre.
   Episodes are counted as children of those shows."
  [ds tag-filter]
  (let [query
        (-> (h/select [[:raw "regexp_replace(mt.name, '^genre:', '')"] :genre]
                     [[:count [:distinct :mi.id]] :show_count]
                      [[:raw "SUM(e.count)::int"] :episode_count])
            (h/from [:metadata-tags :mt])
            (h/join [:metadata :m] [:= :m.id :mt.metadata-id])
            (h/join [:media-items :mi] [:= :mi.id :m.media-item-id])
             (h/left-join [[:lateral
                            {:select [[:%count.* :count]]
                             :from   [[:media-items :e2]]
                             :where  [:and [:= :e2.kind (sql-util/->pg-enum "media_item_kind" "episode")]
                                          [:or [:= :e2.parent-id :mi.id]
                                               [:in :e2.parent-id {:select [:season.id]
                                                                   :from   [[:media-items :season]]
                                                                   :where  [:and [:= :season.parent-id :mi.id]
                                                                                 [:= :season.kind (sql-util/->pg-enum "media_item_kind" "season")]]}]]]}]
                           :e]
                          [:= 1 1])
             (h/where [:and [:= :mi.state (sql-util/->pg-enum "media_item_state" "normal")]
                            [:in :mi.kind [(sql-util/->pg-enum "media_item_kind" "show")
                                           (sql-util/->pg-enum "media_item_kind" "movie")]]
                            [:like :mt.name "genre:%"]])
            (cond->
              tag-filter (h/where (tag-filter-clause tag-filter)))
            (h/group-by :mt.name)
            (h/order-by :mt.name))
        rows (db/query-unqualified ds (sql/format query))]
    (->> rows
         (mapv (fn [r]
                 {:genre         (or (:genre r) "Unknown")
                  :show_count    (or (:show-count r) 0)
                  :episode_count (or (:episode-count r) 0)}))
         (filterv #(pos? (:show_count %))))))

;; ---------------------------------------------------------------------------
;; Runtime histogram
;; ---------------------------------------------------------------------------

;; 15-minute buckets from 0 to 210 minutes, then an open-ended top bucket.
;; Matches the tolerance granularity `pseudovision.http.api.daily-slots` uses
;; at air time (`default-fit-tolerance-minutes`) and the bucket width the
;; feasibility duration-fit check (tunarr-scheduler) reasons in, so a "tight"
;; finding upstream and a "closest available" fallback at air time describe
;; the same boundary. Bumping past the old scheme's `60-90min`/`90+min` top
;; (which lumped a 91-minute film with a 3-hour epic together) was the whole
;; point of dimensioning this per-category — see DURATION_AWARE_SCHEDULING.md
;; (tunarr-scheduler) §3.2.
(def ^:private bucket-width-minutes 15)
(def ^:private bucket-ceiling-minutes 210)

(def ^:private bucket-bounds
  "[[lo hi] ...] for every closed bucket below the ceiling, e.g. [0 15] [15 30]
   ... [195 210]. The open-ended top bucket (`210+min`) is handled separately."
  (mapv (fn [lo] [lo (+ lo bucket-width-minutes)])
        (range 0 bucket-ceiling-minutes bucket-width-minutes)))

(defn- bucket-label [lo hi]
  (if hi (str lo "-" hi "min") (str lo "+min")))

(defn- runtime-bucket-clause
  "Returns a CASE expression that buckets durations into human-readable labels.
   Durations are measured in minutes (from EXTRACT(EPOCH FROM duration)/60)."
  []
  (into [:case]
        (concat
         (mapcat (fn [[lo hi]]
                   [[:<= [:raw "EXTRACT(EPOCH FROM mv.duration) / 60"] [:raw (str hi)]]
                    [:raw (str "'" (bucket-label lo hi) "'")]])
                 bucket-bounds)
         [:else [:raw (str "'" (bucket-label bucket-ceiling-minutes nil) "'")]])))

(def ^:private bucket-label->min-max
  (into {(bucket-label bucket-ceiling-minutes nil) [bucket-ceiling-minutes nil]}
        (map (fn [[lo hi]] [(bucket-label lo hi) [lo hi]]))
        bucket-bounds))

(defn- bucket->min-max
  "Map a bucket label to [min max] for the RuntimeBucket shape."
  [label]
  (get bucket-label->min-max label [0 nil]))

(defn list-runtime-histogram
  "Returns a seq of {:label, :min_minutes, :max_minutes, :item_count}.
   Only counts items with a positive duration (probed)."
  [ds tag-filter]
  (let [query
        (-> (h/select [(runtime-bucket-clause) :label]
                     [:%count.* :item_count])
            (h/from [:media-items :mi])
            (h/join [:media-versions :mv] [:= :mv.media-item-id :mi.id])
             (h/where [:and [:= :mi.state (sql-util/->pg-enum "media_item_state" "normal")]
                            [:> :mv.duration [:raw "INTERVAL '0'"]]])
            (cond->
              tag-filter (h/where (tag-filter-clause tag-filter)))
            (h/group-by [(runtime-bucket-clause)])
            (h/order-by [(runtime-bucket-clause)]))
        rows (db/query-unqualified ds (sql/format query))]
    (mapv (fn [r]
            (let [label (:label r)
                  [min max] (bucket->min-max label)]
              {:label       label
               :min_minutes min
               :max_minutes max
               :item_count  (or (:item-count r) 0)}))
          rows)))

(defn list-tag-runtime-histogram
  "Returns a seq of {:tag, :label, :min_minutes, :max_minutes, :item_count}.

   The per-category counterpart to `list-runtime-histogram`: buckets runtime
   by *tag* (the same `metadata_tags` dimension `random:<category>` slots
   resolve against — see `daily-slots.clj`'s `resolve-by-category`), not just
   globally. A `random:movie` strip and a `random:sitcom` strip can have
   wildly different runtime distributions; this is what lets a feasibility
   check (or a future slot-authoring step) ask 'does *this* category have
   content at *this* length', not just 'does the catalog as a whole'.

   Tags live on the top-level item (show or movie); this expands each tagged
   show to its (season-nested) episodes so the bucket reflects actual
   playable runtime, not the show's own (nonexistent) duration. A movie's tag
   and its runtime are on the same row. Optional `tag-filter` further limits
   to items ALSO carrying that tag (e.g. channel scoping), independent of the
   per-row `:tag` this groups by.

   Only counts items with a positive (probed) duration."
  [ds tag-filter]
  (let [query
        (-> (h/select [:t.name :tag]
                      [(runtime-bucket-clause) :label]
                      [:%count.* :item_count])
            (h/from [:metadata-tags :t])
            (h/join [:metadata :m] [:= :m.id :t.metadata-id])
            (h/join [:media-items :mi] [:= :mi.id :m.media-item-id])
            (h/left-join [:media-items :season]
                         [:and [:= :season.parent-id :mi.id]
                               [:= :season.kind (sql-util/->pg-enum "media_item_kind" "season")]])
            ;; :play and :mv are LEFT JOINs (not inner) purely to dodge
            ;; HoneySQL's join-emission order — every inner join is rendered
            ;; before every left join regardless of thread order, so an inner
            ;; :play here would render ahead of the :season LEFT JOIN its ON
            ;; clause references ("missing FROM-clause entry for table
            ;; season"). The WHERE clause below (`play.state = normal`,
            ;; `mv.duration > 0`) drops the unmatched NULL rows, so this
            ;; behaves like an inner join in effect — same pattern as
            ;; daily-slots.clj's `resolve-by-category`.
            (h/left-join [:media-items :play]
                         [:or
                          [:and [:= :mi.kind (sql-util/->pg-enum "media_item_kind" "movie")]
                                [:= :play.id :mi.id]]
                          [:and [:= :mi.kind (sql-util/->pg-enum "media_item_kind" "show")]
                                [:= :play.kind (sql-util/->pg-enum "media_item_kind" "episode")]
                                [:or [:= :play.parent-id :mi.id]
                                     [:= :play.parent-id :season.id]]]])
            (h/left-join [:media-versions :mv] [:= :mv.media-item-id :play.id])
            (h/where [:and
                      [:= :mi.state (sql-util/->pg-enum "media_item_state" "normal")]
                      [:= :play.state (sql-util/->pg-enum "media_item_state" "normal")]
                      [:in :mi.kind [(sql-util/->pg-enum "media_item_kind" "show")
                                     (sql-util/->pg-enum "media_item_kind" "movie")]]
                      [:> :mv.duration [:raw "INTERVAL '0'"]]])
            (cond->
              tag-filter (h/where (tag-filter-clause tag-filter)))
            (h/group-by :t.name [(runtime-bucket-clause)])
            (h/order-by :t.name [(runtime-bucket-clause)]))
        rows (db/query-unqualified ds (sql/format query))
        buckets (mapv (fn [r]
                        (let [label (:label r)
                              [min max] (bucket->min-max label)]
                          {:tag         (:tag r)
                           :label       label
                           :min_minutes min
                           :max_minutes max
                           :item_count  (or (:item-count r) 0)}))
                      rows)]
    ;; Wire shape is {:tag ... :buckets [RuntimeBucket ...]} — one entry per
    ;; tag — rather than the flat per-(tag,bucket) rows the query returns, so
    ;; group here rather than push that reshaping onto every caller.
    (->> buckets
         (group-by :tag)
         (mapv (fn [[tag tag-buckets]]
                 {:tag     tag
                  :buckets (mapv #(dissoc % :tag) tag-buckets)}))
         (sort-by :tag)
         vec)))

;; ---------------------------------------------------------------------------
;; Tag aggregates
;; ---------------------------------------------------------------------------

(defn list-tag-aggregates
  "Returns a seq of {:tag, :show_count, :episode_count}.
   Optional `tag-filter` limits to items with that tag.

   A show is counted for a tag if its metadata carries that tag.
   Episodes are counted as children of those shows."
  [ds tag-filter]
  (let [query
        (-> (h/select [:mt.name :tag]
                      [[:count [:distinct :mi.id]] :show_count]
                      [[:raw "SUM(e.count)::int"] :episode_count])
            (h/from [:metadata-tags :mt])
            (h/join [:metadata :m] [:= :m.id :mt.metadata-id])
            (h/join [:media-items :mi] [:= :mi.id :m.media-item-id])
            (h/left-join [[:lateral
                           {:select [[:%count.* :count]]
                            :from   [[:media-items :e2]]
                            :where  [:and [:= :e2.kind (sql-util/->pg-enum "media_item_kind" "episode")]
                                          [:or [:= :e2.parent-id :mi.id]
                                               [:in :e2.parent-id {:select [:season.id]
                                                                   :from   [[:media-items :season]]
                                                                   :where  [:and [:= :season.parent-id :mi.id]
                                                                                 [:= :season.kind (sql-util/->pg-enum "media_item_kind" "season")]]}]]]}]
                          :e]
                          [:= 1 1])
            (h/where [:and [:= :mi.state (sql-util/->pg-enum "media_item_state" "normal")]
                           [:in :mi.kind [(sql-util/->pg-enum "media_item_kind" "show")
                                          (sql-util/->pg-enum "media_item_kind" "movie")]]])
            (cond->
              tag-filter (h/where (tag-filter-clause tag-filter)))
            (h/group-by :mt.name)
            (h/order-by :mt.name))
        rows (db/query-unqualified ds (sql/format query))]
    (->> rows
         (mapv (fn [r]
                 {:tag           (or (:tag r) "Unknown")
                  :show_count    (or (:show-count r) 0)
                  :episode_count (or (:episode-count r) 0)}))
         (filterv #(pos? (:show_count %))))))

;; ---------------------------------------------------------------------------
;; Public assembly
;; ---------------------------------------------------------------------------

;; DEPRECATED: Includes hardcoded :channel_scope and :genres fields in the
;; CatalogProfile. These are legacy first-class concepts. Use :tag_aggregates
;; and tag-based filtering instead.
;; See TS DIMENSION_CLEANUP.md for the full migration plan.
(defn build-catalog-profile
  "DEPRECATED: Assembles the full CatalogProfile map with hardcoded fields.

   Includes :channel_scope (hardcoded channel concept) and :genres
   (hardcoded genre aggregates). In the dimension model, both are just tags
   in :tag_aggregates with 'channel:' and 'genre:' prefixes.

   `scope` is a map with:
     :channel-name — display name recorded in the profile's :channel_scope.
     :tag-filter   — resolved tag to slice the catalog by (nil = whole catalog).

   Returns a map ready to be JSON-encoded."
  [ds {:keys [channel-name tag-filter]}]
  (let [counts   (count-playable-items ds tag-filter)
        shows    (list-show-profiles ds tag-filter)
        genres   (list-genre-aggregates ds tag-filter)
        tags     (list-tag-aggregates ds tag-filter)
        histo    (list-runtime-histogram ds tag-filter)
        tag-histo (list-tag-runtime-histogram ds tag-filter)]
    {:channel_scope    channel-name
     :total_items      (or (:total_items counts) 0)
     :total_episodes   (or (:total_episodes counts) 0)
     :movie_count      (or (:movie_count counts) 0)
     :shows            shows
     :genres           genres
     :tag_aggregates   tags
     :runtime_histogram histo
     :tag_runtime_histograms tag-histo
     :generated_at     (str (java.time.Instant/now))}))
