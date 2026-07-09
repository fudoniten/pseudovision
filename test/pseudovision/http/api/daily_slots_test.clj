(ns pseudovision.http.api.daily-slots-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ring.mock.request :as mock]
            [cheshire.core     :as json]
            [pseudovision.http.core :as http]
            [pseudovision.http.api.daily-slots :as ds]
            [pseudovision.db.playouts :as playout-db]
            [pseudovision.db.channels :as channels-db]
            [pseudovision.db.core :as db-core]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-test-handler []
  (let [stub {:db nil :ffmpeg {} :media {} :scheduling {}}]
    (http/make-handler stub)))

(defn- parse-json-body [resp]
  (let [parsed (some-> resp :body (json/parse-string true))]
    (if (sequential? parsed) (vec parsed) parsed)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest daily-slots-returns-404-when-no-playout
  (testing "POST /api/channels/1/daily-slots returns 404 without playout"
    (with-redefs [channels-db/get-channel (fn [_ _] {:channels/id 1})
                  playout-db/get-playout-for-channel (fn [_ _] nil)]
      (let [handler (make-test-handler)
            resp    (handler (-> (mock/request :post "/api/channels/1/daily-slots")
                                (mock/json-body [{:start-time "2026-01-10T10:00:00Z"
                                                  :end-time "2026-01-10T22:00:00Z"
                                                  :media-id "series:cheers"
                                                  :media-selection-strategy "sequential"}])))]
        (is (= 404 (:status resp)))))))

(deftest daily-slots-ingests-empty-list
  (testing "POST /api/channels/1/daily-slots with empty body returns 0 ingested"
    (with-redefs [channels-db/get-channel (fn [_ _] {:channels/id 1})
                  playout-db/get-playout-for-channel (fn [_ _] {:playouts/id 1})
                  playout-db/delete-events! (fn [& _] 0)
                  playout-db/bulk-insert-events! (fn [& _] nil)]
      (let [handler (make-test-handler)
            resp    (handler (-> (mock/request :post "/api/channels/1/daily-slots")
                                (mock/json-body [])))]
        (is (= 200 (:status resp)))
        (let [body (parse-json-body resp)]
          (is (= 0 (:ingested body)))
          (is (= 0 (:skipped body))))))))

(deftest daily-slots-ingests-valid-slots
  (testing "POST /api/channels/1/daily-slots resolves media and creates events"
    (with-redefs [channels-db/get-channel (fn [_ _] {:channels/id 1})
                  playout-db/get-playout-for-channel (fn [_ _] {:playouts/id 1})
                  playout-db/delete-events! (fn [& _] 0)
                  playout-db/bulk-insert-events! (fn [& _] nil)
                  db-core/query-one (fn [_ _] nil)
                  db-core/query (fn [_ _] [{:media-items/id 42
                                            :media-items/kind "episode"
                                            :duration (java.time.Duration/ofMinutes 22)}])]
      (let [handler (make-test-handler)
            resp    (handler (-> (mock/request :post "/api/channels/1/daily-slots")
                                (mock/json-body [{:start-time "2026-01-10T10:00:00Z"
                                                  :end-time "2026-01-10T22:00:00Z"
                                                  :media-id "random:comedy"
                                                  :media-selection-strategy "random"}])))]
        (is (= 200 (:status resp)))
        (let [body (parse-json-body resp)]
          (is (= 1 (:ingested body)))
          (is (= 0 (:skipped body))))))))

(deftest daily-slots-schedules-unprobed-items
  (testing "a random:<category> slot whose resolved items carry no probed
            duration still ingests (stamped with the slot's nominal length)
            rather than being skipped as 'No playable items found'.

            Regression: the duration-aware overlap fix dropped every item
            without a positive probed duration, so a channel whose content was
            not yet probed lost ALL its playout events and its stream 503'd."
    (with-redefs [channels-db/get-channel (fn [_ _] {:channels/id 1})
                  playout-db/get-playout-for-channel (fn [_ _] {:playouts/id 1})
                  playout-db/delete-events! (fn [& _] 0)
                  playout-db/bulk-insert-events! (fn [& _] nil)
                  db-core/query-one (fn [_ _] nil)
                  ;; Resolved pool item carries NO :duration (never probed).
                  db-core/query (fn [_ _] [{:media-items/id 42
                                            :media-items/kind "episode"}])]
      (let [handler (make-test-handler)
            resp    (handler (-> (mock/request :post "/api/channels/1/daily-slots")
                                (mock/json-body [{:start-time "2026-01-10T10:00:00"
                                                  :end-time   "2026-01-10T11:00:00"
                                                  :media-id   "random:comedy"
                                                  :media-selection-strategy "random"
                                                  :category-filters []
                                                  :notes []}])))]
        (is (= 200 (:status resp)))
        (let [body (parse-json-body resp)]
          (is (= 1 (:ingested body)))
          (is (= 0 (:skipped body)))
          (is (empty? (:errors body))))))))

(deftest daily-slots-accepts-naive-datetimes
  (testing "POST .../daily-slots ingests a batch of naive-ISO slots (no Z/offset)"
    ;; The Tunarr-Scheduler expander emits naive local wall-clock datetimes
    ;; ("YYYY-MM-DDTHH:MM:SS" with no timezone). These must be accepted and
    ;; resolved, not rejected as "Missing start_time".
    (with-redefs [channels-db/get-channel (fn [_ _] {:channels/id 1})
                  playout-db/get-playout-for-channel (fn [_ _] {:playouts/id 1})
                  playout-db/delete-events! (fn [& _] 0)
                  playout-db/bulk-insert-events! (fn [& _] nil)
                  ;; Resolve any show lookup to a stub show; episode/tag queries
                  ;; resolve to a single concrete episode.
                  db-core/query-one (fn [_ _] {:media-items/id 99})
                  db-core/query (fn [_ _] [{:media-items/id 42
                                            :media-items/kind "episode"
                                            :duration (java.time.Duration/ofMinutes 22)}])]
      (let [batch [{:start-time "2026-06-29T00:00:00"
                    :end-time   "2026-06-29T01:00:00"
                    :media-id   "random:Mystery"
                    :media-selection-strategy "random"
                    :category-filters []
                    :notes []}
                   {:start-time "2026-06-29T01:00:00"
                    :end-time   "2026-06-29T02:00:00"
                    :media-id   "series:cheers"
                    :media-selection-strategy "sequential"
                    :category-filters []
                    :notes []}]
            handler (make-test-handler)
            resp    (handler (-> (mock/request :post "/api/channels/1/daily-slots")
                                (mock/json-body batch)))]
        (is (= 200 (:status resp)))
        (let [body (parse-json-body resp)]
          (is (= (count batch) (:ingested body)))
          (is (= 0 (:skipped body)))
          (is (empty? (:errors body))))))))

;; ---------------------------------------------------------------------------
;; Media resolution — SQL-shape regressions
;;
;; These lock in that the daily-slots resolver looks up the SAME id/dimension
;; space that GET /api/catalog/aggregate emits:
;;   - series:<id> episodes are nested show -> season -> episode, so the lookup
;;     must traverse seasons (not only direct children of the show);
;;   - random:<category> matches the `genre:` tag dimension the aggregate
;;     emits (post-DIMENSION_CLEANUP; the legacy metadata_genres table was
;;     dropped in migration 20260703-001), expanding shows to playable
;;     episodes.
;; ---------------------------------------------------------------------------

(deftest resolve-show-episodes-traverses-seasons
  (testing "series episode lookup reaches season-nested episodes, not just direct children"
    (let [captured (atom nil)]
      (with-redefs [db-core/query-one (fn [_ _] {:media-items/id 7})
                    db-core/query     (fn [_ sqlvec] (reset! captured sqlvec) [])]
        (#'ds/resolve-show-episodes nil "f2c639e8be1a00ff83e176aa034ee765")
        (let [sql (first @captured)]
          (is (re-find #"(?i)season" sql)
              "joins the seasons table to reach episodes")
          (is (re-find #"(?i)parent_id" sql)
              "links episodes to the show via parent_id"))))))

(deftest resolve-by-category-matches-tags-and-expands-playables
  (testing "random:<category> reads the `genre:` tag dimension the aggregate emits and resolves to playable items"
    (let [captured (atom nil)]
      (with-redefs [db-core/query (fn [_ sqlvec] (reset! captured sqlvec) [])]
        (#'ds/resolve-by-category nil "Mystery")
        (let [[sql & params] @captured]
          (is (not (re-find #"(?i)metadata_genres|metadata-genres" sql))
              "the legacy `metadata_genres` table is no longer queried (dropped in 20260703-001)")
          (is (re-find #"(?i)metadata_tags" sql)
              "matches against the `genre:` tag dimension the aggregate emits")
          (is (re-find #"(?i) play\b|AS play" sql)
              "selects a distinct playable row (episode/movie), not the show")
          (is (some #{"Mystery"} params)
              "binds the requested category verbatim (legacy case)")
           (is (some #{"genre:mystery"} params)
               "honours the genre:<name> tag convention"))))))

(deftest kebab-case-normalizes-special-characters
  (testing "the kebab-case helper produces the canonical tag form"
    (is (= "sci-fi-and-fantasy"   (#'ds/kebab-case "Sci-Fi & Fantasy"))
        "& becomes 'and' and spaces collapse to hyphens")
    (is (= "action-and-adventure" (#'ds/kebab-case "Action & Adventure")))
    (is (= "sci-fi"               (#'ds/kebab-case "Sci-Fi"))
        "non-alphanumerics become a single hyphen")
    (is (= "comedy"               (#'ds/kebab-case "Comedy"))
        "lowercase pass-through for the simple case")
    (is (= "comedy"               (#'ds/kebab-case "comedy"))
        "already-kebab inputs are unchanged")
    (is (= ""                     (#'ds/kebab-case nil))
        "nil input is safe (returns nil-ish empty string)")
    (is (= "drama"                (#'ds/kebab-case "  Drama  "))
        "leading/trailing whitespace is trimmed")))

(deftest resolve-by-category-matches-kebab-cased-input
  (testing "random:<category> with `&` and spaces hits the kebab-cased `genre:` tag too"
    ;; Repro of the 2026-07-03 outage. Tunabrain emits `random:Sci-Fi & Fantasy`
    ;; (human-readable, with `&` and spaces). Storage holds `genre:sci-fi-and-fantasy`
    ;; (kebab-case, the post-DIMENSION_CLEANUP canonical form). The query must
    ;; bind both `genre:sci-fi-and-fantasy` and the bare `sci-fi-and-fantasy` kebab
    ;; form so LLM-generated overrides resolve cleanly.
    (let [captured (atom nil)]
      (with-redefs [db-core/query (fn [_ sqlvec] (reset! captured sqlvec) [])]
        (#'ds/resolve-by-category nil "Sci-Fi & Fantasy")
        (let [[_sql & params] @captured]
          (is (some #{"sci-fi-and-fantasy"} params)
              "binds the kebab-cased bare form (no prefix)")
          (is (some #{"genre:sci-fi-and-fantasy"} params)
              "binds the kebab-cased `genre:` form (the canonical post-migration tag)")
          (is (some #{"sci-fi & fantasy"} params)
              "still binds the raw lowercased form (legacy metadata_tags rows that pre-date the kebab migration)")
          (is (some #{"genre:sci-fi & fantasy"} params)
              "still binds the raw lowercased form with `genre:` prefix (legacy row)"))))))

(deftest resolve-by-category-declares-season-join-before-it-is-referenced
  (testing "the season join is emitted before the play join that references season.id"
    ;; HoneySQL renders every inner :join ahead of every :left-join regardless
    ;; of threading order. When the playable-row join was an inner :join it was
    ;; emitted before the :season LEFT JOIN it referenced, yielding Postgres
    ;; error 42P01: "missing FROM-clause entry for table season". Lock in that
    ;; the season alias is declared before its first use.
    (let [captured (atom nil)]
      (with-redefs [db-core/query (fn [_ sqlvec] (reset! captured sqlvec) [])]
        (#'ds/resolve-by-category nil "Mystery")
        (let [sql      (str/lower-case (first @captured))
              ;; season.parent_id appears only in the :season join's own ON
              ;; clause (its declaration); season.id appears only in the play
              ;; join that references it.
              decl-idx (.indexOf sql "season.parent_id")
              ref-idx  (.indexOf sql "season.id")]
          (is (not (neg? decl-idx)) "season table is joined in the query")
          (is (not (neg? ref-idx))  "play join references season.id")
          (is (< decl-idx ref-idx)
              "season must be in scope (declared) before it is referenced"))))))

;; ---------------------------------------------------------------------------
;; Regression: category_filters must scope by SHOW, not episode
;;
;; Reproducer (Jul 2026): Tunarr Scheduler sends every weekly slot with
;; category_filters=["channel:<slug>"] to scope `random:<genre>` to the
;; channel's media pool. The slug is a show-level tag in metadata_tags.
;; A previous version of pick-item's filter looked up tags by the episode's
;; `:media-items/id`, which never had the show-level tag, and silently
;; rejected every slot with "No playable items found for media_id ...". The
;; `_top-id` carried on each resolved row points the filter at the show's
;; metadata, so the show-level tag matches and the slot ingests.
;; ---------------------------------------------------------------------------

(deftest resolve-by-category-selects-top-id
  (testing "random:<category> returns the show's id alongside the playable row, so category_filters can scope by top"
    (let [captured (atom nil)]
      (with-redefs [db-core/query (fn [_ sqlvec] (reset! captured sqlvec) [])]
        (#'ds/resolve-by-category nil "Drama")
        (let [[sql _params] @captured]
          (is (re-find #"(?i)\btop\.id\b" sql)
              "the top (show/movie) id is selected alongside play.*")
          (is (re-find #"(?i)AS _top_id\b|AS \"_top_id\"" sql)
              "the top id is aliased as _top_id so the Clojure layer can read it"))))))

(deftest resolve-show-episodes-tags-rows-with-show-id
  (testing "every episode row returned by resolve-show-episodes carries :_top-id == the show's id"
    (with-redefs [db-core/query-one (fn [_ _] {:media-items/id 7})
                  db-core/query     (fn [_ _] [{:media-items/id 100}
                                               {:media-items/id 101}])]
      (let [rows (#'ds/resolve-show-episodes nil "f2c639e8be1a00ff83e176aa034ee765")]
        (is (= 2 (count rows)))
        (is (every? #(= 7 (:_top-id %)) rows)
            "every episode is tagged with the parent show's id")))))

(deftest resolve-movie-tags-row-with-its-own-id
  (testing "the movie row carries :_top-id == :media-items/id (top and play are the same row)"
    (with-redefs [db-core/query-one (fn [_ sqlvec]
                                      ;; Only the first call returns the movie.
                                      (when (string? (first sqlvec))
                                        {:media-items/id 99 :media-items/kind "movie"}))]
      (let [m (#'ds/resolve-movie nil "99")]
        (is (some? m))
        (is (= 99 (:media-items/id m)))
        (is (= 99 (:_top-id m))
            "for movies _top-id equals the movie's own id")))))

(deftest daily-slots-succeeds-when-show-has-category-filter-tag
  (testing "POST .../daily-slots with a show-level category_filter ingests when the show has the tag"
    ;; Repro of the Jul 2026 outage. Before the fix, the filter was scoped
    ;; to episode tags, so category_filters=["channel:goldenreels"] excluded
    ;; every match. After the fix, the filter scopes by the show's id and
    ;; sees the tag in metadata_tags.
    (with-redefs [channels-db/get-channel (fn [_ _] {:channels/id 1})
                  playout-db/get-playout-for-channel (fn [_ _] {:playouts/id 1})
                  playout-db/delete-events! (fn [& _] 0)
                  playout-db/bulk-insert-events! (fn [& _] nil)
                  db-core/query-one (fn [_ _] {:media-items/id 7})
                  ;; Two DB calls inside pick-item:
                  ;;   1) resolve-show-episodes → returns the two stub episodes
                  ;;   2) tag lookup for the show → returns the channel tag
                  db-core/query (fn [_ sqlvec]
                                  (let [sql (str/lower-case (or (first sqlvec) ""))]
                                    (cond
                                      (str/includes? sql "from media_items")
                                      ;; resolve-show-episodes — the two episodes of show 7
                                      [{:media-items/id 100 :media-items/kind "episode"
                                        :duration (java.time.Duration/ofMinutes 22)}
                                       {:media-items/id 101 :media-items/kind "episode"
                                        :duration (java.time.Duration/ofMinutes 22)}]
                                      (str/includes? sql "from metadata_tags")
                                      ;; Tag lookup scoped by top-id (show 7):
                                      ;; the show carries channel:goldenreels, the
                                      ;; episodes do not.
                                      [{:metadata/media-item-id 7
                                        :metadata-tags/name "channel:goldenreels"}]
                                      :else [])))]
      (let [batch [{:start-time "2026-07-04T00:00:00"
                    :end-time   "2026-07-04T00:30:00"
                    :media-id   "series:abc"
                    :media-selection-strategy "specific"
                    :category-filters ["channel:goldenreels"]
                    :notes []}]
            handler (make-test-handler)
            resp    (handler (-> (mock/request :post "/api/channels/1/daily-slots")
                                (mock/json-body batch)))]
        (is (= 200 (:status resp)))
        (let [body (parse-json-body resp)]
          (is (= 1 (:ingested body))
              "the slot ingests when the show has the requested category tag")
          (is (= 0 (:skipped body)))
          (is (empty? (:errors body))))))))

(deftest daily-slots-skips-when-show-lacks-category-filter-tag
  (testing "POST .../daily-slots with a show-level category_filter still rejects when the show genuinely lacks the tag"
    ;; The fix must not turn a real "this show doesn't belong to this channel"
    ;; into a false positive. Confirm the show-level filter still excludes
    ;; shows that don't carry the requested tag.
    (with-redefs [channels-db/get-channel (fn [_ _] {:channels/id 1})
                  playout-db/get-playout-for-channel (fn [_ _] {:playouts/id 1})
                  playout-db/delete-events! (fn [& _] 0)
                  playout-db/bulk-insert-events! (fn [& _] nil)
                  db-core/query-one (fn [_ _] {:media-items/id 7})
                  db-core/query (fn [_ sqlvec]
                                  (let [sql (str/lower-case (or (first sqlvec) ""))]
                                    (cond
                                      (str/includes? sql "from media_items")
                                      [{:media-items/id 100 :media-items/kind "episode"}
                                       {:media-items/id 101 :media-items/kind "episode"}]
                                      (str/includes? sql "from metadata_tags")
                                      ;; Show 7 has no tag rows — it's not a member of
                                      ;; channel:goldenreels.
                                      []
                                      :else [])))]
      (let [batch [{:start-time "2026-07-04T00:00:00"
                    :end-time   "2026-07-04T00:30:00"
                    :media-id   "series:abc"
                    :media-selection-strategy "specific"
                    :category-filters ["channel:goldenreels"]
                    :notes []}]
            handler (make-test-handler)
            resp    (handler (-> (mock/request :post "/api/channels/1/daily-slots")
                                (mock/json-body batch)))]
        (is (= 200 (:status resp)))
        (let [body (parse-json-body resp)]
          (is (= 0 (:ingested body)))
          (is (= 1 (:skipped body))
              "the slot is skipped when the show genuinely lacks the tag")
          (is (re-find #"(?i)no playable items" (first (:errors body)))))))))

;; ---------------------------------------------------------------------------
;; Regression: duration-aware fit selection (the "movie overlaps next slot" bug)
;;
;; Before this fix, a random:<category> pool was selected from blindly (with no
;; regard for the slot's length), and the created event's finish_at was
;; stamped from the SLOT's own end_time rather than the picked item's actual
;; runtime. A 2h movie landed in a 1h slot was recorded as finishing on time,
;; so the next slot's event silently started on top of it.
;; ---------------------------------------------------------------------------

(deftest daily-slots-picks-item-that-fits-slot-and-stamps-real-duration
  (testing "random:<category> selection prefers a runtime that fits the slot; finish_at reflects the item's ACTUAL duration, not the slot boundary"
    (let [captured (atom nil)]
      (with-redefs [channels-db/get-channel (fn [_ _] {:channels/id 1})
                    playout-db/get-playout-for-channel (fn [_ _] {:playouts/id 1})
                    playout-db/delete-events! (fn [& _] 0)
                    playout-db/bulk-insert-events! (fn [_ events] (reset! captured events))
                    db-core/query-one (fn [_ _] nil)
                    ;; Pool for random:movie: a 55-minute film that fits a
                    ;; 1-hour slot, and a 130-minute epic that would grossly
                    ;; overflow it.
                    db-core/query (fn [_ _]
                                    [{:media-items/id 501 :media-items/kind "movie"
                                      :duration (java.time.Duration/ofMinutes 55)}
                                     {:media-items/id 502 :media-items/kind "movie"
                                      :duration (java.time.Duration/ofMinutes 130)}])]
        (let [batch [{:start-time "2026-08-01T20:00:00"
                      :end-time   "2026-08-01T21:00:00"
                      :media-id   "random:movie"
                      :media-selection-strategy "random"
                      :category-filters []
                      :notes []}]
              handler (make-test-handler)
              resp    (handler (-> (mock/request :post "/api/channels/1/daily-slots")
                                  (mock/json-body batch)))]
          (is (= 200 (:status resp)))
          (let [body (parse-json-body resp)]
            (is (= 1 (:ingested body)))
            (is (empty? (:errors body))))
          (is (= 1 (count @captured)))
          (let [event (first @captured)]
            (is (= 501 (:media-item-id event))
                "the 55-minute film is picked over the 130-minute epic that would overflow the slot")
            (is (= (java.time.Duration/ofMinutes 55)
                   (java.time.Duration/between (:start-at event) (:finish-at event)))
                "finish_at reflects the item's REAL duration, not the slot's nominal 1-hour boundary")))))))

(deftest daily-slots-shifts-next-slot-when-previous-item-overflows
  (testing "an oversized item shifts the NEXT slot's start forward instead of overlapping it"
    (let [captured (atom nil)
          call-idx (atom -1)
          ;; Slot 1 (random:movie): the only candidate is a 100-minute movie,
          ;; which overflows its nominal 1-hour window entirely (falls back to
          ;; "closest available" since nothing fits within tolerance).
          ;; Slot 2 (random:sitcom): a 22-minute episode that fits easily.
          responses [[{:media-items/id 601 :media-items/kind "movie"
                       :duration (java.time.Duration/ofMinutes 100)}]
                     [{:media-items/id 602 :media-items/kind "episode"
                       :duration (java.time.Duration/ofMinutes 22)}]]]
      (with-redefs [channels-db/get-channel (fn [_ _] {:channels/id 1})
                    playout-db/get-playout-for-channel (fn [_ _] {:playouts/id 1})
                    playout-db/delete-events! (fn [& _] 0)
                    playout-db/bulk-insert-events! (fn [_ events] (reset! captured events))
                    db-core/query-one (fn [_ _] nil)
                    db-core/query (fn [_ _] (nth responses (swap! call-idx inc)))]
        (let [batch [{:start-time "2026-08-01T20:00:00"
                      :end-time   "2026-08-01T21:00:00"
                      :media-id   "random:movie"
                      :media-selection-strategy "random"
                      :category-filters []
                      :notes []}
                     {:start-time "2026-08-01T21:00:00"
                      :end-time   "2026-08-01T21:30:00"
                      :media-id   "random:sitcom"
                      :media-selection-strategy "random"
                      :category-filters []
                      :notes []}]
              handler (make-test-handler)
              resp    (handler (-> (mock/request :post "/api/channels/1/daily-slots")
                                  (mock/json-body batch)))]
          (is (= 200 (:status resp)))
          (let [body (parse-json-body resp)]
            (is (= 2 (:ingested body)))
            (is (empty? (:errors body))))
          (is (= 2 (count @captured)))
          (let [[first-event second-event] (sort-by :start-at @captured)]
            (is (= 601 (:media-item-id first-event)))
            (is (= 602 (:media-item-id second-event)))
            ;; The 100-minute movie starting at 20:00 finishes at 21:40 —
            ;; 40 minutes past its own slot AND past slot 2's nominal 21:00
            ;; start.
            (is (= (:finish-at first-event) (:start-at second-event))
                "slot 2 starts exactly when slot 1's event actually finished, not at its own nominal 21:00 — no overlap")
            (is (.isAfter ^java.time.Instant (:start-at second-event)
                          (java.time.Instant/parse "2026-08-01T21:00:00Z"))
                "slot 2's start drifted later than its nominal time because slot 1 overran")))))))

(deftest daily-slots-varying-fit-pool-sizes-across-slots-do-not-error
  (testing "multiple slots referencing the same random:<category> pool, each with a different fitting subset, ingest without error"
    ;; Regression guard: pick-from-pool caches a shuffled array per pool key.
    ;; Before pool-cache-key folded the candidate SET into that key, two slots
    ;; of different lengths sharing one media_id could hand pick-from-pool
    ;; different-sized item sets under the SAME cache key, risking an
    ;; IndexOutOfBoundsException when a later call's index exceeded an
    ;; earlier, smaller cached array.
    (let [captured (atom nil)
          pool     [{:media-items/id 701 :media-items/kind "movie"
                     :duration (java.time.Duration/ofMinutes 20)}
                    {:media-items/id 702 :media-items/kind "movie"
                     :duration (java.time.Duration/ofMinutes 50)}
                    {:media-items/id 703 :media-items/kind "movie"
                     :duration (java.time.Duration/ofMinutes 100)}]]
      (with-redefs [channels-db/get-channel (fn [_ _] {:channels/id 1})
                    playout-db/get-playout-for-channel (fn [_ _] {:playouts/id 1})
                    playout-db/delete-events! (fn [& _] 0)
                    playout-db/bulk-insert-events! (fn [_ events] (reset! captured events))
                    db-core/query-one (fn [_ _] nil)
                    ;; Same underlying category pool on every call — as a real
                    ;; DB would return for the same category, regardless of
                    ;; the querying slot's own duration.
                    db-core/query (fn [_ _] pool)]
        (let [batch [{:start-time "2026-08-01T10:00:00"
                      :end-time   "2026-08-01T10:24:00" ; ~24min slot
                      :media-id   "random:comedy"
                      :media-selection-strategy "random"
                      :category-filters []
                      :notes []}
                     {:start-time "2026-08-01T11:00:00"
                      :end-time   "2026-08-01T11:24:00" ; ~24min slot
                      :media-id   "random:comedy"
                      :media-selection-strategy "random"
                      :category-filters []
                      :notes []}
                     {:start-time "2026-08-01T12:00:00"
                      :end-time   "2026-08-01T13:50:00" ; ~110min slot
                      :media-id   "random:comedy"
                      :media-selection-strategy "random"
                      :category-filters []
                      :notes []}]
              handler (make-test-handler)
              resp    (handler (-> (mock/request :post "/api/channels/1/daily-slots")
                                  (mock/json-body batch)))]
          (is (= 200 (:status resp)))
          (let [body (parse-json-body resp)]
            (is (= 3 (:ingested body)))
            (is (= 0 (:skipped body)))
            (is (empty? (:errors body)))))))))
