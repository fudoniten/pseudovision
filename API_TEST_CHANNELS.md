# Test Channels API

Remote API endpoints for creating and managing test channels for streaming verification.

## Endpoints

### `GET /api/test/info`

Get information about available collections and API usage examples.

**Response:**
```json
{
  "collections": [
    {
      "collections/id": 1,
      "collections/name": "Movies",
      "..."
    }
  ],
  "default_collection": {...},
  "endpoints": {
    "create": "http://localhost:8080/api/test/channels",
    "list": "http://localhost:8080/api/test/channels",
    "delete": "http://localhost:8080/api/test/channels/:identifier",
    "info": "http://localhost:8080/api/test/info"
  },
  "usage": {
    "create": {
      "method": "POST",
      "url": "http://localhost:8080/api/test/channels",
      "body": {
        "number": "999",
        "name": "My Test Channel",
        "collection_id": 1
      }
    },
    "delete": {
      "method": "DELETE",
      "url": "http://localhost:8080/api/test/channels/999"
    },
    "list": {
      "method": "GET",
      "url": "http://localhost:8080/api/test/channels"
    }
  }
}
```

**Example:**
```bash
curl http://localhost:8080/api/test/info | jq
```

---

### `POST /api/test/channels`

Create a test channel for streaming verification.

**Request Body (all fields optional):**
```json
{
  "number": "999",           // Channel number (default: "999")
  "name": "Test Channel",    // Channel name (default: "Test Channel")
  "collection_id": 5         // Collection ID (default: first available)
}
```

**Response (201 Created):**
```json
{
  "channel": {
    "channels/id": 123,
    "channels/uuid": "550e8400-e29b-41d4-a716-446655440000",
    "channels/number": "999",
    "channels/name": "Test Channel",
    "..."
  },
  "schedule": {...},
  "playout": {...},
  "uuid": "550e8400-e29b-41d4-a716-446655440000",
  "stream_url": "http://localhost:8080/stream/550e8400-e29b-41d4-a716-446655440000",
  "playlist_url": "http://localhost:8080/iptv/channels.m3u",
  "epg_url": "http://localhost:8080/xmltv"
}
```

**Examples:**

Create with default settings:
```bash
curl -X POST http://localhost:8080/api/test/channels \
  -H "Content-Type: application/json" | jq
```

Create with custom settings:
```bash
curl -X POST http://localhost:8080/api/test/channels \
  -H "Content-Type: application/json" \
  -d '{
    "number": "998",
    "name": "My Stream Test",
    "collection_id": 3
  }' | jq
```

Save UUID for later:
```bash
UUID=$(curl -X POST http://localhost:8080/api/test/channels \
  -H "Content-Type: application/json" \
  -d '{"number": "999"}' | jq -r '.uuid')

echo "Stream URL: http://localhost:8080/stream/$UUID"
```

---

### `GET /api/test/channels`

List all test channels (channels with "Test" in name or in "Testing" group).

**Response (200 OK):**
```json
{
  "channels": [
    {
      "channels/id": 123,
      "channels/uuid": "550e8400-e29b-41d4-a716-446655440000",
      "channels/number": "999",
      "channels/name": "Test Channel",
      "channels/group_name": "Testing",
      "..."
    }
  ],
  "count": 1
}
```

**Example:**
```bash
curl http://localhost:8080/api/test/channels | jq
```

---

### `DELETE /api/test/channels/:identifier`

Delete a test channel by UUID or channel number.

**Path Parameters:**
- `identifier` - Channel UUID or channel number (e.g., "999")

**Response (200 OK):**
```json
{
  "deleted": true,
  "channel": {
    "channels/id": 123,
    "channels/uuid": "550e8400-e29b-41d4-a716-446655440000",
    "channels/number": "999",
    "channels/name": "Test Channel",
    "..."
  },
  "message": "Deleted channel: Test Channel"
}
```

**Response (404 Not Found):**
```json
{
  "deleted": false,
  "error": "Channel not found",
  "identifier": "999"
}
```

**Examples:**

Delete by channel number:
```bash
curl -X DELETE http://localhost:8080/api/test/channels/999 | jq
```

Delete by UUID:
```bash
curl -X DELETE http://localhost:8080/api/test/channels/550e8400-e29b-41d4-a716-446655440000 | jq
```

Delete using saved UUID variable:
```bash
curl -X DELETE http://localhost:8080/api/test/channels/$UUID | jq
```

---

## Complete Workflow Example

