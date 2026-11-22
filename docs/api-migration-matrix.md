# API Migration Matrix — Legacy to Prophecy Verse V3

Target Base URL: `https://prophecy-verse-v3-backend.onrender.com/`

| Feature | Legacy Route | V3 Route | Session Header | Migration Status |
|---|---|---|---|---|
| **Search** | `GET /api/v1/anime/search` | `GET /api/v1/anime/search?q={query}` | Returns `session_id` | Pending |
| **Anime Details** | `GET /api/v1/anime/{anime_id}` | `GET /api/v1/anime/{anime_id}` | `X-PV-Session` | Pending |
| **Seasons** | *(derived from `/episodes`)* | `GET /api/v1/anime/{anime_id}/seasons` | `X-PV-Session` | Pending |
| **Episodes** | `GET /api/v1/anime/{anime_id}/episodes` | `GET /api/v1/seasons/{season_id}/episodes` | `X-PV-Session` | Pending |
| **Episode Sources** | `GET /api/v1/anime/{anime_id}/episodes/{episode_id}/full` | `GET /api/v1/episodes/{episode_id}/sources` | `X-PV-Session` | Pending |
| **Direct Resolve** | `POST /api/v1/play/direct` | `POST /api/v1/episodes/{episode_id}/resolve` | `X-PV-Session` | Pending |
| **Health / Ready** | *(none)* | `GET /health`, `GET /ready` | None | Pending |
