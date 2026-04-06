-- Add unique constraint on media_items (library_path_id, remote_key) for upserts
-- This allows ON CONFLICT to work properly for remote media sources like Jellyfin
-- Note: We use a unique constraint, not a partial index, because ON CONFLICT requires it

ALTER TABLE media_items 
    ADD CONSTRAINT uq_media_items_library_remote 
    UNIQUE (library_path_id, remote_key);
--;;
