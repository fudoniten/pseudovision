-- Add tag-based filtering to schedule_slots
-- Enables slots to require/exclude content based on metadata tags

ALTER TABLE schedule_slots
ADD COLUMN required_tags TEXT[] DEFAULT '{}',
ADD COLUMN excluded_tags TEXT[] DEFAULT '{}';

-- Index for tag-based queries
CREATE INDEX ix_schedule_slots_required_tags ON schedule_slots USING GIN (required_tags);
CREATE INDEX ix_schedule_slots_excluded_tags ON schedule_slots USING GIN (excluded_tags);

COMMENT ON COLUMN schedule_slots.required_tags IS 'Items must have ALL of these tags to be selected (AND logic)';
COMMENT ON COLUMN schedule_slots.excluded_tags IS 'Items with ANY of these tags are filtered out (NOT logic)';

-- Example usage:
--   required_tags = ['comedy', 'short']     → only items with BOTH tags
--   excluded_tags = ['explicit', 'nsfw']    → exclude items with EITHER tag
