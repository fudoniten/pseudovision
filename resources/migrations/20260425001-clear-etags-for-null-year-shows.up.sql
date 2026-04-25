-- Clear etags for shows missing a production year so they re-sync and pick up
-- a year derived from PremiereDate on the next Jellyfin scan.

UPDATE media_items
SET    remote_etag = NULL
WHERE  kind = 'show'
  AND  id IN (
         SELECT mi.id
         FROM   media_items mi
         LEFT   JOIN metadata m ON m.media_item_id = mi.id
         WHERE  mi.kind = 'show'
           AND  m.year IS NULL
       );
--;;
