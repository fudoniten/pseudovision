(ns pseudovision.http.schemas
  "Malli schemas for request/response coercion and OpenAPI generation.

   Response schemas are `open maps` (malli's default) so new DB columns flow
   through automatically without silently stripping data. Create/Update request
   schemas are also open; tighten with `:closed` per-field once every caller is
   known.")

;; ---------------------------------------------------------------------------
;; Primitives
;; ---------------------------------------------------------------------------

(def ChannelId [:int {:min 1}])

(def ChannelNumber
  [:string {:min 1 :description "Channel number, e.g. \"2\" or \"4.1\""}])

(def SortNumber
  [:double {:description "Numeric sort order; derived from :number if omitted on create"}])

(def StreamingMode
  [:enum {:description "Output stream format"} "ts" "hls"])

;; ---------------------------------------------------------------------------
;; Error envelope
;; ---------------------------------------------------------------------------

(def Error
  [:map
   [:error :string]])

(def CoercionError
  "Structured 400 returned when request coercion fails."
  [:map
   [:error    :string]
   [:in       [:vector :any]]
   [:humanized {:optional true} :any]])

;; ---------------------------------------------------------------------------
;; Channel — GET/POST/PUT response shape (unqualified keys)
;; ---------------------------------------------------------------------------

(def Channel
  [:map
   [:id                             ChannelId]
   [:uuid                           :uuid]
   [:number                         ChannelNumber]
   [:sort-number                    SortNumber]
   [:name                           :string]
   [:group-name                     {:optional true} [:maybe :string]]
   [:categories                     {:optional true} [:maybe :string]]
   [:description                    {:optional true} [:maybe :string]]
   [:streaming-mode                 StreamingMode]
   [:ffmpeg-profile-id              :int]
   [:watermark-id                   {:optional true} [:maybe :int]]
   [:fallback-filler-id             {:optional true} [:maybe :int]]
   [:preferred-audio-language       {:optional true} [:maybe :string]]
   [:preferred-audio-title          {:optional true} [:maybe :string]]
   [:preferred-subtitle-language    {:optional true} [:maybe :string]]
   [:subtitle-mode                  :string]
   [:music-video-credits-mode       {:optional true} [:maybe :string]]
   [:music-video-credits-template   {:optional true} [:maybe :string]]
   [:song-video-mode                {:optional true} [:maybe :string]]
   [:slug-seconds                   {:optional true} [:maybe :double]]
   [:stream-selector-mode           :string]
   [:stream-selector                {:optional true} [:maybe :string]]
   [:is-enabled                     :boolean]
   [:show-in-epg                    :boolean]])

;; ---------------------------------------------------------------------------
;; Channel — POST request body
;;
;; Only :number and :name are required; everything else has a DB default or
;; is nullable. :uuid may be provided to pin a stable identifier, else the DB
;; generates one. :sort-number is derived from :number if omitted.
;; ---------------------------------------------------------------------------

(def ChannelCreate
  [:map
   [:number                         ChannelNumber]
   [:name                           :string]
   [:uuid                           {:optional true} :uuid]
   [:sort-number                    {:optional true} SortNumber]
   [:group-name                     {:optional true} [:maybe :string]]
   [:categories                     {:optional true} [:maybe :string]]
   [:description                    {:optional true} [:maybe :string]]
   [:streaming-mode                 {:optional true} StreamingMode]
   [:ffmpeg-profile-id              {:optional true} :int]
   [:watermark-id                   {:optional true} [:maybe :int]]
   [:fallback-filler-id             {:optional true} [:maybe :int]]
   [:preferred-audio-language       {:optional true} [:maybe :string]]
   [:preferred-audio-title          {:optional true} [:maybe :string]]
   [:preferred-subtitle-language    {:optional true} [:maybe :string]]
   [:subtitle-mode                  {:optional true} :string]
   [:music-video-credits-mode       {:optional true} [:maybe :string]]
   [:music-video-credits-template   {:optional true} [:maybe :string]]
   [:song-video-mode                {:optional true} [:maybe :string]]
   [:slug-seconds                   {:optional true} [:maybe :double]]
   [:stream-selector-mode           {:optional true} :string]
   [:stream-selector                {:optional true} [:maybe :string]]
   [:is-enabled                     {:optional true} :boolean]
   [:show-in-epg                    {:optional true} :boolean]])

;; ---------------------------------------------------------------------------
;; Channel — PUT request body (all fields optional)
;; ---------------------------------------------------------------------------

(def ChannelUpdate
  [:map
   [:number                         {:optional true} ChannelNumber]
   [:name                           {:optional true} :string]
   [:sort-number                    {:optional true} SortNumber]
   [:group-name                     {:optional true} [:maybe :string]]
   [:categories                     {:optional true} [:maybe :string]]
   [:description                    {:optional true} [:maybe :string]]
   [:streaming-mode                 {:optional true} StreamingMode]
   [:ffmpeg-profile-id              {:optional true} :int]
   [:watermark-id                   {:optional true} [:maybe :int]]
   [:fallback-filler-id             {:optional true} [:maybe :int]]
   [:preferred-audio-language       {:optional true} [:maybe :string]]
   [:preferred-audio-title          {:optional true} [:maybe :string]]
   [:preferred-subtitle-language    {:optional true} [:maybe :string]]
   [:subtitle-mode                  {:optional true} :string]
   [:music-video-credits-mode       {:optional true} [:maybe :string]]
   [:music-video-credits-template   {:optional true} [:maybe :string]]
   [:song-video-mode                {:optional true} [:maybe :string]]
   [:slug-seconds                   {:optional true} [:maybe :double]]
   [:stream-selector-mode           {:optional true} :string]
   [:stream-selector                {:optional true} [:maybe :string]]
   [:is-enabled                     {:optional true} :boolean]
   [:show-in-epg                    {:optional true} :boolean]])
