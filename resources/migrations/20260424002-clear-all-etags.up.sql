-- Force ALL media items to resync by clearing all etags
-- This ensures remote_key gets properly populated for all items

UPDATE media_items SET remote_etag = NULL;
--;;
