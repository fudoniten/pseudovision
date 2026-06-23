-- Add gpu_pool column to channels for sharded routing
--
-- A router or proxy can use this to pin a channel to a specific GPU pool
-- (e.g. 'nvenc', 'vaapi', or null for "any pod").  Null is the default so
-- existing channels are unaffected.

ALTER TABLE channels
  ADD COLUMN gpu_pool TEXT;

;;

CREATE INDEX idx_channels_gpu_pool ON channels(gpu_pool);

;;

COMMENT ON COLUMN channels.gpu_pool IS
  'Optional routing hint: nvenc, vaapi, etc. NULL = any pod.';
