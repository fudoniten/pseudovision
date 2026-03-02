-- =============================================================================
-- Pseudovision initial schema
-- =============================================================================
-- Design goals
--   * ~35 tables vs 130+ in the original C# ErsatzTV
--   * Table-per-hierarchy (TPH) with a `kind` discriminator wherever a class
--     hierarchy would have produced multiple near-identical tables
--   * JSONB for config blobs that are always read/written as a unit
--     (ffmpeg profiles, watermark config, collection playlist items,
--      playout cursor state, media-source connection details)
--   * PostgreSQL native ENUMs for all discriminators and state columns
--   * The core scheduling path (channels → schedules → slots → playouts →
--     events) is self-contained in §8-§10; the optional template system (§11)
--     adds 12 more tables and can be migrated in a later version
-- =============================================================================


-- =============================================================================
-- §1  Foundation
-- =============================================================================

-- Key/value store for application-level settings (XMLTV path, JWT secret …)
CREATE TABLE config (
    key   TEXT NOT NULL PRIMARY KEY,
    value TEXT
);
--;;

-- ISO 639-2/T codes bundled at startup; read-only at runtime.
CREATE TABLE language_codes (
    id   SERIAL PRIMARY KEY,
    code TEXT   NOT NULL UNIQUE,   -- e.g. "eng", "fra"
    name TEXT   NOT NULL           -- e.g. "English"
);
--;;


-- =============================================================================
-- §2  Media Sources & Libraries
-- =============================================================================

CREATE TYPE media_source_kind AS ENUM (
    'local',      -- files scanned directly from the filesystem  ← implemented
    'plex',       -- future
    'jellyfin',   -- future
    'emby'        -- future
);
--;;

-- TPH: one row per source.
-- Remote connection details live in JSONB so they can evolve without schema
-- changes.  For 'local' sources connection_config is NULL.
--
-- connection_config shapes (remote sources only):
--   plex:     { client_identifier, product_version, platform,
--               connections: [{uri, is_active}] }
--   jellyfin: { operating_system, connections: [{uri, is_active}] }
--   emby:     { operating_system, connections: [{uri, is_active}] }
--
-- path_replacements: [{remote_path, local_path}]  (empty for local)
CREATE TABLE media_sources (
    id                    SERIAL            PRIMARY KEY,
    kind                  media_source_kind NOT NULL DEFAULT 'local',
    name                  TEXT              NOT NULL,
    connection_config     JSONB,
    path_replacements     JSONB             NOT NULL DEFAULT '[]',
    last_collections_scan TIMESTAMPTZ
);
--;;

CREATE TYPE library_kind AS ENUM (
    'movies',
    'shows',
    'music_videos',
    'other_videos',
    'songs',
    'images'
);
--;;

CREATE TABLE libraries (
    id              SERIAL       PRIMARY KEY,
    media_source_id INTEGER      NOT NULL REFERENCES media_sources (id) ON DELETE CASCADE,
    kind            library_kind NOT NULL,
    name            TEXT         NOT NULL,
    -- Remote-server library identifier (Plex key, Jellyfin item id, etc.)
    -- NULL for local libraries.
    external_id     TEXT,
    should_sync     BOOLEAN      NOT NULL DEFAULT TRUE,
    last_scan       TIMESTAMPTZ
);
--;;

CREATE INDEX ix_libraries_source ON libraries (media_source_id);
--;;

-- Each library can watch one or more root paths.
-- Separate table so individual paths can be rescanned independently.
CREATE TABLE library_paths (
    id         SERIAL  PRIMARY KEY,
    library_id INTEGER NOT NULL REFERENCES libraries (id) ON DELETE CASCADE,
    path       TEXT    NOT NULL,
    last_scan  TIMESTAMPTZ,
    UNIQUE (library_id, path)
);
--;;

CREATE INDEX ix_library_paths ON library_paths (library_id);
--;;

-- Subdirectory cache for incremental local scanning.
CREATE TABLE library_folders (
    id              SERIAL  PRIMARY KEY,
    library_path_id INTEGER NOT NULL REFERENCES library_paths (id) ON DELETE CASCADE,
    parent_id       INTEGER REFERENCES library_folders (id),
    path            TEXT    NOT NULL,
    etag            TEXT
);
--;;

CREATE INDEX ix_library_folders ON library_folders (library_path_id);
--;;


-- =============================================================================
-- §3  Media Items
-- =============================================================================

CREATE TYPE media_item_kind AS ENUM (
    'movie',
    'show',
    'season',
    'episode',
    'artist',
    'music_video',
    'other_video',
    'song',
    'image'
);
--;;

CREATE TYPE media_item_state AS ENUM (
    'normal',
    'file_not_found',
    'unavailable',
    'remote_only'
);
--;;

