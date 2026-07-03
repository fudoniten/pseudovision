-- Grout-backed filler presets.
--
-- Grout is a single-purpose filler service that stores tagged clips on a
-- filesystem shared with Pseudovision.  A filler preset with a non-empty
-- `grout_tags` array draws its candidate clips from Grout (queried by channel +
-- tags + gap duration at build time) instead of from a local collection or
-- media item.  When `grout_tags` is NULL/empty the preset behaves exactly as
-- before (collection_id / media_item_id).
ALTER TABLE filler_presets
    ADD COLUMN grout_tags TEXT[];
--;;

CREATE INDEX ix_filler_grout ON filler_presets (id)
    WHERE grout_tags IS NOT NULL;
