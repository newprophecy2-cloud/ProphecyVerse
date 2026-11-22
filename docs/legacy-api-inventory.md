# Legacy API Inventory — Prophecy Verse APK

## 1. Discovered Legacy Base URLs
- **Primary Backend Base URL (`NetworkModule.kt:17`):** `https://api.prophecyverse.tech`
- **No `moviebox-tui-prophecy.onrender.com` references** exist in `app/src/` or `app/build.gradle.kts`.

## 2. Legacy Retrofit Endpoints (`ProphecyVerseApiService.kt`)
| Method | Route | Purpose | Defect / V3 Status |
|---|---|---|---|
| `GET` | `/api/v1/anime/search` | Search anime (`q`, `limit`, `cursor`) | Missing `session_id` capture (`X-PV-Session`) and V3 response shape (`anime_id`, `provider_variants`, `counts_status`) |
| `GET` | `/api/v1/anime/{anime_id}` | Anime details | Missing V3 `verified_episodes`, `unverified_episodes`, `season_structure_status`, `provider_coverage` |
| `GET` | `/api/v1/anime/{anime_id}/episodes` | Flat episode list | **Not present in V3**; V3 uses `/api/v1/anime/{anime_id}/seasons` + `/api/v1/seasons/{season_id}/episodes` |
| `GET` | `/api/v1/anime/{anime_id}/episodes/{episode_id}/full` | Combined episode + sources | **Not present in V3**; V3 uses `/api/v1/episodes/{episode_id}/sources` |
| `POST` | `/api/v1/play/direct` | Resolve stream URL (`DirectPlayRequest`) | **Not present in V3**; V3 uses `POST /api/v1/episodes/{episode_id}/resolve` |

## 3. Auxiliary Services (Preserved, Not Replaced)
- **AniList GraphQL:** `https://graphql.anilist.co` (`AniListGraphQLService`) — used exclusively for Home/Discovery.
- **Cloudflare D1:** `https://prophecy-verse-db.newprophecy4.workers.dev/v1/` (`CloudflareD1ApiService`) — shared catalog metadata cache.
- **Firebase:** Auth, Firestore, Messaging, Remote Config, Analytics, AppCheck.