-- TPH: every piece of content is one row.
-- Replaces 14+ tables from the original (Movie, PlexMovie, Show, PlexShow,
-- Season, Episode, PlexEpisode, MusicVideo, Song, Artist, Image, OtherVideo …)
--
-- Hierarchy via self-join:
--   season.parent_id  → show
--   episode.parent_id → season
--   music_video.parent_id → artist
--   all others: parent_id IS NULL
CREATE TABLE media_items (
    id              SERIAL           PRIMARY KEY,
    kind            media_item_kind  NOT NULL,
    state           media_item_state NOT NULL DEFAULT 'normal',
    library_path_id INTEGER          NOT NULL REFERENCES library_paths (id) ON DELETE CASCADE,
    parent_id       INTEGER          REFERENCES media_items (id) ON DELETE CASCADE,

    -- Ordinal within parent (season_number / episode_number / track_number)
    position        INTEGER,

    -- Remote-source identity and change-detection etag
    remote_key      TEXT,
    remote_etag     TEXT,

    -- image-specific: override the global default display duration (seconds)
    image_seconds   INTEGER,

    CONSTRAINT chk_hierarchical_kinds_have_parent CHECK (
        (kind IN ('season', 'episode', 'music_video') AND parent_id IS NOT NULL)
        OR (kind NOT IN ('season', 'episode', 'music_video') AND parent_id IS NULL)
    )
);
--;;

CREATE INDEX ix_media_items_library    ON media_items (library_path_id);
--;;
CREATE INDEX ix_media_items_parent     ON media_items (parent_id) WHERE parent_id IS NOT NULL;
--;;
CREATE INDEX ix_media_items_kind_state ON media_items (kind, state);
--;;
CREATE INDEX ix_media_items_remote_key ON media_items (library_path_id, remote_key)
    WHERE remote_key IS NOT NULL;
--;;

