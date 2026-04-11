-- Create a test collection with all media items
-- Run this script to quickly create a collection for testing

-- 1. Create a manual collection
INSERT INTO collections (kind, name, config) 
VALUES ('manual', 'All Media (Test)', '{}') 
RETURNING id;

-- Note the collection ID from above (let's assume it's 1)
-- Replace '1' in the next query with your actual collection ID

-- 2. Add ALL media items to this collection
-- IMPORTANT: Replace '1' with the collection ID from step 1
INSERT INTO collection_items (collection_id, media_item_id)
SELECT 1, id FROM media_items
ON CONFLICT (collection_id, media_item_id) DO NOTHING;

-- 3. Verify the collection has items
SELECT 
    c.id,
    c.name,
    COUNT(ci.media_item_id) as item_count
FROM collections c
LEFT JOIN collection_items ci ON c.id = ci.collection_id
WHERE c.id = 1  -- Replace with your collection ID
GROUP BY c.id, c.name;
