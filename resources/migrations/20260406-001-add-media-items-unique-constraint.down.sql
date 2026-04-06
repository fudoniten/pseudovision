-- Rollback: drop the unique constraint on media_items

DROP INDEX IF EXISTS uq_media_items_library_remote;
--;;
