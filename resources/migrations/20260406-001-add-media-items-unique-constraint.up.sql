-- Add unique constraint on media_items (library_path_id, remote_key) for upserts
-- This allows ON CONFLICT to work properly for remote media sources like Jellyfin

CREATE UNIQUE INDEX uq_media_items_library_remote 
    ON media_items (library_path_id, remote_key) 
    WHERE remote_key IS NOT NULL;
--;;
