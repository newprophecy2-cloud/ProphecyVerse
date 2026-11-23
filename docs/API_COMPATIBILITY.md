# API Compatibility — Prophecy Verse V3

Backend Base URL: `https://prophecy-verse-v3-backend.onrender.com/` (`BackendConfig.BASE_URL`)

| Function | Method & Endpoint | APK Integration | Session Header |
|---|---|---|---|
| Anime Search | `GET /api/v1/anime/search?q={query}` | `ProphecyVerseApiService.searchAnime` -> `V3BackendGateway.searchCompat` | Captures `session_id` into `PvSessionManager` |
| Anime Details | `GET /api/v1/anime/{anime_id}` | `ProphecyVerseApiService.getAnimeDetails` -> `V3BackendGateway.getDetailsCompat` | `X-PV-Session` (auto-mint + 1x `401` retry) |
| Anime Seasons | `GET /api/v1/anime/{anime_id}/seasons` | `ProphecyVerseApiService.getAnimeSeasons` | `X-PV-Session` |
| Season Episodes | `GET /api/v1/seasons/{season_id}/episodes` | `ProphecyVerseApiService.getSeasonEpisodes` (filters `verification == "verified"`) | `X-PV-Session` |
| Episode Sources | `GET /api/v1/episodes/{episode_id}/sources` | `ProphecyVerseApiService.getEpisodeSources` -> `V3BackendGateway.getEpisodeSourcesCompat` | `X-PV-Session` |
| Stream Resolve | `POST /api/v1/episodes/{episode_id}/resolve` | `ProphecyVerseApiService.resolveEpisode` -> `V3BackendGateway.resolveEpisodeCompat` | `X-PV-Session` |

- **Backend Integrity:** Zero backend modifications were made; `MovieBox-Tui-Prophecy-` (`prophecy-backend-v3`) was inspected read-only.
- **Movies Limitation:** When a V3 movie record has `total_seasons: 0, seasons: []` (e.g., `NP-A-155948`), the APK does not fabricate episodes or stream URLs and surfaces an unavailable state.