-- A media item may have multiple versions (different encodes, editions).
CREATE TABLE media_versions (
    id                   SERIAL      PRIMARY KEY,
    media_item_id        INTEGER     NOT NULL REFERENCES media_items (id) ON DELETE CASCADE,
    name                 TEXT        NOT NULL DEFAULT 'Main',
    duration             INTERVAL    NOT NULL DEFAULT INTERVAL '0',
    width                INTEGER     NOT NULL DEFAULT 0,
    height               INTEGER     NOT NULL DEFAULT 0,
    sample_aspect_ratio  TEXT,
    display_aspect_ratio TEXT,
    r_frame_rate         TEXT,           -- e.g. "24000/1001"
    video_scan_kind      TEXT,           -- 'progressive' | 'interlaced' | 'unknown'
    interlaced_ratio     DOUBLE PRECISION,
    date_added           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    date_updated         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
--;;

CREATE INDEX ix_media_versions ON media_versions (media_item_id);
--;;

-- Physical file(s) backing a version (multi-file episodes are the exception)
CREATE TABLE media_files (
    id                SERIAL  PRIMARY KEY,
    media_version_id  INTEGER NOT NULL REFERENCES media_versions (id) ON DELETE CASCADE,
    library_folder_id INTEGER REFERENCES library_folders (id),
    path              TEXT    NOT NULL,
    path_hash         TEXT    NOT NULL UNIQUE   -- xxHash/SHA-256 of path for dedup
);
--;;

CREATE INDEX ix_media_files ON media_files (media_version_id);
--;;

CREATE TYPE stream_kind AS ENUM (
    'video', 'audio', 'subtitle', 'attachment', 'external_subtitle'
);
--;;

-- Individual A/V/subtitle streams within a version
CREATE TABLE media_streams (
    id                  SERIAL      PRIMARY KEY,
    media_version_id    INTEGER     NOT NULL REFERENCES media_versions (id) ON DELETE CASCADE,
    stream_index        INTEGER     NOT NULL,
    kind                stream_kind NOT NULL,
    codec               TEXT,
    profile             TEXT,
    language            TEXT,
    channels            INTEGER     NOT NULL DEFAULT 0,
    title               TEXT,
    is_default          BOOLEAN     NOT NULL DEFAULT FALSE,
    is_forced           BOOLEAN     NOT NULL DEFAULT FALSE,
    is_attached_pic     BOOLEAN     NOT NULL DEFAULT FALSE,
    pixel_format        TEXT,
    color_range         TEXT,
    color_space         TEXT,
    color_transfer      TEXT,
    color_primaries     TEXT,
    bits_per_raw_sample INTEGER     NOT NULL DEFAULT 0,
    -- sidecar subtitle file
    file_name           TEXT,
    mime_type           TEXT
);
--;;

CREATE INDEX ix_media_streams ON media_streams (media_version_id);
--;;

-- Chapter markers (for mid-roll filler injection)
CREATE TABLE media_chapters (
    id               SERIAL   PRIMARY KEY,
    media_version_id INTEGER  NOT NULL REFERENCES media_versions (id) ON DELETE CASCADE,
    chapter_id       BIGINT   NOT NULL,
    start_time       INTERVAL NOT NULL,
    end_time         INTERVAL NOT NULL,
    title            TEXT
);
--;;

CREATE INDEX ix_media_chapters ON media_chapters (media_version_id);
--;;


-- =============================================================================
-- §4  Metadata
-- =============================================================================

-- TPH: all metadata in one table.
-- Replaces 9 separate *Metadata tables from the original.
-- Kind-specific scalars (episode_number, album, track_number) are nullable;
--;;
-- they are never filtered on in isolation.
CREATE TABLE metadata (
    id              SERIAL          PRIMARY KEY,
    media_item_id   INTEGER         NOT NULL UNIQUE
                                    REFERENCES media_items (id) ON DELETE CASCADE,
    kind            media_item_kind NOT NULL,

    title           TEXT,
    sort_title      TEXT,
    original_title  TEXT,
    year            INTEGER,
    release_date    DATE,
    plot            TEXT,
    outline         TEXT,
    tagline         TEXT,
    content_rating  TEXT,

    -- episode-specific
    episode_number  INTEGER,

    -- music-specific
    album           TEXT,
    track_number    INTEGER,

    date_added      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    date_updated    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
--;;

CREATE INDEX ix_metadata_item       ON metadata (media_item_id);
--;;
CREATE INDEX ix_metadata_title      ON metadata (lower(title));
--;;
CREATE INDEX ix_metadata_sort_title ON metadata (sort_title);
--;;

CREATE TABLE metadata_genres (
    id          SERIAL  PRIMARY KEY,
    metadata_id INTEGER NOT NULL REFERENCES metadata (id) ON DELETE CASCADE,
    name        TEXT    NOT NULL
);
--;;
CREATE INDEX ix_metadata_genres ON metadata_genres (metadata_id);
--;;

CREATE TABLE metadata_tags (
    id                     SERIAL  PRIMARY KEY,
    metadata_id            INTEGER NOT NULL REFERENCES metadata (id) ON DELETE CASCADE,
    name                   TEXT    NOT NULL,
    -- Plex/Jellyfin network tag identifiers for smart collection matching
    external_collection_id TEXT,
    external_type_id       TEXT
);
--;;
CREATE INDEX ix_metadata_tags ON metadata_tags (metadata_id);
--;;

CREATE TABLE metadata_studios (
    id          SERIAL  PRIMARY KEY,
    metadata_id INTEGER NOT NULL REFERENCES metadata (id) ON DELETE CASCADE,
    name        TEXT    NOT NULL
);
--;;
CREATE INDEX ix_metadata_studios ON metadata_studios (metadata_id);
--;;

CREATE TYPE person_role AS ENUM ('actor', 'director', 'writer', 'artist');
--;;

CREATE TABLE metadata_people (
    id           SERIAL      PRIMARY KEY,
    metadata_id  INTEGER     NOT NULL REFERENCES metadata (id) ON DELETE CASCADE,
    role         person_role NOT NULL,
    name         TEXT        NOT NULL,
    character    TEXT,       -- only for actors
    sort_order   INTEGER,
    artwork_path TEXT
);
--;;
CREATE INDEX ix_metadata_people ON metadata_people (metadata_id);
--;;

-- External IDs: "imdb:tt1234567", "tvdb:123", tmdb GUIDs, etc.
CREATE TABLE metadata_external_ids (
    id          SERIAL  PRIMARY KEY,
    metadata_id INTEGER NOT NULL REFERENCES metadata (id) ON DELETE CASCADE,
    guid        TEXT    NOT NULL,
    UNIQUE (metadata_id, guid)
);
--;;
CREATE INDEX ix_metadata_external_ids      ON metadata_external_ids (metadata_id);
--;;
CREATE INDEX ix_metadata_external_ids_guid ON metadata_external_ids (guid);
--;;

CREATE TYPE artwork_kind AS ENUM ('poster', 'thumbnail', 'logo', 'fanart', 'watermark');
--;;

CREATE TABLE metadata_artwork (
    id                    SERIAL       PRIMARY KEY,
    metadata_id           INTEGER      NOT NULL REFERENCES metadata (id) ON DELETE CASCADE,
    kind                  artwork_kind NOT NULL,
    path                  TEXT         NOT NULL,
    source_path           TEXT,
    blur_hash_43          TEXT,
    blur_hash_54          TEXT,
    blur_hash_64          TEXT,
    original_content_type TEXT,
    is_orphan             BOOLEAN,
    date_added            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    date_updated          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
--;;
CREATE INDEX ix_metadata_artwork ON metadata_artwork (metadata_id, kind);
--;;

CREATE TYPE subtitle_kind AS ENUM ('embedded', 'sidecar');
--;;

CREATE TABLE subtitles (
    id           SERIAL        PRIMARY KEY,
    metadata_id  INTEGER       NOT NULL REFERENCES metadata (id) ON DELETE CASCADE,
    kind         subtitle_kind NOT NULL,
    stream_index INTEGER       NOT NULL DEFAULT 0,
    codec        TEXT,
    language     TEXT,
    title        TEXT,
    is_default   BOOLEAN       NOT NULL DEFAULT FALSE,
    is_forced    BOOLEAN       NOT NULL DEFAULT FALSE,
    is_sdh       BOOLEAN       NOT NULL DEFAULT FALSE,
    is_extracted BOOLEAN       NOT NULL DEFAULT FALSE,
    path         TEXT,
    date_added   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    date_updated TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
--;;
CREATE INDEX ix_subtitles ON subtitles (metadata_id);
--;;


-- =============================================================================
-- §5  Collections
-- =============================================================================

-- Unified collections table.
-- Replaces: Collection, SmartCollection, MultiCollection, MultiCollectionItem,
-- Playlist, PlaylistItem, PlaylistGroup, TraktList, TraktListItem,
-- RerunCollection (11+ tables).
--
-- kind-specific config shapes:
--   manual:   {}          (items live in collection_items junction table)
--   smart:    { "query": "genre:comedy year:>2010" }
--   playlist: { "items": [{ "index", "content_kind", "content_id",
--                            "playback_order", "count", "play_all",
--                            "include_in_guide" }] }
--   multi:    { "members": [{ "collection_id", "playback_order",
--                              "schedule_as_group" }] }
--   trakt:    { "trakt_id", "user", "list", "auto_refresh",
--               "generate_playlist", "last_update", "item_count" }
--   rerun:    { "source_collection_id", "first_run_order", "rerun_order" }
CREATE TYPE collection_kind AS ENUM (
    'manual',     -- explicit list
    'smart',      -- search query evaluated at build time
    'playlist',   -- ordered list of (collection, settings) tuples
    'multi',      -- union of other collections
    'trakt',      -- synced from Trakt.tv
    'rerun'       -- first-run → rerun wrapper
);
--;;

CREATE TABLE collections (
    id                        SERIAL          PRIMARY KEY,
    kind                      collection_kind NOT NULL,
    name                      TEXT            NOT NULL,
    use_custom_playback_order BOOLEAN         NOT NULL DEFAULT FALSE,
    config                    JSONB           NOT NULL DEFAULT '{}'
);
--;;

CREATE INDEX ix_collections_kind ON collections (kind);
--;;
CREATE INDEX ix_collections_name ON collections (lower(name));
--;;

-- Junction table for manual collections only.
CREATE TABLE collection_items (
    collection_id INTEGER NOT NULL REFERENCES collections (id) ON DELETE CASCADE,
    media_item_id INTEGER NOT NULL REFERENCES media_items (id) ON DELETE CASCADE,
    custom_order  INTEGER,
    PRIMARY KEY (collection_id, media_item_id)
);
--;;
CREATE INDEX ix_collection_items_item ON collection_items (media_item_id);
--;;

-- Trakt list → local media item mapping (populated by Trakt sync).
CREATE TABLE trakt_list_items (
    id            SERIAL  PRIMARY KEY,
    collection_id INTEGER NOT NULL REFERENCES collections (id) ON DELETE CASCADE,
    media_item_id INTEGER NOT NULL REFERENCES media_items (id) ON DELETE CASCADE,
    guids         JSONB   NOT NULL DEFAULT '[]',   -- raw imdb/tmdb/tvdb strings
    trakt_kind    TEXT    NOT NULL,                -- 'movie'|'show'|'season'|'episode'
    season        INTEGER,
    episode       INTEGER,
    UNIQUE (collection_id, media_item_id)
);
--;;
CREATE INDEX ix_trakt_items_collection ON trakt_list_items (collection_id);
--;;
CREATE INDEX ix_trakt_items_item       ON trakt_list_items (media_item_id);
--;;


-- =============================================================================
-- §6  FFmpeg Profiles, Watermarks, Graphics
-- =============================================================================

-- All encoding parameters in JSONB.  They are always read/written as a unit
-- and evolve with each FFmpeg release.  No query ever filters on a single
-- field like video_bitrate.
--
-- config keys: thread_count, hw_accel (none/qsv/nvenc/vaapi/videotoolbox/
--   amf/v4l2m2m/rkmpp), vaapi_display, vaapi_driver, vaapi_device,
--   qsv_extra_hw_frames, resolution, scaling_behavior (scale_and_pad/
--   stretch/fit), pad_mode (software/hardware), video_format (h264/hevc/
--   mpeg2/av1/copy), video_profile, video_preset, allow_b_frames, bit_depth,
--   video_bitrate, video_buffer_size, tonemap_algorithm, audio_format
--   (aac/ac3/copy), audio_bitrate, audio_buffer_size, audio_channels,
--   audio_sample_rate, normalize_loudness_mode, target_loudness,
--   normalize_audio, normalize_video, normalize_framerate,
--   normalize_colors, deinterlace_video
CREATE TABLE ffmpeg_profiles (
    id     SERIAL PRIMARY KEY,
    name   TEXT   NOT NULL UNIQUE,
    config JSONB  NOT NULL
);
--;;

-- config keys: mode (none/permanent/intermittent/opacity_expression),
--   image_source (custom/channel_logo), image, original_content_type,
--   location (bottom_right/bottom_left/top_right/top_left/…),
--   size (percent/fixed), width_percent, h_margin_percent, v_margin_percent,
--   frequency_minutes, duration_seconds, opacity, place_within_content,
--   opacity_expression, z_index
CREATE TABLE watermarks (
    id     SERIAL PRIMARY KEY,
    name   TEXT   NOT NULL UNIQUE,
    config JSONB  NOT NULL
);
--;;

CREATE TYPE graphics_element_kind AS ENUM ('lower_third', 'sequence', 'overlay');
--;;

CREATE TABLE graphics_elements (
    id   SERIAL                PRIMARY KEY,
    name TEXT                  NOT NULL,
    path TEXT                  NOT NULL,
    kind graphics_element_kind NOT NULL
);
--;;


-- =============================================================================
-- §7  Filler Presets
-- =============================================================================

-- `role`     — WHERE the filler plays (positional)
-- `category` — WHAT the filler content is (descriptive)
--
-- Example: "Pre-roll Ads" → role='pre', category='commercial'
--          "Bumpers"       → role='tail', category='bumper'
--          "Short Docs"    → role='mid',  category='documentary'

CREATE TYPE filler_role AS ENUM (
    'pre',       -- pre-roll: before main content
    'mid',       -- mid-roll: chapter-based inside content
    'post',      -- post-roll: after main content
    'pad',       -- pad to a minute boundary
    'tail',      -- fill remaining time at end of a timed block
    'fallback'   -- play when no other content is scheduled
);
--;;

CREATE TYPE filler_category AS ENUM (
    'commercial',    -- advertising / paid interstitials
    'promo',         -- channel or show promos
    'bumper',        -- short ident / station ID (usually <30s)
    'short',         -- short film or short-form content
    'documentary',   -- documentary segment
    'music_video',   -- music videos used as filler
    'countdown',     -- clock, countdown, ticker
    'credit_roll',   -- end-credit sequences
    'trailer',       -- movie or show trailers
    'interstitial',  -- general-purpose transition content
    'other'
);
--;;

CREATE TYPE filler_mode AS ENUM (
    'duration',      -- fill exactly this duration
    'count',         -- play exactly N items
    'random_count',  -- play 1..N items (random)
    'pad_to_minute'  -- pad to the next N-minute boundary
);
--;;

CREATE TABLE filler_presets (
    id                    SERIAL          PRIMARY KEY,
    name                  TEXT            NOT NULL,
    role                  filler_role     NOT NULL,
    category              filler_category NOT NULL DEFAULT 'other',
    mode                  filler_mode     NOT NULL,

    -- mode-specific scalars (only the relevant one is non-null)
    duration              INTERVAL,    -- mode = 'duration'
    count                 INTEGER,     -- mode = 'count' or 'random_count' (max)
    pad_to_nearest_minute INTEGER,     -- mode = 'pad_to_minute'

    allow_watermarks      BOOLEAN      NOT NULL DEFAULT TRUE,
    use_chapters_as_items BOOLEAN      NOT NULL DEFAULT FALSE,

    -- content source (exactly one should be non-null in practice)
    collection_id         INTEGER      REFERENCES collections (id) ON DELETE SET NULL,
    media_item_id         INTEGER      REFERENCES media_items (id)  ON DELETE SET NULL
);
--;;

CREATE INDEX ix_filler_collection ON filler_presets (collection_id) WHERE collection_id IS NOT NULL;
--;;
CREATE INDEX ix_filler_category   ON filler_presets (category);
--;;


-- =============================================================================
-- §8  Channels
-- =============================================================================

CREATE TYPE streaming_mode AS ENUM (
    'ts',             -- MPEG-TS (most compatible)
    'ts_hybrid',      -- MPEG-TS with HLS hybrid
    'hls_direct',     -- HLS passthrough
    'hls_segmenter'   -- HLS with segmenter
);
--;;

CREATE TYPE subtitle_mode AS ENUM (
    'none', 'any', 'forced_only', 'default_only', 'burn_in'
);
--;;

CREATE TABLE channels (
    id                          SERIAL           PRIMARY KEY,
    -- UUID stable across renames; used in XMLTV, HDHR, and M3U output.
    uuid                        UUID             NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    number                      TEXT             NOT NULL UNIQUE,   -- "2", "4.1"
    sort_number                 DOUBLE PRECISION NOT NULL,
    name                        TEXT             NOT NULL,
    group_name                  TEXT,
    categories                  TEXT,

    streaming_mode              streaming_mode   NOT NULL DEFAULT 'ts',
    ffmpeg_profile_id           INTEGER          NOT NULL REFERENCES ffmpeg_profiles (id),
    watermark_id                INTEGER          REFERENCES watermarks (id) ON DELETE SET NULL,
    fallback_filler_id          INTEGER          REFERENCES filler_presets (id) ON DELETE SET NULL,

    preferred_audio_language    TEXT,
    preferred_audio_title       TEXT,
    preferred_subtitle_language TEXT,
    subtitle_mode               subtitle_mode    NOT NULL DEFAULT 'none',

    music_video_credits_mode     TEXT,
    music_video_credits_template TEXT,
    song_video_mode              TEXT,

    -- Optional padding at stream-open (seconds)
    slug_seconds                DOUBLE PRECISION,

    stream_selector_mode        TEXT             NOT NULL DEFAULT 'auto',
    stream_selector             TEXT,

    is_enabled                  BOOLEAN          NOT NULL DEFAULT TRUE,
    show_in_epg                 BOOLEAN          NOT NULL DEFAULT TRUE
);
--;;

CREATE INDEX ix_channels_sort ON channels (sort_number);
--;;

CREATE TABLE channel_artwork (
    id                    SERIAL       PRIMARY KEY,
    channel_id            INTEGER      NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    kind                  artwork_kind NOT NULL,
    path                  TEXT         NOT NULL,
    original_content_type TEXT,
    date_added            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    date_updated          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
--;;

CREATE INDEX ix_channel_artwork ON channel_artwork (channel_id);
--;;


-- =============================================================================
-- §9  Schedules and Slots
-- =============================================================================

-- A Schedule is the abstract, named, reusable program template.
-- The build engine compiles it into a Playout.
CREATE TABLE schedules (
    id                         SERIAL  PRIMARY KEY,
    name                       TEXT    NOT NULL UNIQUE,
    -- 'skip' = jump to next occurrence when a fixed-start time is in the past
    -- 'play' = run it anyway regardless of wall-clock position
    fixed_start_time_behavior  TEXT    NOT NULL DEFAULT 'skip',
    shuffle_slots              BOOLEAN NOT NULL DEFAULT FALSE,
    random_start_point         BOOLEAN NOT NULL DEFAULT FALSE,
    keep_multi_part_together   BOOLEAN NOT NULL DEFAULT FALSE,
    treat_collections_as_shows BOOLEAN NOT NULL DEFAULT FALSE
);
--;;

CREATE TYPE slot_anchor AS ENUM (
    'fixed',      -- starts at a wall-clock time (start_time must be set)
    'sequential'  -- starts when the previous slot ends
);
--;;

CREATE TYPE slot_fill_mode AS ENUM (
    'once',   -- play exactly one item
    'count',  -- play exactly N items
    'block',  -- fill a fixed duration, pad the remainder
    'flood'   -- fill until the next fixed-anchor slot
);
--;;

CREATE TYPE playback_order AS ENUM (
    'chronological',
    'random',
    'shuffle',
    'shuffle_in_order',
    'multi_episode_shuffle',
    'season_episode',
    'random_rotation',
    'marathon'
);
--;;

CREATE TYPE guide_mode AS ENUM (
    'normal',
    'filler'   -- hide from EPG / merge with adjacent content
);
--;;

-- A Slot is one entry in a Schedule.
-- Content source: set exactly one of (collection_id, media_item_id).
-- Filler overrides: all nullable — null means "use the channel default".
CREATE TABLE schedule_slots (
    id          SERIAL         PRIMARY KEY,
    schedule_id INTEGER        NOT NULL REFERENCES schedules (id) ON DELETE CASCADE,
    slot_index  INTEGER        NOT NULL,   -- ordering within the schedule

    anchor      slot_anchor    NOT NULL DEFAULT 'sequential',
    start_time  INTERVAL,                 -- required when anchor = 'fixed'

    fill_mode      slot_fill_mode NOT NULL DEFAULT 'once',
    item_count     INTEGER,               -- fill_mode = 'count'
    block_duration INTERVAL,             -- fill_mode = 'block'
    -- What to do with leftover time in a 'block' slot:
    -- 'none' | 'filler' | 'offline'
    tail_mode      TEXT          NOT NULL DEFAULT 'none',
    -- Number of items to discard-and-retry to make one fit the remaining time
    discard_to_fill_attempts INTEGER NOT NULL DEFAULT 0,

    -- Content source
    collection_id INTEGER REFERENCES collections (id) ON DELETE SET NULL,
    media_item_id INTEGER REFERENCES media_items (id)  ON DELETE SET NULL,

    playback_order          playback_order NOT NULL DEFAULT 'chronological',
    marathon_group_by       TEXT,
    marathon_shuffle_groups BOOLEAN        NOT NULL DEFAULT FALSE,
    marathon_shuffle_items  BOOLEAN        NOT NULL DEFAULT FALSE,
    marathon_batch_size     INTEGER,

    -- EPG / guide
    guide_mode    guide_mode NOT NULL DEFAULT 'normal',
    custom_title  TEXT,

    -- Filler overrides (NULL = inherit from channel)
    pre_filler_id      INTEGER REFERENCES filler_presets (id) ON DELETE SET NULL,
    mid_filler_id      INTEGER REFERENCES filler_presets (id) ON DELETE SET NULL,
    post_filler_id     INTEGER REFERENCES filler_presets (id) ON DELETE SET NULL,
    tail_filler_id     INTEGER REFERENCES filler_presets (id) ON DELETE SET NULL,
    fallback_filler_id INTEGER REFERENCES filler_presets (id) ON DELETE SET NULL,

    -- Display overrides (NULL = inherit from channel)
    watermark_id                INTEGER       REFERENCES watermarks (id) ON DELETE SET NULL,
    disable_watermarks          BOOLEAN       NOT NULL DEFAULT FALSE,
    preferred_audio_language    TEXT,
    preferred_audio_title       TEXT,
    preferred_subtitle_language TEXT,
    subtitle_mode               subtitle_mode,

    fill_with_group_mode TEXT NOT NULL DEFAULT 'none',  -- 'none'|'alternate'|'batch'

    UNIQUE (schedule_id, slot_index),

    CONSTRAINT chk_fixed_needs_start_time CHECK (
        (anchor = 'fixed' AND start_time IS NOT NULL)
        OR anchor = 'sequential'
    ),
    CONSTRAINT chk_count_needs_item_count CHECK (
        fill_mode <> 'count' OR item_count IS NOT NULL
    ),
    CONSTRAINT chk_block_needs_duration CHECK (
        fill_mode <> 'block' OR block_duration IS NOT NULL
    )
);
--;;

CREATE INDEX ix_schedule_slots ON schedule_slots (schedule_id, slot_index);
--;;

CREATE TABLE slot_graphics_elements (
    slot_id             INTEGER NOT NULL REFERENCES schedule_slots (id) ON DELETE CASCADE,
    graphics_element_id INTEGER NOT NULL REFERENCES graphics_elements (id) ON DELETE CASCADE,
    PRIMARY KEY (slot_id, graphics_element_id)
);
--;;


-- =============================================================================
-- §10  Playouts and Events
-- =============================================================================

-- A Playout is the concrete, time-stamped timeline for a Channel.
-- The build engine generates it from a Schedule and the cursor state.
-- One active Playout per Channel (UNIQUE constraint).
CREATE TABLE playouts (
    id                 SERIAL      PRIMARY KEY,
    channel_id         INTEGER     NOT NULL UNIQUE REFERENCES channels (id) ON DELETE CASCADE,
    schedule_id        INTEGER     REFERENCES schedules (id) ON DELETE SET NULL,
    seed               INTEGER     NOT NULL DEFAULT 0,
    daily_rebuild_time INTERVAL,

    -- Opaque build-engine cursor.
    -- Replaces PlayoutAnchor + PlayoutProgramScheduleAnchor +
    -- PlayoutScheduleItemFillGroupIndex (5 tables in the original) with a
    -- single JSONB blob.  The scheduling engine owns and evolves this freely.
    --
    -- Approximate shape:
    -- {
    --   "next_start":        "2024-01-01T20:00:00Z",
    --   "slot_index":        3,
    --   "count_remaining":   null,
    --   "block_ends_at":     null,
    --   "in_flood":          false,
    --   "in_duration_filler": false,
    --   "next_guide_group":  12,
    --   "enumerator_states": {
    --     "collection:42": { "seed": 999, "index": 7 },
    --     "collection:17": { "seed": 0,   "index": 2 }
    --   }
    -- }
    cursor             JSONB,

    last_built_at      TIMESTAMPTZ,
    build_success      BOOLEAN,
    build_message      TEXT
);
--;;

CREATE INDEX ix_playouts_schedule ON playouts (schedule_id) WHERE schedule_id IS NOT NULL;
--;;

CREATE TYPE event_kind AS ENUM (
    'content',   -- primary programme content
    'pre',       -- pre-roll filler
    'mid',       -- mid-roll filler
    'post',      -- post-roll filler
    'pad',       -- padding to a minute boundary
    'tail',      -- tail filler
    'fallback',  -- fallback when nothing else is available
    'offline'    -- explicit offline segment
);
--;;

-- A single scheduled media item in a Playout timeline.
--
-- is_manual = TRUE means the user injected or edited this event via the API.
-- The build engine preserves manual events on rebuild instead of replacing
-- them.  This is the mechanism for "add a bumper" and "swap an episode".
CREATE TABLE playout_events (
    id            SERIAL       PRIMARY KEY,
    playout_id    INTEGER      NOT NULL REFERENCES playouts (id) ON DELETE CASCADE,
    media_item_id INTEGER      NOT NULL REFERENCES media_items (id),
    kind          event_kind   NOT NULL DEFAULT 'content',

    start_at      TIMESTAMPTZ  NOT NULL,
    finish_at     TIMESTAMPTZ  NOT NULL,

    -- EPG display window (may differ from start/finish when filler is hidden)
    guide_start_at  TIMESTAMPTZ,
    guide_finish_at TIMESTAMPTZ,
    -- Grouping key so the EPG can display multi-segment content as one entry
    guide_group     INTEGER     NOT NULL DEFAULT 0,
    custom_title    TEXT,

    -- Chapter / trim support
    in_point      INTERVAL     NOT NULL DEFAULT INTERVAL '0',
    out_point     INTERVAL,    -- NULL = play to end
    chapter_title TEXT,

    -- Display overrides for this specific event
    watermark_id                INTEGER       REFERENCES watermarks (id) ON DELETE SET NULL,
    disable_watermarks          BOOLEAN       NOT NULL DEFAULT FALSE,
    preferred_audio_language    TEXT,
    preferred_audio_title       TEXT,
    preferred_subtitle_language TEXT,
    subtitle_mode               subtitle_mode,

    -- Traceability: which schedule slot produced this event
    slot_id       INTEGER REFERENCES schedule_slots (id) ON DELETE SET NULL,

    -- Build-engine resume keys (opaque to users)
    block_key       TEXT,
    collection_key  TEXT,
    collection_etag TEXT,

    -- TRUE = user-managed; rebuild will not overwrite
    is_manual     BOOLEAN      NOT NULL DEFAULT FALSE
);
--;;

-- Hot path: streaming engine fetches current/upcoming events constantly.
CREATE INDEX ix_playout_events_time
    ON playout_events (playout_id, start_at, finish_at);
--;;

-- EPG queries across all channels for a time window.
CREATE INDEX ix_playout_events_global_time
    ON playout_events (start_at, finish_at);
--;;

-- Fast lookup of manual events during rebuild.
CREATE INDEX ix_playout_events_manual
    ON playout_events (playout_id) WHERE is_manual = TRUE;
--;;

CREATE TABLE event_graphics_elements (
    event_id            INTEGER NOT NULL REFERENCES playout_events (id) ON DELETE CASCADE,
    graphics_element_id INTEGER NOT NULL REFERENCES graphics_elements (id) ON DELETE CASCADE,
    PRIMARY KEY (event_id, graphics_element_id)
);
--;;

-- Materialised gaps for fast "what's on now" EPG queries.
CREATE TABLE playout_gaps (
    id         SERIAL      PRIMARY KEY,
    playout_id INTEGER     NOT NULL REFERENCES playouts (id) ON DELETE CASCADE,
    start_at   TIMESTAMPTZ NOT NULL,
    finish_at  TIMESTAMPTZ NOT NULL
);
--;;
CREATE INDEX ix_playout_gaps ON playout_gaps (playout_id, start_at);
--;;

-- PlayoutHistory: tracks which items have recently aired per collection.
-- Used for sequential playback coherence across rebuilds and rerun detection.
CREATE TABLE playout_history (
    id               SERIAL         PRIMARY KEY,
    playout_id       INTEGER        NOT NULL REFERENCES playouts (id) ON DELETE CASCADE,
    block_key        TEXT,          -- identifies the schedule slot
    collection_key   TEXT           NOT NULL,
    child_key        TEXT,          -- playlist item / sub-collection
    is_current_child BOOLEAN        NOT NULL DEFAULT FALSE,
    playback_order   playback_order NOT NULL,
    position_index   INTEGER        NOT NULL DEFAULT 0,
    aired_at         TIMESTAMPTZ    NOT NULL,
    -- Used to efficiently prune stale rows (DELETE WHERE event_finish_at < ...)
    event_finish_at  TIMESTAMPTZ    NOT NULL,
    details          JSONB          -- title, episode info etc. for rerun display
);
--;;

CREATE INDEX ix_playout_history_playout    ON playout_history (playout_id);
--;;
CREATE INDEX ix_playout_history_collection ON playout_history (playout_id, collection_key);
--;;
CREATE INDEX ix_playout_history_finish     ON playout_history (event_finish_at);
--;;


-- =============================================================================
-- §11  Template Scheduling System  (optional — deferred to a later migration)
-- =============================================================================
-- Block groups, blocks, templates, decos, and playout_templates.
-- The core scheduling system in §8-§10 works without this section.
-- Add these tables in a separate migration when template scheduling is needed.
-- =============================================================================
