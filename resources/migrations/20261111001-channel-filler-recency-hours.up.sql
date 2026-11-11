-- Per-channel filler recency window, in hours.
-- NULL = the global default (4 hours). A shorter window makes the filler
-- packer treat an item as "recent" for less time, so a channel with a small
-- filler supply repeats items sooner instead of running out.
ALTER TABLE channels ADD COLUMN filler_recency_hours integer
    CHECK (filler_recency_hours IS NULL OR filler_recency_hours >= 0);
