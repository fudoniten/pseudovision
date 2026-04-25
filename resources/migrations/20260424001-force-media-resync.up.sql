-- Force media items to resync by clearing their etags
-- This will cause the next library scan to re-upsert all items,
-- which will populate remote_key fields that were previously NULL

UPDATE media_items SET remote_etag = NULL WHERE remote_key IS NULL;
--;;
