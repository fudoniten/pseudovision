-- Remove description column from channels table

ALTER TABLE channels
DROP COLUMN IF EXISTS description;
