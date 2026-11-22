# Prophecy Verse V3 Verified API Contract

Verified directly against `https://prophecy-verse-v3-backend.onrender.com` on 2026-11-22.

## 1. Session Lifecycle
- `GET /api/v1/anime/search?q={query}` returns `"session_id": "..."` in JSON body.
- Subsequent `/api/v1/anime/*`, `/api/v1/seasons/*`, `/api/v1/episodes/*` requests require header:
  `X-PV-Session: {session_id}`
- Missing/invalid/expired sessions return HTTP `401` (`SESSION_REQUIRED` / `SESSION_EXPIRED`) or `400` (`SESSION_INVALID`).

## 2. Endpoints
- **`GET /api/v1/anime/search?q={query}`**: Returns `status`, `session_id`, `query`, `total`, `partial`, `provider_coverage`, `provider_failures`, `results[]` (`anime_id`, `title`, `media_type`, `year`, `poster_url`, `description`, `genres`, `total_seasons`, `total_episodes`, `verified_episodes`, `unverified_episodes`, `synthetic_episodes`, `counts_status`, `provider_ids`, `provider_variants`).
- **`GET /api/v1/anime/{anime_id}`**: Returns `status`, `anime_id`, `title`, `alternative_titles`, `media_type`, `year`, `description`, `poster_url`, `genres`, `total_seasons`, `total_episodes`, `verified_episodes`, `unverified_episodes`, `synthetic_episodes`, `season_structure_status`, `provider_coverage`, `provider_variants`.
- **`GET /api/v1/anime/{anime_id}/seasons`**: Returns `status`, `anime_id`, `total_seasons`, `total_episodes`, `verified_episodes`, `unverified_episodes`, `synthetic_episodes`, `season_structure_status`, `seasons[]` (`season_id`, `season_number`, `episode_count`, `verified_episodes`, `unverified_episodes`, `synthetic_episodes`, `advertised_episode_count`, `structure_status`).
- **`GET /api/v1/seasons/{season_id}/episodes`**: Returns `status`, `season_id`, `total_episodes`, `verified_episodes`, `unverified_episodes`, `synthetic_episodes`, `structure_status`, `episodes[]` (`episode_id`, `episode_number`, `title`, `metadata_status`, `provenance`, `verification`). Only `verification == "verified"` episodes are user-visible.
- **`GET /api/v1/episodes/{episode_id}/sources`**: Returns `status`, `episode_id`, `total_sources`, `verified_sources`, `excluded_sources`, `merged_duplicates_count`, `failed_validation_count`, `available_languages`, `declared_languages`, `manifest_languages`, `observed_track_languages`, `language_verification_status`, `provider_coverage`, `provider_details[]` (including `fallback_attempted`, `fallback_status`), `providers[]` (`provider`, `sources[]` with `source_id`, `provider`, `stream_url`, `quality`, `language`, `language_code`, `declared_language`, `codec`, `audio_codec`, `channels`, `verification_status`, `playability_status`, `audio_tracks[]`, `media_metadata`, `headers`, `available`).
- **`POST /api/v1/episodes/{episode_id}/resolve`**: Body `{"source_id": "src-1"}` (optional `track_index` / `language`). Returns `status`, `episode_id`, `source_id`, `provider`, `stream_url`, `quality`, `codec`, `audio_codec`, `channels`, `language`, `language_code`, `verification_status`, `playability_status`, `audio_tracks[]`, `headers` (`Map<String, String>`).
