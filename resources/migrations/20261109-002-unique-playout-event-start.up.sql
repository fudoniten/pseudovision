-- One generated event per start time on a playout.
--
-- Two builds writing the same playout at once each deleted the future and
-- inserted their own copy of the timeline, so every event appeared twice
-- (Spotlight, November 2026). Builds now take a per-playout advisory lock, and
-- this index makes a second copy impossible: the insert fails and the build
-- rolls back instead of doubling the schedule. Manual events are left out,
-- because the builder doesn't pack around them.
--
-- Existing duplicates are removed first, keeping the earliest row of each.

DELETE FROM playout_events a
 USING playout_events b
 WHERE a.playout_id = b.playout_id
   AND a.start_at   = b.start_at
   AND NOT a.is_manual
   AND NOT b.is_manual
   AND a.id > b.id;
--;;

-- Trim filler that runs into the next generated event so it ends where that
-- event starts. Builds now do this themselves; this cleans up the timelines
-- already written (the post breaks that ended a few seconds past every hour).
UPDATE playout_events e
   SET finish_at = n.next_start
  FROM (SELECT id,
               LEAD(start_at) OVER (PARTITION BY playout_id
                                    ORDER BY start_at, finish_at, id) AS next_start
          FROM playout_events
         WHERE NOT is_manual) n
 WHERE e.id = n.id
   AND e.kind IN ('pre', 'mid', 'post', 'pad', 'tail', 'fallback', 'bumper')
   AND n.next_start IS NOT NULL
   AND n.next_start > e.start_at
   AND e.finish_at  > n.next_start;
--;;

CREATE UNIQUE INDEX ux_playout_events_generated_start
    ON playout_events (playout_id, start_at)
 WHERE NOT is_manual;
