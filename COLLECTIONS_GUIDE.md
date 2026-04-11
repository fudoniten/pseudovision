# Collections Guide

## What are Collections?

**Collections** are logical groupings of media items that can be scheduled for playback on channels. Think of them as playlists or curated lists of content.

### Collection Types

Pseudovision supports several collection types:

- **`manual`** - Explicit list of media items you add manually
- **`smart`** - Search query evaluated at build time (dynamic)
- **`playlist`** - Ordered list of collections with settings
- **`multi`** - Union of other collections
- **`trakt`** - Synced from Trakt.tv lists
- **`rerun`** - First-run → rerun wrapper

For testing purposes, **`manual`** collections are the simplest.

---

## Creating Collections

### Prerequisites

Before creating collections, you need:
1. ✅ A **media source** (e.g., Jellyfin) configured
2. ✅ At least one **library** from that source
3. ✅ **Media items** scanned from the library

---

## Step-by-Step: Create Your First Collection

### Step 1: Add a Media Source (Jellyfin)

```bash
curl -X POST http://localhost:8080/api/media/sources \
  -H "Content-Type: application/json" \
  -d '{
    "name": "My Jellyfin",
    "kind": "jellyfin",
    "base_url": "http://jellyfin:8096",
    "api_key": "your-jellyfin-api-key-here"
  }' | jq
```

**Response:**
```json
{
  "media-sources/id": 1,
  "media-sources/name": "My Jellyfin",
  "media-sources/kind": "jellyfin",
  ...
}
```

Save the `media-sources/id` (e.g., `1`).

---

### Step 2: Discover Libraries

```bash
# Replace {source-id} with your media source ID
curl -X POST http://localhost:8080/api/media/sources/1/libraries/discover | jq
```

This will discover all libraries from your Jellyfin server.

**Response:**
```json
{
  "discovered": 3,
  "libraries": [
    {
      "libraries/id": 1,
      "libraries/name": "Movies",
      "libraries/remote-key": "abc123",
      ...
    },
    {
      "libraries/id": 2,
      "libraries/name": "TV Shows",
      ...
    }
  ]
}
```

---

### Step 3: Scan a Library

```bash
# Scan the Movies library (replace with your library ID)
curl -X POST http://localhost:8080/api/media/libraries/1/scan | jq
```

This will scan all media items from that library into Pseudovision's database.

**Check scan progress:**
```bash
curl http://localhost:8080/api/media/libraries/1/items | jq '.[] | {title: .["metadata/title"], id: .["media-items/id"]}'
```

---

### Step 4: Create a Manual Collection

Now that you have media items, create a collection:

```bash
curl -X POST http://localhost:8080/api/media/collections \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Movies",
    "kind": "manual"
  }' | jq
```

**Response:**
```json
{
  "collections/id": 1,
  "collections/name": "Test Movies",
  "collections/kind": "manual",
  "collections/use_custom_playback_order": false,
  "collections/config": {}
}
```

Save the `collections/id` (e.g., `1`).

---

### Step 5: Add Media Items to Collection

```bash
# You'll need to make a small script or add items one by one
# First, get some media item IDs:
curl http://localhost:8080/api/media/libraries/1/items | jq '.[] | .["media-items/id"]' | head -10

# Then add them to the collection (no direct API endpoint exists yet, so this would need to be done via database or REPL)
```

**Note:** There doesn't appear to be a direct API endpoint for adding items to collections yet. You can either:

1. Add items via SQL:
   ```sql
   INSERT INTO collection_items (collection_id, media_item_id) VALUES (1, 123);
   INSERT INTO collection_items (collection_id, media_item_id) VALUES (1, 124);
   -- etc.
   ```

2. Or use a **smart collection** instead (see below)

---

## Alternative: Create a Smart Collection

Smart collections use a search query to dynamically include media items. This is easier than manually adding items:

```bash
curl -X POST http://localhost:8080/api/media/collections \
  -H "Content-Type: application/json" \
  -d '{
    "name": "All Movies",
    "kind": "smart",
    "config": {
      "query": {
        "media_type": "movie"
      }
    }
  }' | jq
```

