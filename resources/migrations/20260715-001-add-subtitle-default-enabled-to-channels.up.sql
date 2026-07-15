-- Add subtitle_default_enabled to channels
--
-- Channels already carry preferred_audio_language / preferred_subtitle_language
-- / subtitle_mode. This flag decides whether the preferred subtitle language
-- is burned in whenever it's available, or only as a fallback when the
-- preferred spoken (audio) language could not be found on the item currently
-- playing.

ALTER TABLE channels
  ADD COLUMN subtitle_default_enabled BOOLEAN NOT NULL DEFAULT TRUE;

--;;

COMMENT ON COLUMN channels.subtitle_default_enabled IS
  'true = burn in the preferred subtitle language whenever present; false = only as a fallback when the preferred audio language is unavailable.';

--;;
