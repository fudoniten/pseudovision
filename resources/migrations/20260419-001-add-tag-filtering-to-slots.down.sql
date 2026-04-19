-- Rollback tag filtering from schedule_slots

DROP INDEX IF EXISTS ix_schedule_slots_excluded_tags;
DROP INDEX IF EXISTS ix_schedule_slots_required_tags;

ALTER TABLE schedule_slots
DROP COLUMN IF EXISTS excluded_tags,
DROP COLUMN IF EXISTS required_tags;
