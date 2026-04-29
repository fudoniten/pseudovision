-- Add day-of-week filtering to schedule_slots
--
-- days_of_week is a bitmask where each bit represents a day:
--   Monday    = 1   (bit 0)
--   Tuesday   = 2   (bit 1)
--   Wednesday = 4   (bit 2)
--   Thursday  = 8   (bit 3)
--   Friday    = 16  (bit 4)
--   Saturday  = 32  (bit 5)
--   Sunday    = 64  (bit 6)
--
-- Default 127 (0b1111111) = every day, preserving existing behaviour.
--
-- Examples:
--   MWF only        = 1 + 4 + 16 = 21
--   Weekdays only   = 1+2+4+8+16 = 31
--   Weekends only   = 32+64       = 96
--   Mon only        = 1

ALTER TABLE schedule_slots
  ADD COLUMN days_of_week INTEGER NOT NULL DEFAULT 127;
--;;\n
COMMENT ON COLUMN schedule_slots.days_of_week IS
  'Bitmask: Mon=1 Tue=2 Wed=4 Thu=8 Fri=16 Sat=32 Sun=64. Default 127 = every day.';
