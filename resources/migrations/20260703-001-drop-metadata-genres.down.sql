-- Rollback: recreate the legacy `metadata_genres` table.
--
-- This recreates the schema as it existed in
-- 20260225-001-initial-schema.up.sql. It does NOT repopulate the table —
-- that data lives in `metadata_tags` with the `genre:<kebab>` form now, and
-- would need to be re-derived from those rows to restore the legacy
-- human-readable form (e.g. `Sci-Fi & Fantasy`).

CREATE TABLE metadata_genres (
    id          SERIAL  PRIMARY KEY,
    metadata_id INTEGER NOT NULL REFERENCES metadata (id) ON DELETE CASCADE,
    name        TEXT    NOT NULL
);
--;;

CREATE INDEX ix_metadata_genres ON metadata_genres (metadata_id);
--;;
