-- Rollback §10
DROP TABLE IF EXISTS playout_history;
--;;
DROP TABLE IF EXISTS playout_gaps;
--;;
DROP TABLE IF EXISTS event_graphics_elements;
--;;
DROP TABLE IF EXISTS playout_events;
--;;
DROP TABLE IF EXISTS playouts;
--;;
DROP TYPE  IF EXISTS event_kind;
--;;

-- Rollback §9
DROP TABLE IF EXISTS slot_graphics_elements;
--;;
DROP TABLE IF EXISTS schedule_slots;
--;;
DROP TABLE IF EXISTS schedules;
--;;
DROP TYPE  IF EXISTS guide_mode;
--;;
DROP TYPE  IF EXISTS playback_order;
--;;
DROP TYPE  IF EXISTS slot_fill_mode;
--;;
DROP TYPE  IF EXISTS slot_anchor;
--;;

-- Rollback §8
DROP TABLE IF EXISTS channel_artwork;
--;;
DROP TABLE IF EXISTS channels;
--;;
DROP TYPE  IF EXISTS subtitle_mode;
--;;
DROP TYPE  IF EXISTS streaming_mode;
--;;

-- Rollback §7
DROP TABLE IF EXISTS filler_presets;
--;;
DROP TYPE  IF EXISTS filler_mode;
--;;
DROP TYPE  IF EXISTS filler_category;
--;;
DROP TYPE  IF EXISTS filler_role;
--;;

-- Rollback §6
DROP TABLE IF EXISTS graphics_elements;
--;;
DROP TABLE IF EXISTS watermarks;
--;;
DROP TABLE IF EXISTS ffmpeg_profiles;
--;;
DROP TYPE  IF EXISTS graphics_element_kind;
--;;

-- Rollback §5
DROP TABLE IF EXISTS trakt_list_items;
--;;
DROP TABLE IF EXISTS collection_items;
--;;
DROP TABLE IF EXISTS collections;
--;;
DROP TYPE  IF EXISTS collection_kind;
--;;

-- Rollback §4
DROP TABLE IF EXISTS subtitles;
--;;
DROP TABLE IF EXISTS metadata_artwork;
--;;
DROP TABLE IF EXISTS metadata_external_ids;
--;;
DROP TABLE IF EXISTS metadata_people;
--;;
DROP TABLE IF EXISTS metadata_studios;
--;;
DROP TABLE IF EXISTS metadata_tags;
--;;
DROP TABLE IF EXISTS metadata_genres;
--;;
DROP TABLE IF EXISTS metadata;
--;;
DROP TYPE  IF EXISTS subtitle_kind;
--;;
DROP TYPE  IF EXISTS artwork_kind;
--;;
DROP TYPE  IF EXISTS person_role;
--;;

-- Rollback §3
DROP TABLE IF EXISTS media_chapters;
--;;
DROP TABLE IF EXISTS media_streams;
--;;
DROP TABLE IF EXISTS media_files;
--;;
DROP TABLE IF EXISTS media_versions;
--;;
DROP TABLE IF EXISTS media_items;
--;;
DROP TYPE  IF EXISTS media_item_state;
--;;
DROP TYPE  IF EXISTS media_item_kind;
--;;
DROP TYPE  IF EXISTS stream_kind;
--;;

-- Rollback §2
DROP TABLE IF EXISTS library_folders;
--;;
DROP TABLE IF EXISTS library_paths;
--;;
DROP TABLE IF EXISTS libraries;
--;;
DROP TABLE IF EXISTS media_sources;
--;;
DROP TYPE  IF EXISTS library_kind;
--;;
DROP TYPE  IF EXISTS media_source_kind;
--;;

-- Rollback §1
DROP TABLE IF EXISTS language_codes;
--;;
DROP TABLE IF EXISTS config;
--;;
