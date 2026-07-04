-- Drop the legacy `metadata_genres` table.
--
-- Genres are now stored as `genre:<kebab>` rows in `metadata_tags`, which is
-- the universal dimension/tag table (alongside `channel:<slug>`, `time-slot:`,
-- `audience:`, `freshness:`, `season:`). The backfill that populated the
-- `genre:` rows was applied before this migration, so dropping the legacy
-- table does not lose any data.
--
-- Once this migration runs, all genre reads must source from `metadata_tags`
-- filtered by the `genre:` prefix:
--   - db.catalog/show-genres          (private; called by list-show-profiles)
--   - db.catalog/list-genre-aggregates (public; powers /api/catalog/aggregate :genres)
--   - http.api.daily-slots/resolve-by-category (random:<category> lookups)
--
-- See pseudovision/src/pseudovision/db/catalog.clj and
-- pseudovision/src/pseudovision/http/api/daily_slots.clj for the canonical
-- implementations.

DROP TABLE IF EXISTS metadata_genres;
--;;
