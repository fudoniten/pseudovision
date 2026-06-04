(ns pseudovision.util.pagination
  "Utilities for paginated API responses.")

(defn offset-pagination-response
  "Wraps items in an offset-based pagination envelope.
   
   Args:
   - items: The collection of items for this page
   - limit: The requested page size
   - offset: The starting offset
   - total: Total count of all items (across all pages)"
  [items limit offset total]
  {:items items
   :pagination {:limit limit
                :offset offset
                :total total
                :has_more (> total (+ offset (count items)))}})

(defn cursor-pagination-response
  "Wraps items in a cursor-based pagination envelope.
   
   Args:
   - items: The collection of items for this page
   - limit: The requested page size
   - cursor-fn: Function to extract cursor value from last item, or nil if no more
   
   The cursor-fn should return nil when there are no more items."
  [items limit cursor-fn]
  (let [next-cursor (when (= (count items) limit)
                      (cursor-fn (last items)))]
    {:items items
     :pagination {:limit limit
                  :has_more (some? next-cursor)
                  :next_cursor next-cursor}}))
