-- Add channels.slug and backfill all existing rows
--
-- A channel's "slug" is the canonical key used by the channel-catalog fallback
-- in scheduling.core/load-items.  The slot resolver looks up
-- `metadata_tags.name = 'channel:<slug>'` to find items belonging to the
-- channel when the slot has no explicit collection-id or media-item-id.
--
-- PRE-FIX: the fallback constructed the tag as `channel:` + the channel's
-- display name (e.g. "channel:Sitcom Spectrum"), but live production tags
-- are stored against an op-curated lowercase slug (e.g. `channel:spectrum`).
-- The two namespaces never matched and every rebuild of the four "(auto)"
-- schedules returned events-generated: 0 (verified live 2026-07-17 from the
-- k8s PV pod logs: `channel-tag "channel:Sitcom Spectrum" → count 0`).
--
-- POST-FIX: `channels.slug` is a first-class column.  The fallback uses
-- `:channels/slug` and throws loudly if the column is nil — no silent
-- heuristic substitution from the display name.
--
-- The backfill below maps every existing channel to its currently-curated
-- slug (verified live by querying `metadata_tags` for the matching
-- `channel:<slug>` tag).  The forward-deploy constraint is NOT NULL UNIQUE
-- so a future insert/update cannot accidentally omit the field — the loud
-- error fires at the DB layer rather than at scheduling time.

ALTER TABLE channels
  ADD COLUMN slug TEXT;

--;;

-- Backfill using the curated mapping from the live `metadata_tags` table.
-- Run idempotently: 1 UPDATE per channel; the subsequent NOT NULL + UNIQUE
-- step would fail anyway if any row was missed.
UPDATE channels SET slug = 'infobytes'      WHERE id = 34 AND slug IS NULL;
--;;
UPDATE channels SET slug = 'goldenreels'    WHERE id = 35 AND slug IS NULL;
--;;
UPDATE channels SET slug = 'muse'           WHERE id = 36 AND slug IS NULL;
--;;
UPDATE channels SET slug = 'spotlight'      WHERE id = 37 AND slug IS NULL;
--;;
UPDATE channels SET slug = 'nippon'         WHERE id = 38 AND slug IS NULL;
--;;
UPDATE channels SET slug = 'toontown'       WHERE id = 39 AND slug IS NULL;
--;;
UPDATE channels SET slug = 'hua'            WHERE id = 40 AND slug IS NULL;
--;;
UPDATE channels SET slug = 'tasty'          WHERE id = 41 AND slug IS NULL;
--;;
UPDATE channels SET slug = 'enigma'         WHERE id = 42 AND slug IS NULL;
--;;
UPDATE channels SET slug = 'prime'          WHERE id = 43 AND slug IS NULL;
--;;
UPDATE channels SET slug = 'spectrum'       WHERE id = 44 AND slug IS NULL;
--;;
UPDATE channels SET slug = 'britannia'      WHERE id = 45 AND slug IS NULL;
--;;
UPDATE channels SET slug = 'galaxy'         WHERE id = 46 AND slug IS NULL;
--;;
UPDATE channels SET slug = 'chronicles'     WHERE id = 47 AND slug IS NULL;
--;;

--;;

-- Refuse to leave the migration half-applied: any backfill miss halts the
-- migration with a constraint violation, surfacing the bug in deploy logs
-- rather than producing silent-NULL behaviour later.
ALTER TABLE channels
  ALTER COLUMN slug SET NOT NULL;

--;;

ALTER TABLE channels
  ADD CONSTRAINT channels_slug_unique UNIQUE (slug);

--;;

COMMENT ON COLUMN channels.slug IS
  'Canonical lowercase key used to resolve a channel''s content via the `channel:<slug>` tag in metadata_tags. The scheduling engine requires this column: load-items throws when slug is nil, so it cannot silently emit zero events the way the pre-fix display-name lookup did. Set this explicitly for every channel — there is no default-from-name inference, because the production slugs (e.g. "spectrum" for "Sitcom Spectrum") are op-curated and do not match an algorithmic transform of the display name.';
