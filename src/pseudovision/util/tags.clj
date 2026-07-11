(ns pseudovision.util.tags
  "Helpers for tag-string normalisation.

   The project encodes all categorisation as tag strings in `metadata_tags`,
   with the category encoded in a string prefix (`channel:<slug>`,
   `time-slot:primetime`, `audience:family`, `genre:comedy`, etc.). The
   `kebab-case` helper produces the canonical *value* portion of a tag from
   a free-form input (e.g. a Jellyfin `Genres` row or an LLM-emitted
   `random:<category>` category).

   Single source of truth — do not duplicate this in callers; import it
   from here instead."
  (:require [clojure.string :as str]))

(defn kebab-case
  "Lowercase, replace `&` with `and`, replace runs of non-alphanumerics with
   a single `-`, and trim leading/trailing `-`. Matches the dimension
   naming convention used by the tag-curation pipeline: spaces and
   punctuation collapse to hyphens, `&` becomes the word `and`.

   `Sci-Fi & Fantasy`   -> `sci-fi-and-fantasy`
   `Action & Adventure` -> `action-and-adventure`
   `Sci-Fi`             -> `sci-fi`
   `Comedy`             -> `comedy`
   `Random`             -> `random`
   nil                  -> empty string"
  [s]
  (if s
    (-> s
        str/lower-case
        (str/replace "&" "and")
        (str/replace #"[^a-z0-9]+" "-")
        (str/replace #"(^-+)|(-+$)" ""))
    ""))
