CREATE TABLE channel_views (
    id          BIGSERIAL   PRIMARY KEY,
    channel_id  INTEGER     NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at    TIMESTAMPTZ
);
--;;
CREATE INDEX ix_channel_views_channel      ON channel_views (channel_id);
--;;
CREATE INDEX ix_channel_views_started      ON channel_views (started_at);
--;;
CREATE INDEX ix_channel_views_channel_time ON channel_views (channel_id, started_at DESC);
--;;

-- total_duration_secs is denormalised from media_versions at insert time so
-- percent_watched can be computed in SQL at update time without a join.
CREATE TABLE media_item_views (
    id                    BIGSERIAL        PRIMARY KEY,
    media_item_id         INTEGER          NOT NULL REFERENCES media_items (id) ON DELETE CASCADE,
    channel_id            INTEGER          NOT NULL REFERENCES channels (id)    ON DELETE CASCADE,
    source_type           TEXT             NOT NULL,
    start_position_secs   DOUBLE PRECISION NOT NULL DEFAULT 0,
    total_duration_secs   DOUBLE PRECISION,
    started_at            TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    ended_at              TIMESTAMPTZ,
    percent_watched       DOUBLE PRECISION
);
--;;
CREATE INDEX ix_media_item_views_item         ON media_item_views (media_item_id);
--;;
CREATE INDEX ix_media_item_views_channel      ON media_item_views (channel_id);
--;;
CREATE INDEX ix_media_item_views_started      ON media_item_views (started_at);
--;;
CREATE INDEX ix_media_item_views_channel_time ON media_item_views (channel_id, started_at DESC);
--;;
