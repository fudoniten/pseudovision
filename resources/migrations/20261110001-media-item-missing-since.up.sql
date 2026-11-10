-- Track Jellyfin items that have dropped out of their library.
--
-- A full, successful library scan that no longer lists an item stamps
-- missing_since and sets state = 'unavailable', which hides it from the
-- catalog and from new playout builds. A later full scan that still doesn't
-- list it, once the grace period has passed, deletes the row. If the item
-- comes back first, the stamp is cleared and the state returns to 'normal'.
-- See pseudovision.media.prune.

ALTER TABLE media_items ADD COLUMN missing_since TIMESTAMPTZ;
--;;
CREATE INDEX ix_media_items_missing
    ON media_items (library_path_id)
 WHERE missing_since IS NOT NULL;
