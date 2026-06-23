DROP INDEX IF EXISTS idx_channels_gpu_pool;
ALTER TABLE channels DROP COLUMN IF EXISTS gpu_pool;
