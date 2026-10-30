# ProphecyVerse Anime APK — Master Build Prompt

## Project goal

Build a polished, fast, responsive Android anime-discovery and playback APK using Kotlin, Jetpack Compose, Room, AniList GraphQL, the ProphecyVerse API, and AndroidX Media3 ExoPlayer.

Use AniList for anime discovery and permitted metadata only. Use the ProphecyVerse API for the playable catalog and source resolution. For playback, use exactly one backend play route: `POST https://api.prophecyverse.tech/api/v1/play/direct`. Do not call any other playback, watch, stream-proxy, remux, transcode, or native-play route.

The user requires database-first data rendering: every screen, including search results, must observe the local database. Network responses must be validated and written to the database before they can appear in the UI. Do not bind a raw API or AniList response directly to a screen.

Use only original UI assets and content the project has permission to use. Do not copy Crunchyroll's name, logo, icons, illustrations, exact layout, text, or other protected assets. Do not scrape, bundle, stream, or download copyrighted video, audio, artwork, subtitles, or trailers without permission. Use original/generated placeholders or explicitly licensed material. If a playback source is not authorized, do not expose a Play action for it.

## 1. Start by inspecting the existing project

Before changing files:

1. Inspect the repository, Android app structure, Gradle setup, existing package/application ID, current API client, and current database.
2. Review the public API contract at `https://api.prophecyverse.tech/openapi.json`. Confirm request and response schemas instead of guessing fields.
3. Preserve existing project conventions and unrelated code. Make the smallest necessary changes, run the project’s available tests, and report anything the API contract cannot confirm.
4. Do not add extra backend routes. Do not deploy or publish the app without explicit authorization.

## 2. Stack and architecture

Use:

- Kotlin and Jetpack Compose for the Android UI.
- AndroidX Media3 ExoPlayer for video playback. Do not use the legacy `com.google.android.exoplayer2` package.
- Room as the local source of truth for all displayable catalog, search, metadata, watchlist, and history data.
- A repository-based architecture with ViewModels and unidirectional UI state.
- A maintained Android-compatible HTTP client for the REST API and a GraphQL client or small typed GraphQL transport for AniList.
- Coroutines and `Flow` for asynchronous work and database observation.

Keep the layers separate:

- **UI:** Compose screens; reads only ViewModel state backed by Room flows.
- **Domain:** search, title matching, source selection, history, and playback use cases.
- **Data:** Room DAOs, API clients, AniList client, repositories, cache policy, and DTO-to-entity mapping.
- **Player:** a lifecycle-safe Media3 player controller. It receives a resolved direct URL in memory; it must not make independent backend playback calls.

## 3. Database-only UI and search rule

All visible data must come from Room. This applies to home cards, details, episode lists, search results, availability labels, watchlist, and history.

Implement this sequence for every remote refresh:

1. Read and render the current matching rows from Room immediately.
2. If online and the cache is stale or the user explicitly refreshes, request fresh data from AniList and/or the ProphecyVerse API.
3. Validate and normalize the responses.
4. Upsert them transactionally into Room, including their mapping and cache timestamps.
5. Let Room `Flow` emit the updated state. The UI must never render the network response directly.
6. If refresh fails, keep cached rows visible and show a non-blocking stale/offline state.

For search, query Room first using a local full-text index over titles and aliases. In parallel, after a debounce, request remote matches as needed, write those results and their order to Room, then display the Room query result. If the database has no result yet, show a loading/empty state while synchronization runs; never bypass Room to show a raw network result.

Use pagination and avoid fetching all catalog data at once. Normalize case, punctuation, whitespace, and diacritics for search. Support AniList English, Romaji, native titles, and synonyms where available. Keep backend IDs and AniList IDs in separate columns. Never assume the two ID systems are interchangeable.

Suggested local tables/entities:

- `AnimeEntry`: AniList ID when matched, backend anime ID when available, title variants, year/season, formats, genres, score, synopsis, image references allowed by policy, and timestamps.
- `AnimeAlias`: normalized and original title aliases linked to an `AnimeEntry`.
- `CatalogMapping`: explicit mapping confidence between an AniList entry and a backend anime ID; retain ambiguous matches separately instead of merging them.
- `SeasonEntry` and `EpisodeEntry`: backend season/episode IDs, display numbers, names, and availability state.
- `SearchQuery` and `SearchResultRef`: normalized query, result IDs, ranking order, cursor, and fetch time.
- `WatchlistEntry`, `PlaybackHistory`, and `UserPreference`.

