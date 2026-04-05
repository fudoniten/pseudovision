(ns pseudovision.media.connection
  "Multimethods for reading and writing media source connection configs.

   Each remote source type (jellyfin, plex, emby) implements two multimethods
   that translate between flat request params and the `connection_config` JSONB
   column in `media_sources`.

   Dispatch is on the source kind string (\"jellyfin\", \"plex\", etc.).")

;; ---------------------------------------------------------------------------
;; Shared helper
;; ---------------------------------------------------------------------------

(defn active-uri
  "Returns the URI of the preferred connection from a connections list,
   falling back to the first entry."
  [connections]
  (or (:uri (first (filter :is_active connections)))
      (:uri (first connections))))

;; ---------------------------------------------------------------------------
;; Write: request params → connection_config JSONB map
;; ---------------------------------------------------------------------------

(defmulti ->connection-config
  "Builds a connection_config map from request body params for a given source kind.
   Returns nil for source types with no connection config (e.g. local)."
  :kind)

(defmethod ->connection-config :default [_] nil)

;; ---------------------------------------------------------------------------
;; Read: media_sources row → connection map
;; ---------------------------------------------------------------------------

(defmulti <-connection-config
  "Extracts a connection map from a media_sources row.
   Returns {:base-url \"...\" :api-key \"...\"}."
  (fn [source] (or (:media-sources/kind source) (:kind source))))

(defmethod <-connection-config :default [_] nil)