**Config options for smart collections:**
- Filter by media type: `{"media_type": "movie"}` or `{"media_type": "episode"}`
- Filter by library: `{"library_id": 1}`
- Filter by title pattern: `{"title_pattern": "%Star%"}`

---

## Quick Start for Testing

If you just want to get started quickly:

### Option A: Create Collection with ALL Media

```bash
# 1. Create media source
curl -X POST http://localhost:8080/api/media/sources \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Jellyfin",
    "kind": "jellyfin",
    "base_url": "http://jellyfin:8096",
    "api_key": "YOUR_API_KEY"
  }' | jq -r '.["media-sources/id"]'
# Save this ID

# 2. Discover libraries
curl -X POST http://localhost:8080/api/media/sources/1/libraries/discover | jq

# 3. Scan first library
curl -X POST http://localhost:8080/api/media/libraries/1/scan | jq

# 4. Create a smart collection that includes ALL items
curl -X POST http://localhost:8080/api/media/collections \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Everything",
    "kind": "smart",
    "config": {}
  }' | jq -r '.["collections/id"]'
# Save this collection ID

# 5. Now create test channel with this collection
curl -X POST http://localhost:8080/api/test/channels \
  -H "Content-Type: application/json" \
  -d '{
    "number": "999",
    "name": "Test Stream",
    "collection_id": 1
  }' | jq -r '.stream_url'
```

### Option B: Add Collection Items via Database

If you have database access:

```sql
-- Create a manual collection
INSERT INTO collections (kind, name, config) 
VALUES ('manual', 'Test Collection', '{}') 
RETURNING id;

-- Add all media items from a library to the collection
INSERT INTO collection_items (collection_id, media_item_id)
SELECT 1, id FROM media_items WHERE library_id = 1;
```

---

## Verify Your Collections

```bash
# List all collections
curl http://localhost:8080/api/media/collections | jq '.[] | {id: .["collections/id"], name: .["collections/name"], kind: .["collections/kind"]}'

# Get collection info (if endpoint exists)
curl http://localhost:8080/api/media/collections/1 | jq
```

---

## Using Collections in Test Channels

Once you have a collection, specify it when creating a test channel:

```bash
curl -X POST http://localhost:8080/api/test/channels \
  -H "Content-Type: application/json" \
  -d '{
    "number": "999",
    "name": "My Test Stream",
    "collection_id": 1
  }' | jq
```

This creates a channel that will play random items from that collection in a continuous loop.

---

## Troubleshooting

### "No collections found"

**Problem:** No collections exist in the database.

**Solution:** Create at least one collection (see steps above).

---

### "No media items in collection"

**Problem:** Collection exists but has no items.

**Solution:**
1. Make sure you've scanned a library
2. Add items to your manual collection
3. Or use a smart collection with a query that matches your media

---

### "Cannot connect to Jellyfin"

**Problem:** Media source connection failed.

**Solution:**
1. Verify Jellyfin URL is accessible: `curl http://jellyfin:8096`
2. Check API key is valid
3. Ensure Jellyfin server is running
4. Check network connectivity from Pseudovision container

---

## Collection Structure Reference

```json
{
  "collections/id": 1,
  "collections/kind": "manual|smart|playlist|multi|trakt|rerun",
  "collections/name": "My Collection",
  "collections/use_custom_playback_order": false,
  "collections/config": {
    // Kind-specific configuration
    // For smart: {"query": {...}}
    // For multi: {"collection_ids": [1, 2, 3]}
    // For playlist: {"items": [...]}
  }
}
```

---

## Next Steps

After creating collections:

1. ✅ Create test channels with `POST /api/test/channels`
2. ✅ Test streaming with VLC or browser
3. ✅ Create schedules with multiple collections
4. ✅ Set up actual channels for production use

---

## See Also

- `API_TEST_CHANNELS.md` - Test channel API documentation
- `TESTING_STREAMING.md` - Comprehensive testing procedures
- Database schema: `resources/migrations/20260225-001-initial-schema.up.sql`
