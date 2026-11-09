-- Every media item's effective tags: its own plus those of its parent and
-- grandparent. An episode of Seinfeld carries every tag on the show (genres,
-- channel:<slug>, curated categories) and on its season, plus any tag of its
-- own.
--
-- Tags are written at the show level (Jellyfin genres, Tunarr Scheduler's
-- channel and category curation), so filters that looked only at an episode's
-- own metadata found nothing: Sitcom Spectrum's 42 shows had 3,133 episodes
-- and none of them matched channel:spectrum. Resolving through this view keeps
-- inheritance live, so no backfill is needed and a retagged show is picked up
-- immediately. The hierarchy is at most three deep (show > season > episode).

CREATE OR REPLACE VIEW media_item_effective_tags AS
SELECT m.media_item_id, mt.name
  FROM metadata m
  JOIN metadata_tags mt ON mt.metadata_id = m.id
UNION
SELECT c.id AS media_item_id, mt.name
  FROM media_items c
  JOIN metadata m       ON m.media_item_id = c.parent_id
  JOIN metadata_tags mt ON mt.metadata_id = m.id
UNION
SELECT c.id AS media_item_id, mt.name
  FROM media_items c
  JOIN media_items p    ON p.id = c.parent_id
  JOIN metadata m       ON m.media_item_id = p.parent_id
  JOIN metadata_tags mt ON mt.metadata_id = m.id;
