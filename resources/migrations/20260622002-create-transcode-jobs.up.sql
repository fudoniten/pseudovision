-- Transcode job queue for distributed ahead-of-time normalization
--
-- Workers claim rows with FOR UPDATE SKIP LOCKED so concurrent pullers
-- never double-process a job.  Crash recovery is handled by the
-- lease_expires_at watchdog.

CREATE TABLE transcode_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_ref JSONB NOT NULL,
    target_profile_id INTEGER REFERENCES ffmpeg_profiles(id),
    spec JSONB NOT NULL,
    state TEXT NOT NULL DEFAULT 'pending',
    priority INTEGER NOT NULL DEFAULT 0,
    required_accel TEXT,
    worker_id TEXT,
    claimed_at TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,
    artifact_uri TEXT,
    error JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

;;

CREATE INDEX idx_transcode_jobs_state
    ON transcode_jobs(state, priority, created_at);

;;

CREATE INDEX idx_transcode_jobs_worker
    ON transcode_jobs(worker_id, lease_expires_at);
