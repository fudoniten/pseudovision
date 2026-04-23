-- Fix media items with NULL remote_key by forcing re-upsert
-- Strategy: Remove items with NULL remote_key and let the next scan recreate them properly

-- First, identify how many items have NULL remote_key
-- (This is just for logging purposes if someone checks the migration)
-- SELECT COUNT(*) FROM media_items WHERE remote_key IS NULL;

-- Delete media items with NULL remote_key
-- Cascading deletes will clean up related tables (versions, files, metadata, etc.)
DELETE FROM media_items WHERE remote_key IS NULL;
--;;
