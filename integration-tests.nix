{ pkgs, pseudovision }:

{
  schedule-integration = pkgs.testers.nixosTest {
    name = "pseudovision-schedule-integration";

    nodes.server = { config, pkgs, ... }: {
      # ── PostgreSQL ────────────────────────────────────────────────
      services.postgresql = {
        enable = true;
        package = pkgs.postgresql_16;
        initialDatabases = [{ name = "pseudovision"; }];
        # Trust local connections so the service can connect without a password
        authentication = ''
          local all all trust
          host  all all 127.0.0.1/32 trust
          host  all all ::1/128      trust
        '';
        initialScript = pkgs.writeText "init.sql" ''
          CREATE ROLE pseudovision WITH LOGIN;
          GRANT ALL PRIVILEGES ON DATABASE pseudovision TO pseudovision;
          \c pseudovision
          GRANT ALL ON SCHEMA public TO pseudovision;
        '';
      };

      # ── Pseudovision service ──────────────────────────────────────
      systemd.services.pseudovision = {
        description = "Pseudovision IPTV scheduling engine";
        after = [ "postgresql.service" ];
        wants = [ "postgresql.service" ];
        environment = {
          PSEUDOVISION_PORT = "8080";
          PSEUDOVISION_DB_URL = "jdbc:postgresql://localhost:5432/pseudovision";
          PSEUDOVISION_DB_USER = "pseudovision";
          PSEUDOVISION_DB_PASS = "";
        };
        serviceConfig = {
          ExecStart = "${pseudovision}/bin/pseudovision";
          Restart = "on-failure";
        };
        wantedBy = [ "multi-user.target" ];
      };

      # ── Firewall / resources ──────────────────────────────────────
      networking.firewall.allowedTCPPorts = [ 8080 ];
      virtualisation.memorySize = 2048;
    };

    testScript = ''
      import json
      import time

      server.start()
      server.wait_for_unit("postgresql.service")
      server.wait_for_unit("pseudovision.service")
      server.wait_for_open_port(8080)

      # Give the service a moment to finish startup/migrations
      time.sleep(2)

      base = "http://localhost:8080"


      # ================================================================
      # Helper: HTTP requests via curl
      # ================================================================
      def api_get(path):
          """GET request, returns parsed JSON."""
          result = server.succeed(
              f"curl -sf {base}{path}"
          )
          return json.loads(result)


      def api_post(path, data):
          """POST request with JSON body, returns parsed JSON."""
          payload = json.dumps(data)
          result = server.succeed(
              f"curl -sf -X POST -H 'Content-Type: application/json' "
              f"-d '{payload}' {base}{path}"
          )
          return json.loads(result)


      def api_put(path, data):
          """PUT request with JSON body, returns parsed JSON."""
          payload = json.dumps(data)
          result = server.succeed(
              f"curl -sf -X PUT -H 'Content-Type: application/json' "
              f"-d '{payload}' {base}{path}"
          )
          return json.loads(result)


      # ================================================================
      # 0. Health check
      # ================================================================
      health = api_get("/health")
      assert health["status"] == "ok", f"Health check failed: {health}"


      # ================================================================
      # 1. Create an FFmpeg profile (required FK for channels)
      # ================================================================
      server.succeed(
          "sudo -u pseudovision psql -d pseudovision -c \""
          "INSERT INTO ffmpeg_profiles (name, config) "
          "VALUES ('default', '{\\\"video_format\\\": \\\"h264\\\"}');\""
      )


      # ================================================================
      # 2. Create a media source, library, library path, and media items
      # ================================================================
      # -- media source
      source = api_post("/api/media/sources", {
          "name": "Test Local",
          "kind": "local"
      })
      source_id = source.get("media_sources/id") or source.get("id")
      assert source_id, f"Failed to create media source: {source}"

      # -- library
      lib = api_post(f"/api/media/sources/{source_id}/libraries", {
          "name": "Movies",
          "kind": "movies"
      })
      lib_id = lib.get("libraries/id") or lib.get("id")
      assert lib_id, f"Failed to create library: {lib}"

      # -- library path  (direct SQL — no scan API for fake files)
      server.succeed(
          f"sudo -u pseudovision psql -d pseudovision -c \""
          f"INSERT INTO library_paths (library_id, path) "
          f"VALUES ({lib_id}, '/media/movies');\""
      )
      lp_row = server.succeed(
          "sudo -u pseudovision psql -t -A -d pseudovision -c \""
          "SELECT id FROM library_paths LIMIT 1;\""
      ).strip()
      lp_id = int(lp_row)

      # -- Insert 10 fake movie media items with versions of varying durations
      #    Durations: 20, 25, 30, 15, 40, 35, 22, 28, 18, 33 minutes
      durations_minutes = [20, 25, 30, 15, 40, 35, 22, 28, 18, 33]
      media_item_ids = []
      for i, dur_min in enumerate(durations_minutes):
          server.succeed(
              f"sudo -u pseudovision psql -d pseudovision -c \""
              f"INSERT INTO media_items (kind, library_path_id) "
              f"VALUES ('movie', {lp_id});\""
          )
          mi_id = server.succeed(
              "sudo -u pseudovision psql -t -A -d pseudovision -c \""
              "SELECT id FROM media_items ORDER BY id DESC LIMIT 1;\""
          ).strip()
          mi_id = int(mi_id)
          media_item_ids.append(mi_id)

          # metadata
          server.succeed(
              f"sudo -u pseudovision psql -d pseudovision -c \""
              f"INSERT INTO metadata (media_item_id, kind, title) "
              f"VALUES ({mi_id}, 'movie', 'Test Movie {i+1}');\""
          )

          # media version with duration
          server.succeed(
              f"sudo -u pseudovision psql -d pseudovision -c \""
              f"INSERT INTO media_versions (media_item_id, name, duration) "
              f"VALUES ({mi_id}, 'Main', INTERVAL '{dur_min} minutes');\""
          )

          # media file (fake path)
          mv_id = server.succeed(
              "sudo -u pseudovision psql -t -A -d pseudovision -c \""
              "SELECT id FROM media_versions ORDER BY id DESC LIMIT 1;\""
          ).strip()
          server.succeed(
              f"sudo -u pseudovision psql -d pseudovision -c \""
              f"INSERT INTO media_files (media_version_id, path, path_hash) "
              f"VALUES ({mv_id}, '/media/movies/movie{i+1}.mkv', 'hash{i+1}');\""
          )

      assert len(media_item_ids) == 10, (
          f"Expected 10 media items, got {len(media_item_ids)}"
      )


      # ================================================================
      # 3. Create collections
      # ================================================================

      # -- Collection A: first 5 movies (shorter items)
      coll_a = api_post("/api/media/collections", {
          "name": "Short Movies",
          "kind": "manual"
      })
      coll_a_id = coll_a.get("collections/id") or coll_a.get("id")
      assert coll_a_id, f"Failed to create collection A: {coll_a}"
      for mi_id in media_item_ids[:5]:
          server.succeed(
              f"sudo -u pseudovision psql -d pseudovision -c \""
              f"INSERT INTO collection_items (collection_id, media_item_id) "
              f"VALUES ({coll_a_id}, {mi_id});\""
          )

      # -- Collection B: last 5 movies (longer items)
      coll_b = api_post("/api/media/collections", {
          "name": "Long Movies",
          "kind": "manual"
      })
      coll_b_id = coll_b.get("collections/id") or coll_b.get("id")
      assert coll_b_id, f"Failed to create collection B: {coll_b}"
      for mi_id in media_item_ids[5:]:
          server.succeed(
              f"sudo -u pseudovision psql -d pseudovision -c \""
              f"INSERT INTO collection_items (collection_id, media_item_id) "
              f"VALUES ({coll_b_id}, {mi_id});\""
          )

      # -- Collection C: all 10 movies
      coll_c = api_post("/api/media/collections", {
          "name": "All Movies",
          "kind": "manual"
      })
      coll_c_id = coll_c.get("collections/id") or coll_c.get("id")
      assert coll_c_id, f"Failed to create collection C: {coll_c}"
      for mi_id in media_item_ids:
          server.succeed(
              f"sudo -u pseudovision psql -d pseudovision -c \""
              f"INSERT INTO collection_items (collection_id, media_item_id) "
              f"VALUES ({coll_c_id}, {mi_id});\""
          )


      # ================================================================
      # 4. Create channels with FFmpeg profile
      # ================================================================
      ffmpeg_id = int(server.succeed(
          "sudo -u pseudovision psql -t -A -d pseudovision -c \""
          "SELECT id FROM ffmpeg_profiles LIMIT 1;\""
      ).strip())

      # -- Channel 1: for "once" + "count" schedule test
      ch1 = api_post("/api/channels", {
          "name": "Test Channel 1",
          "number": "1",
          "sort_number": 1.0,
          "ffmpeg_profile_id": ffmpeg_id
      })
      ch1_id = ch1.get("channels/id") or ch1.get("id")
      assert ch1_id, f"Failed to create channel 1: {ch1}"

      # -- Channel 2: for "block" schedule test
      ch2 = api_post("/api/channels", {
          "name": "Test Channel 2",
          "number": "2",
          "sort_number": 2.0,
          "ffmpeg_profile_id": ffmpeg_id
      })
      ch2_id = ch2.get("channels/id") or ch2.get("id")
      assert ch2_id, f"Failed to create channel 2: {ch2}"

      # -- Channel 3: for "flood" schedule test
      ch3 = api_post("/api/channels", {
          "name": "Test Channel 3",
          "number": "3",
          "sort_number": 3.0,
          "ffmpeg_profile_id": ffmpeg_id
      })
      ch3_id = ch3.get("channels/id") or ch3.get("id")
      assert ch3_id, f"Failed to create channel 3: {ch3}"


      # ================================================================
      # 5. Create schedules with different slot configurations
      # ================================================================

      # ── Schedule 1: "once" slot then "count" slot (3 items) ────────
      sched1 = api_post("/api/schedules", {"name": "Once-Count Schedule"})
      s1_id = sched1.get("schedules/id") or sched1.get("id")
      assert s1_id, f"Failed to create schedule 1: {sched1}"

      api_post(f"/api/schedules/{s1_id}/slots", {
          "slot_index": 0,
          "anchor": "sequential",
          "fill_mode": "once",
          "collection_id": coll_a_id,
          "playback_order": "chronological"
      })
      api_post(f"/api/schedules/{s1_id}/slots", {
          "slot_index": 1,
          "anchor": "sequential",
          "fill_mode": "count",
          "item_count": 3,
          "collection_id": coll_b_id,
          "playback_order": "chronological"
      })

      # ── Schedule 2: "block" slot (2 hour block) ────────────────────
      sched2 = api_post("/api/schedules", {"name": "Block Schedule"})
      s2_id = sched2.get("schedules/id") or sched2.get("id")
      assert s2_id, f"Failed to create schedule 2: {sched2}"

      api_post(f"/api/schedules/{s2_id}/slots", {
          "slot_index": 0,
          "anchor": "sequential",
          "fill_mode": "block",
          "block_duration": "02:00:00",
          "collection_id": coll_c_id,
          "playback_order": "chronological"
      })

      # ── Schedule 3: "flood" between two fixed anchors ──────────────
      sched3 = api_post("/api/schedules", {"name": "Flood Schedule"})
      s3_id = sched3.get("schedules/id") or sched3.get("id")
      assert s3_id, f"Failed to create schedule 3: {sched3}"

      api_post(f"/api/schedules/{s3_id}/slots", {
          "slot_index": 0,
          "anchor": "fixed",
          "start_time": "00:00:00",
          "fill_mode": "flood",
          "collection_id": coll_a_id,
          "playback_order": "chronological"
      })
      api_post(f"/api/schedules/{s3_id}/slots", {
          "slot_index": 1,
          "anchor": "fixed",
          "start_time": "06:00:00",
          "fill_mode": "flood",
          "collection_id": coll_b_id,
          "playback_order": "chronological"
      })
      api_post(f"/api/schedules/{s3_id}/slots", {
          "slot_index": 2,
          "anchor": "fixed",
          "start_time": "12:00:00",
          "fill_mode": "once",
          "collection_id": coll_c_id,
          "playback_order": "shuffle"
      })


      # ================================================================
      # 6. Create playouts and trigger builds
      # ================================================================

      # Wire channel → schedule via playout rows (direct SQL to set schedule_id)
      for (ch_id, s_id) in [(ch1_id, s1_id), (ch2_id, s2_id), (ch3_id, s3_id)]:
          server.succeed(
              f"sudo -u pseudovision psql -d pseudovision -c \""
              f"INSERT INTO playouts (channel_id, schedule_id, seed) "
              f"VALUES ({ch_id}, {s_id}, 42);\""
          )

      # Trigger rebuilds via the API
      for ch_id in [ch1_id, ch2_id, ch3_id]:
          result = server.succeed(
              f"curl -sf -X POST {base}/api/channels/{ch_id}/playout"
          )
          resp = json.loads(result)
          assert "message" in resp or "error" not in resp, (
              f"Rebuild failed for channel {ch_id}: {resp}"
          )

      # Brief pause to let builds complete
      time.sleep(3)


      # ================================================================
      # 7. Fetch events and run coherence checks
      # ================================================================

      from datetime import datetime, timezone


      def parse_iso(s):
          """Parse an ISO-8601 timestamp string to a datetime."""
          if s is None:
              return None
          # Handle various formats the API might return
          s = s.replace("Z", "+00:00")
          return datetime.fromisoformat(s)


      def get_events(ch_id):
          """Fetch all playout events for a channel."""
          data = api_get(f"/api/channels/{ch_id}/playout/events")
          # The API returns a list of event maps
          events = data if isinstance(data, list) else data.get("events", data)
          return events


      def event_start(ev):
          """Extract start timestamp from event (handling namespaced keys)."""
          return (ev.get("playout_events/start_at")
                  or ev.get("start_at")
                  or ev.get("playout_events/start-at")
                  or ev.get("start-at"))


      def event_finish(ev):
          """Extract finish timestamp from event (handling namespaced keys)."""
          return (ev.get("playout_events/finish_at")
                  or ev.get("finish_at")
                  or ev.get("playout_events/finish-at")
                  or ev.get("finish-at"))


      def event_media_id(ev):
          """Extract media_item_id from event."""
          return (ev.get("playout_events/media_item_id")
                  or ev.get("media_item_id")
                  or ev.get("playout_events/media-item-id")
                  or ev.get("media-item-id"))


      def check_events_exist(events, channel_name):
          """Verify that the build produced at least one event."""
          assert len(events) > 0, (
              f"[{channel_name}] No events generated!"
          )
          print(f"[{channel_name}] Generated {len(events)} events ✓")


      def check_chronological_order(events, channel_name):
          """Verify events are in strictly ascending start-time order."""
          for i in range(1, len(events)):
              prev_start = event_start(events[i - 1])
              curr_start = event_start(events[i])
              assert prev_start <= curr_start, (
                  f"[{channel_name}] Events not in chronological order at index {i}: "
                  f"{prev_start} > {curr_start}"
              )
          print(f"[{channel_name}] Chronological order ✓")


      def check_no_overlaps(events, channel_name):
          """Verify no two events overlap in time."""
          for i in range(1, len(events)):
              prev_finish = parse_iso(event_finish(events[i - 1]))
              curr_start = parse_iso(event_start(events[i]))
              if prev_finish is not None and curr_start is not None:
                  assert prev_finish <= curr_start, (
                      f"[{channel_name}] Overlap at index {i}: "
                      f"event {i-1} finishes at {prev_finish} but "
                      f"event {i} starts at {curr_start}"
                  )
          print(f"[{channel_name}] No overlaps ✓")


      def check_positive_durations(events, channel_name):
          """Verify every event has finish_at > start_at."""
          for i, ev in enumerate(events):
              start = parse_iso(event_start(ev))
              finish = parse_iso(event_finish(ev))
              if start is not None and finish is not None:
                  assert finish > start, (
                      f"[{channel_name}] Non-positive duration at index {i}: "
                      f"start={start}, finish={finish}"
                  )
          print(f"[{channel_name}] Positive durations ✓")


      def check_contiguous(events, channel_name):
          """Verify events are back-to-back (no unintended gaps)."""
          for i in range(1, len(events)):
              prev_finish = parse_iso(event_finish(events[i - 1]))
              curr_start = parse_iso(event_start(events[i]))
              if prev_finish is not None and curr_start is not None:
                  assert prev_finish == curr_start, (
                      f"[{channel_name}] Gap between events {i-1} and {i}: "
                      f"finish={prev_finish}, next start={curr_start}"
                  )
          print(f"[{channel_name}] Contiguous (no gaps) ✓")


      def check_valid_media_items(events, valid_ids, channel_name):
          """Verify every event references a known media item."""
          for i, ev in enumerate(events):
              mid = event_media_id(ev)
              if mid is not None:
                  assert int(mid) in valid_ids, (
                      f"[{channel_name}] Event {i} references unknown "
                      f"media_item_id={mid}; valid={valid_ids}"
                  )
          print(f"[{channel_name}] Valid media item references ✓")


      all_valid_ids = set(media_item_ids)


      # ── Channel 1: once + count schedule ───────────────────────────
      # Expect: 1 item from coll_a, then 3 items from coll_b, repeating
      print("\n=== Channel 1: once + count schedule ===")
      ev1 = get_events(ch1_id)
      check_events_exist(ev1, "ch1")
      check_chronological_order(ev1, "ch1")
      check_no_overlaps(ev1, "ch1")
      check_positive_durations(ev1, "ch1")
      check_contiguous(ev1, "ch1")
      check_valid_media_items(ev1, all_valid_ids, "ch1")

      # The schedule repeats in groups of 4 (1 once + 3 count).
      # Check the first cycle: the first event should be from coll_a,
      # the next 3 from coll_b.
      if len(ev1) >= 4:
          coll_a_items = set(media_item_ids[:5])
          coll_b_items = set(media_item_ids[5:])
          first_id = int(event_media_id(ev1[0]))
          assert first_id in coll_a_items, (
              f"[ch1] First event should be from collection A "
              f"(ids {coll_a_items}), got {first_id}"
          )
          for j in range(1, 4):
              mid = int(event_media_id(ev1[j]))
              assert mid in coll_b_items, (
                  f"[ch1] Event {j} should be from collection B "
                  f"(ids {coll_b_items}), got {mid}"
              )
          print("[ch1] Slot-to-collection mapping verified ✓")


      # ── Channel 2: block schedule ──────────────────────────────────
      # Expect: events filling 2-hour blocks, no event extends past its
      # block boundary.
      print("\n=== Channel 2: block schedule ===")
      ev2 = get_events(ch2_id)
      check_events_exist(ev2, "ch2")
      check_chronological_order(ev2, "ch2")
      check_no_overlaps(ev2, "ch2")
      check_positive_durations(ev2, "ch2")
      check_contiguous(ev2, "ch2")
      check_valid_media_items(ev2, all_valid_ids, "ch2")

      # Verify block constraint: each group of events from a single
      # block iteration should span <= 2 hours.
      if len(ev2) >= 2:
          block_dur_seconds = 2 * 3600  # 2 hours

          # The first event's start is the block start.  Walk forward
          # and verify that every event stays within 2-hour windows.
          block_start = parse_iso(event_start(ev2[0]))
          from datetime import timedelta
          block_end = block_start + timedelta(seconds=block_dur_seconds)

          for i, ev in enumerate(ev2):
              ev_finish = parse_iso(event_finish(ev))
              ev_start_dt = parse_iso(event_start(ev))
              # If this event starts at or after the current block_end,
              # it's in the next block.
              if ev_start_dt >= block_end:
                  block_start = ev_start_dt
                  block_end = block_start + timedelta(
                      seconds=block_dur_seconds
                  )
              # The event must not extend past the block boundary
              assert ev_finish <= block_end + timedelta(seconds=1), (
                  f"[ch2] Event {i} finish {ev_finish} exceeds block "
                  f"boundary {block_end}"
              )
          print("[ch2] Block duration constraints respected ✓")


      # ── Channel 3: flood schedule ──────────────────────────────────
      print("\n=== Channel 3: flood schedule ===")
      ev3 = get_events(ch3_id)
      check_events_exist(ev3, "ch3")
      check_chronological_order(ev3, "ch3")
      check_no_overlaps(ev3, "ch3")
      check_positive_durations(ev3, "ch3")
      check_valid_media_items(ev3, all_valid_ids, "ch3")

      # Flood slots should produce multiple events (more than 1)
      assert len(ev3) > 1, (
          f"[ch3] Flood schedule should generate multiple events, "
          f"got {len(ev3)}"
      )
      print("[ch3] Flood schedule generated multiple events ✓")


      # ================================================================
      # 8. Verify EPG / M3U output endpoints work
      # ================================================================
      print("\n=== Output endpoint checks ===")

      # M3U playlist should list all 3 channels
      m3u_out = server.succeed(f"curl -sf {base}/iptv/channels.m3u")
      assert "#EXTM3U" in m3u_out, "M3U output missing #EXTM3U header"
      assert "Test Channel 1" in m3u_out, "M3U missing channel 1"
      assert "Test Channel 2" in m3u_out, "M3U missing channel 2"
      assert "Test Channel 3" in m3u_out, "M3U missing channel 3"
      print("[output] M3U playlist contains all channels ✓")

      # XMLTV EPG should be valid XML with programme elements
      epg_out = server.succeed(f"curl -sf {base}/xmltv")
      assert "<?xml" in epg_out or "<tv" in epg_out, (
          "XMLTV output is not valid XML"
      )
      print("[output] XMLTV EPG returns valid XML ✓")

      # Lineup JSON should list channels
      lineup = json.loads(server.succeed(
          f"curl -sf {base}/lineup.json"
      ))
      assert isinstance(lineup, list), "Lineup should be a JSON array"
      assert len(lineup) >= 3, (
          f"Lineup should have >= 3 channels, got {len(lineup)}"
      )
      print("[output] Lineup JSON lists all channels ✓")


      # ================================================================
      # 9. Cross-channel: verify no playout references cross channels
      # ================================================================
      print("\n=== Cross-channel isolation ===")
      for ch_id, ch_name in [(ch1_id, "ch1"), (ch2_id, "ch2"), (ch3_id, "ch3")]:
          playout = api_get(f"/api/channels/{ch_id}/playout")
          playout_id = (playout.get("playouts/id")
                        or playout.get("id"))
          playout_ch = (playout.get("playouts/channel_id")
                        or playout.get("channel_id")
                        or playout.get("playouts/channel-id")
                        or playout.get("channel-id"))
          if playout_ch is not None:
              assert int(playout_ch) == int(ch_id), (
                  f"[{ch_name}] Playout channel_id mismatch: "
                  f"expected {ch_id}, got {playout_ch}"
              )
      print("[cross-channel] Playout isolation verified ✓")


      # ================================================================
      # 10. Summary
      # ================================================================
      print("\n" + "=" * 60)
      print("ALL SCHEDULE INTEGRATION TESTS PASSED")
      print("=" * 60)
    '';
  };
}
