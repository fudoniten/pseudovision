-- Rollback for add-slug-to-channels.

ALTER TABLE channels DROP CONSTRAINT IF EXISTS channels_slug_unique;

--;;

ALTER TABLE channels ALTER COLUMN slug DROP NOT NULL;

--;;

ALTER TABLE channels DROP COLUMN IF EXISTS slug;
