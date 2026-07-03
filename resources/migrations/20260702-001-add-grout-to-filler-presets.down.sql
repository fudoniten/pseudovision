DROP INDEX IF EXISTS ix_filler_grout;
--;;

ALTER TABLE filler_presets
    DROP COLUMN IF EXISTS grout_tags;