Do not persist signed `playback_url` values, provider authorization headers, access tokens, or short-lived playback source IDs in Room, analytics, crash reports, or logs. Keep them in memory only for the active playback session. Do not bundle them into backups.

## 4. ProphecyVerse API — allowed route flow

Use HTTPS and this base URL:

`https://api.prophecyverse.tech`

Use only these routes for the search-to-play workflow:

### A. Search playable anime

`GET /api/v1/anime/search?q={query}&limit={limit}&cursor={cursor}`

- Pass the user’s search query.
- Use `limit` and `cursor` according to the OpenAPI contract.
- Persist results in Room before the search screen displays them.
- Treat returned anime IDs as ProphecyVerse backend IDs only.

### B. Load backend title/season details

`GET /api/v1/anime/{anime_id}`

- Use the backend anime ID from the search result.
- Persist the title and season/episode metadata returned by the API.
- If this response already contains the episode IDs needed by the UI, do not make an unnecessary extra request.

### C. Load episodes only when needed

`GET /api/v1/anime/{anime_id}/episodes`

- Call this only if the title response does not contain the needed episode IDs, or if the API contract requires a separate episode listing.
- This is a catalog/data route, not a playback route.

### D. Get sources for the selected episode

`GET /api/v1/anime/{anime_id}/episodes/{episode_id}/full`

- Call only after the user selects an episode or opens its source chooser.
- Read available source labels and source IDs from the actual response schema.
- Show a source as playable only if the API returns it. Do not manufacture source IDs or playback URLs.
- Keep transient source IDs in memory or apply a very short documented cache; do not treat them as permanent identifiers.

### E. The one and only playback route

`POST /api/v1/play/direct`

JSON body:

```json
{
  "source_id": "<source_id returned for the selected episode>"
}
```

- This is the only backend route permitted for Play/resolution.
- Follow the response schema in the OpenAPI document. Use the returned direct `playback_url` as the ExoPlayer media URI. Pass only response-provided safe request headers if the contract requires them.
- Do not use `/api/v1/watch`, `/api/v1/sources/{id}/watch`, `/playback`, `/stream`, `/stream-proxy`, `/remux`, `/transcode`, or any other route as a fallback.
- Do not rewrite, proxy, expose in logs, or persist the returned signed URL.
- If the direct URL expires or playback fails with a resolvable source error, refresh the selected episode’s source list through route D, then retry this same single Play route once. Apply a strict retry limit and prevent loops.
- Verify that the response is a direct URL and the required scheme/headers are supported before handing it to ExoPlayer. Never accept a user-entered arbitrary URL as a playback URL.

## 5. AniList GraphQL — discovery and metadata logic

Use the official AniList GraphQL endpoint:

`https://graphql.anilist.co`

Use AniList for permitted metadata and discovery. Do not use it as a source of video streams or ProphecyVerse IDs.

Create small, paginated GraphQL queries for:

- **Search:** `Media(search: ..., type: ANIME, isAdult: false)` with title variants, synonyms where available, cover/banner metadata, description, format, status, start date, season/year, episode count, duration, genres, tags, average score, and studio information.
- **Discovery:** paged seasonal, trending, and popular anime lists, filtered by genre/year/format/status when the user selects filters.
- **Detail enrichment:** relations, recommendations, character/staff/studio data, and permitted external trailer references where available.
- **Airing schedule:** upcoming episode number/time and related anime metadata where available.

Store the AniList response in Room before showing it. Store only the fields needed by the UI and comply with AniList’s API terms, attribution requirements, caching guidance, and rate limits. Read response rate-limit headers and honor `Retry-After`; debounce queries, paginate, batch useful fields into GraphQL requests, and cache results. If AniList is unavailable, continue using cached Room data and the ProphecyVerse catalog.

Match AniList and ProphecyVerse entries conservatively using normalized title variants, season/year, and format. Keep unmatched AniList results as metadata-only. Keep unmatched ProphecyVerse results playable with the metadata returned by ProphecyVerse. If a match is ambiguous, show distinct results or ask the user to choose; never silently combine different shows.

