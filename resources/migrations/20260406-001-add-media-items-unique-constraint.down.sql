-- Rollback: drop the unique constraint on media_items

ALTER TABLE media_items 
    DROP CONSTRAINT IF EXISTS uq_media_items_library_remote;
--;;
