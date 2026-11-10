UPDATE media_items SET state = 'normal' WHERE missing_since IS NOT NULL;
--;;
DROP INDEX IF EXISTS ix_media_items_missing;
--;;
ALTER TABLE media_items DROP COLUMN IF EXISTS missing_since;