## 6. Feature scope and screens

### Home / Discover

- Original anime-first home screen with a featured item, trending, seasonal, popular, recently added-to-local-cache, upcoming-airing, and recommendation rails.
- Use AniList for discovery. Do not call many per-card backend searches. Check backend playability only when a user searches, opens a title, or selects an episode unless an approved catalog batch route already exists.
- Clearly distinguish “Catalog info” from “Playable in this app.”

### Search

- Debounced search with local Room results first.
- Search AniList title variants and the ProphecyVerse playable catalog separately, persist both, and merge only high-confidence matches.
- Filters: genre, season, year, format, release status, and score where supported by AniList.
- Search states: recent searches, clear history, pagination, no results, offline cached results, and ambiguous title selection.

### Anime detail

- Poster/banner only where usage is permitted; otherwise original neutral placeholder art.
- Title variants, synopsis, year/season, format, genres/tags, score, studios, and related/recommended titles.
- Display availability as “Playable,” “Info only,” or “Not available,” based on the ProphecyVerse API result.
- If playable, show seasons and episodes from ProphecyVerse backend IDs. Show a Play button only after a playable episode/source is available.

### Library

- Local watchlist, favorites, recently watched, and continue-watching.
- Save playback position and last selected episode locally.
- Optional AniList account sync may be a later opt-in feature using official OAuth. Do not require login for core search or playback. Keep credentials out of logs and ordinary database backups.

### Settings

- App language, theme, preferred subtitle/audio language, subtitle appearance, default playback speed, default quality behavior, autoplay next episode, and data/cache controls.
- Provide a clear “clear cached metadata/search” action. Do not present cache clearing as deleting remote account data.

## 7. Original UI/UX and visual design

Take only broad inspiration from polished anime streaming apps: strong poster-led discovery, editorial content rails, clear detail pages, and a focused player. Create a distinct visual identity; do not reproduce Crunchyroll branding, exact layout, copy, typography, logo, icons, or artwork.

Use a refined dark theme with an original palette, for example deep navy/charcoal with a distinct indigo/cyan accent. Use consistent spacing, readable type, accessible contrast, and restrained motion. Design for phones first, then adapt to tablets and Android TV if the existing project supports it.

Suggested bottom navigation:

- Home
- Search
- Library
- History
- Settings

Include responsive loading skeletons, artwork placeholders, empty states, offline/stale indicators, retry actions, and clear errors. Use Compose state and Room flows so scrolling and search remain smooth. Avoid large blocking spinners and avoid rerendering the full list on each keystroke.

Support accessibility: TalkBack labels, scalable text, adequate touch targets, focus order, keyboard/remote navigation where relevant, and reduced-motion behavior.

## 8. Advanced Media3 ExoPlayer specification

Use AndroidX Media3 ExoPlayer with a dedicated player controller and Compose UI integration. Do not create another backend playback path.

### Core playback controls

- Play/pause, seek bar, current/remaining time, fullscreen, portrait/landscape, screen-fit/crop/zoom, and lock-controls mode.
- Optional double-tap seek, configurable skip interval, swipe brightness/volume, playback speed presets, and sleep timer.
- Resume at the locally saved position; save progress periodically and on lifecycle/background events.
- Next episode and autoplay toggle. Do not auto-play an episode that has no valid backend source.

### Quality and tracks

- Use adaptive quality selection only when the actual stream contains adaptive tracks. Offer Auto and the resolutions that ExoPlayer reports as supported; hide impossible quality choices.
- Show audio and subtitle menus only when tracks are present and supported. Prefer the user’s saved language, but let the user override it.
- Use Media3 track information and selection parameters. Do not promise dual subtitles unless the stream and implementation truly support simultaneous text tracks.
- Optional user-selected local subtitle files may be supported only for lawful files and supported formats. Do not add another backend route to obtain subtitles.

### Reliability and device support

