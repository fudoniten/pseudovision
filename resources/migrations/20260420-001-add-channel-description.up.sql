-- Add description column to channels table

ALTER TABLE channels
ADD COLUMN description TEXT;
--;;

COMMENT ON COLUMN channels.description IS 'Human-readable description of channel content and theme';