```bash
#!/bin/bash
# Complete test channel workflow

BASE_URL="http://localhost:8080"

# 1. Check available collections
echo "Checking available collections..."
curl -s $BASE_URL/api/test/info | jq '.collections[] | {id: .["collections/id"], name: .["collections/name"]}'

# 2. Create a test channel
echo -e "\nCreating test channel..."
RESPONSE=$(curl -s -X POST $BASE_URL/api/test/channels \
  -H "Content-Type: application/json" \
  -d '{
    "number": "999",
    "name": "Stream Test",
    "collection_id": 1
  }')

# 3. Extract info
UUID=$(echo $RESPONSE | jq -r '.uuid')
STREAM_URL=$(echo $RESPONSE | jq -r '.stream_url')

echo "✅ Channel created!"
echo "UUID: $UUID"
echo "Stream URL: $STREAM_URL"

# 4. Test the stream
echo -e "\nTesting playlist..."
curl -s $STREAM_URL | head -10

# 5. Verify in M3U
echo -e "\nChecking M3U playlist..."
curl -s $BASE_URL/iptv/channels.m3u | grep "999"

# 6. Verify in EPG
echo -e "\nChecking EPG..."
curl -s $BASE_URL/xmltv | grep "Test" | head -5

# 7. Play in VLC (optional)
echo -e "\nYou can now test playback:"
echo "  vlc $STREAM_URL"

# 8. Clean up when done
read -p "Press Enter to delete test channel..."
curl -s -X DELETE $BASE_URL/api/test/channels/$UUID | jq

echo "✅ Test complete!"
```

---

## Testing Workflow

### Quick Test
```bash
# Create, get UUID, test, delete
UUID=$(curl -s -X POST http://localhost:8080/api/test/channels \
  -H "Content-Type: application/json" \
  -d '{"number":"999"}' | jq -r '.uuid')

echo "Stream: http://localhost:8080/stream/$UUID"

# Test in VLC
vlc "http://localhost:8080/stream/$UUID"

# Delete when done
curl -X DELETE "http://localhost:8080/api/test/channels/$UUID"
```

### Persistent Test Channel
```bash
# Create a channel and keep it
curl -X POST http://localhost:8080/api/test/channels \
  -H "Content-Type: application/json" \
  -d '{
    "number": "998",
    "name": "Persistent Test"
  }' | jq -r '.stream_url'

# Test multiple times...

# List all test channels
curl http://localhost:8080/api/test/channels | jq '.channels[] | .["channels/number"]'

# Delete by number when done
curl -X DELETE http://localhost:8080/api/test/channels/998
```

### Cleanup All Test Channels
```bash
# Get all test channel numbers
curl -s http://localhost:8080/api/test/channels | \
  jq -r '.channels[] | .["channels/number"]' | \
  while read number; do
    echo "Deleting channel $number..."
    curl -s -X DELETE "http://localhost:8080/api/test/channels/$number" | jq '.message'
  done
```

---

## Integration with Testing Plan

Use the API to automate the testing plan from `TESTING_STREAMING.md`:

```bash
# Phase 1: Setup
UUID=$(curl -s -X POST http://localhost:8080/api/test/channels \
  -H "Content-Type: application/json" \
  -d '{"number":"999"}' | jq -r '.uuid')

# Phase 2: Test playlist
curl "http://localhost:8080/stream/$UUID"

# Phase 3: Test segment
curl "http://localhost:8080/stream/$UUID/segment-000.ts" -o /tmp/test.ts
file /tmp/test.ts

# Phase 4: Test playback
vlc "http://localhost:8080/stream/$UUID"

# Phase 5: Cleanup
curl -X DELETE "http://localhost:8080/api/test/channels/$UUID"
```

---

## Error Handling

### No Collections Available
```json
{
  "error": "Failed to create test channel",
  "message": "No collections found. Create a collection first or specify :collection-id"
}
```

**Solution:** Create a media source and collection first:
```bash
# Add media source
curl -X POST http://localhost:8080/api/media/sources \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Jellyfin",
    "kind": "jellyfin",
    "base_url": "http://jellyfin:8096",
    "api_key": "your-api-key"
  }'

# Add collection
curl -X POST http://localhost:8080/api/media/collections \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Collection"
  }'
```

### Channel Already Exists
If a channel with number "999" already exists:
```json
{
  "error": "Failed to create test channel",
  "message": "ERROR: duplicate key value violates unique constraint..."
}
```

**Solution:** Use a different channel number or delete the existing one:
```bash
curl -X DELETE http://localhost:8080/api/test/channels/999
```

---

## Remote Access

If accessing from a remote machine:

```bash
# Replace localhost with actual hostname/IP
BASE_URL="http://pseudovision.example.com:8080"

curl -X POST $BASE_URL/api/test/channels \
  -H "Content-Type: application/json" \
  -d '{"number":"999"}' | jq
```

The API will automatically detect the correct host from request headers and return the appropriate URLs in the response.

---

## Notes

- Test channels are created in the "Testing" group
- They use "hls_segmenter" streaming mode
- Schedule uses "random" playback order from the collection
- Playout is automatically built on creation
- Currently streams from test URL (Mux test stream)
- Will be updated to stream actual media from playout in Phase 1

---

## See Also

- `TESTING_STREAMING.md` - Comprehensive testing procedures
- `src/pseudovision/dev/test_channel.clj` - REPL utility implementation
- `src/pseudovision/http/api/test.clj` - API endpoint implementation