- Handle buffering, network loss, HTTP errors, expired URLs, unsupported container/codec, decoder failure, and audio-focus changes with clear UI states.
- Test a licensed sample set across representative Android devices. Do not assume every MKV, codec, HDR profile, or provider URL is supported on every device.
- Use appropriate HTTP timeouts and safe response headers. Disable cleartext traffic unless a specific authorized requirement exists.
- Support Android Picture-in-Picture where compatible. Add MediaSession/notification and background playback only if implemented correctly with Android lifecycle and audio-focus rules.
- Keep playback buffering in a bounded cache. Do not silently create permanent offline copies.

### Downloads and DRM

Do not implement video downloads or offline copies in the initial APK unless the content owner explicitly grants download rights and the API contract supplies an authorized download flow. Do not bypass DRM, access controls, subscriptions, or geo-restrictions. If authorized offline playback is added later, implement it as a separately approved feature and not as a second Play route.

## 9. Privacy, security, and copyright requirements

- Do not bundle copyrighted episodes, posters, screenshots, trailers, logos, subtitles, or commercial music.
- Use generated/original placeholder art or assets with explicit licenses. Use AniList metadata/images only as allowed by AniList terms and the image owners’ permissions; do not package them as downloadable offline assets by default.
- Play only content streams the operator has rights to provide. If rights are not established, disable playback and keep the app in catalog/discovery mode.
- Do not scrape third-party streaming sites, bypass authentication, or defeat DRM.
- Never log full direct URLs, signed query parameters, headers, OAuth tokens, or private user data.
- Validate API response types and URL schemes. Use HTTPS. Keep AniList OAuth credentials, if added, in secure platform storage and request the minimum scopes.
- Provide a privacy notice describing locally stored search history, watchlist, progress, and cached metadata. Give users controls to clear local data.

## 10. Performance requirements

- Room is the only source observed by the UI.
- Search performs local FTS immediately, debounces remote synchronization, cancels stale searches, and applies pagination.
- Cache normalized AniList metadata with configurable TTL and use stale-while-revalidate behavior. Use a short TTL for backend availability/source data. Never reuse an expired playback URL.
- Use lazy Compose lists, image loading with memory/disk cache for permitted artwork, stable item keys, and bounded concurrent requests.
- Respect AniList rate-limit and retry headers. Use exponential backoff with a cap for transient failures; do not retry permanent 4xx errors indefinitely.
- Keep the main thread free of networking, database, decoding, and large JSON work.

## 11. Required states and error handling

Implement distinct UI states for:

- Empty search, loading from Room, remote refresh in progress, results, no results, and pagination.
- Metadata available but no playable backend match.
- Playable title but selected episode has no sources.
- Direct resolution rejected or unavailable.
- Player buffering, playing, paused, ended, retrying, and fatal failure.
- Offline with cached database data, stale metadata, and no cached data.

Show useful, non-sensitive messages. Do not display stack traces, raw provider URLs, headers, or source tokens.

## 12. Testing and acceptance criteria

Before declaring the build complete, verify:

1. App compiles and launches on a clean Android install.
2. All visible catalog/search/detail data is read from Room; network results become visible only after Room writes.
3. Search uses the exact API query parameters and does not merge unrelated AniList/backend entries.
4. The flow is Search → title/episode selection → `/full` source list → exactly one `POST /api/v1/play/direct` route for playback.
5. No other playback, watch, proxy, remux, transcode, or native route is called anywhere in the app.
6. Signed playback URLs and sensitive headers are never persisted or logged.
7. Retry after URL expiry refreshes the source and uses the same single direct-play route, with a bounded retry count.
8. ExoPlayer track and quality controls appear only for actual supported tracks.
9. Offline browsing, empty states, rate-limit responses, API errors, and unsupported media are handled without crashes.
10. UI uses no copied Crunchyroll/MovieBox branding or unlicensed content assets.
11. Add unit tests for normalization/matching/cache policies and integration tests for repository-to-Room behavior and playback error handling.
12. Report the exact routes used, database entities added, tests run, and any features intentionally left disabled due to rights or missing API support.

## 13. Deliverables

Deliver a working Android APK project with source code, Room database/migrations, Media3 ExoPlayer integration, API and AniList repositories, original UI design, tests, and a concise README covering setup, authorized API configuration, database behavior, route flow, permissions, and known limitations.

Do not claim a feature works unless it is implemented and tested. If a requested feature requires an API route, license, credential, or response field that is unavailable, explain the blocker and leave the feature safely disabled rather than inventing a workaround.
