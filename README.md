# ProphecyVerse — Android Anime Discovery & Direct Playback APK

ProphecyVerse is an Android anime-discovery and video playback application built with Kotlin, Jetpack Compose, Room Database, AniList GraphQL, the ProphecyVerse API, and AndroidX Media3 ExoPlayer.

---

## 1. Architecture & Database-First Guarantee

The app strictly follows **database-first rendering**:
1. Every screen (Home discovery, search results, anime details, episode lists, watchlist, and history) observes local **Room Database `Flow`s**.
2. Network responses (from AniList and ProphecyVerse) are validated, normalized, and written transactionally into Room before they appear in the UI.
3. No raw remote API responses are ever bound directly to Compose composables.
4. Offline & Stale Support: Cached content remains instantly readable if network connectivity drops.

### Database Tables / Entities:
- `AnimeEntry`: Core metadata (titles, synopsis, year, format, genres, score, studios, poster/banner references, discovery flags, playability status).
- `AnimeAlias`: Normalized and original title variants and synonyms linked to anime entries for rapid search.
- `CatalogMapping`: Explicit match confidence records between AniList IDs and ProphecyVerse backend IDs.
- `SeasonEntry` & `EpisodeEntry`: Season and episode catalog records with backend identifiers.
- `SearchQuery` & `SearchResultRef`: Query history and ranking order references for database-first search results.
- `WatchlistEntry`: User watchlist categories (Watching, Planning, Completed) and favorites.
- `PlaybackHistory`: Chronological playback timestamps, resume points (ms), and duration.
- `UserPreference`: User playback settings and configuration key-values.

---

## 2. API Routes & Single Direct Playback Route

### Discovery & Permitted Metadata (AniList GraphQL)
- **Endpoint:** `POST https://graphql.anilist.co`
- Queries: Seasonal, Trending, Popular discovery feeds, anime search, and detail enrichment.

### ProphecyVerse Playable Catalog
- **Base URL:** `https://api.prophecyverse.tech`
- **Search Playable Anime (Route A):** `GET /api/v1/anime/search?q={query}&limit={limit}&cursor={cursor}`
- **Title & Season Metadata (Route B):** `GET /api/v1/anime/{anime_id}`
- **Episodes Listing (Route C):** `GET /api/v1/anime/{anime_id}/episodes` (called when not bundled in title details)
- **Episode Sources (Route D):** `GET /api/v1/anime/{anime_id}/episodes/{episode_id}/full`
  - Loaded in-memory only upon episode selection; never stored in Room.
- **The One and Only Playback Route (Route E):**
  - `POST /api/v1/play/direct`
  - **Body:** `{"source_id": "<selected_source_id>"}`
  - Resolves verified direct provider `playback_url` with response-authorized request headers (e.g., `Referer`, `User-Agent`).
  - **Strictly No Other Routes:** No `/watch`, `/playback`, `/stream-proxy`, `/remux`, or `/transcode` routes are invoked anywhere in the codebase.

---

## 3. Advanced Media3 ExoPlayer Specification

- **Engine:** AndroidX Media3 ExoPlayer (`androidx.media3:media3-exoplayer:1.4.1`).
- **Header Injection:** Safe HTTP headers (`Referer`, `User-Agent`) injected via `DefaultHttpDataSource.Factory`.
- **In-Memory Security:** Signed URLs, authorization tokens, and short-lived source IDs are kept strictly in memory for the active playback session and never written to Room, logs, or backups.
- **Error & Expiry Handling:** If a stream link expires or connection drops, the player performs a single bounded retry by refreshing episode sources (Route D) and re-resolving direct play (Route E) once.
- **Player Controls:**
  - Play / Pause / Seek bar with formatted timestamps.
  - Skip forward / backward (+10s / -10s).
  - Playback speed selection (0.75x, 1.0x, 1.25x, 1.5x, 2.0x).
  - Track & Quality selection sheet (audio tracks, subtitle tracks, adaptive video resolutions).
  - Screen orientation fullscreen toggle.
  - Controls lock mode and auto-hiding overlay after 4 seconds.
  - Persistent resume: Playback position is periodically saved to local Room history and restored on resume.

---

## 4. Permissions & Compliance

- `android.permission.INTERNET`: Required for network metadata and streaming media.
- `android.permission.ACCESS_NETWORK_STATE`: For checking network availability.
- `android.permission.WAKE_LOCK`: Keeps the screen awake during active video playback.
- **Play Policy Compliance:**
  - Zero broad storage permissions (`READ_EXTERNAL_STORAGE` is not requested).
  - Original adaptive launcher icon generated with Material Design specifications.
  - Dark anime cosmos theme with original styling and typography (no copied Crunchyroll assets).

---

## 5. EAC3 Audio Decoding via FFmpeg Software Decoder

- **FFmpeg Software Audio Decoder:** Integrates `androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer` (`org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1`) with native `libffmpegJNI.so` compiled for all supported Android ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).
- **Targeted EAC3 Routing:**
  - If the selected audio track is EAC3 (`"audio/eac3"` or `"audio/eac3-joc"`), it routes directly to the FFmpeg software decoder.
  - For all other audio formats (AAC, MP3, Opus, FLAC, Vorbis, etc.), the existing ExoPlayer `MediaCodecAudioRenderer` playback is left completely unchanged.
  - If EAC3 is the only available audio track on a stream, it automatically plays through the FFmpeg software decoder.
  - For multi-track streams (e.g. EAC3 + AAC), users can seamlessly select between tracks, with FFmpeg decoding the EAC3 track and MediaCodec decoding the AAC track.
- **Direct Provider URL:** Uses the original provider URL directly without remuxing, server-side transcoding, or audio conversion.
- **Verification & Testing:** Verified via unit and Robolectric tests (`Eac3PlaybackAudioTest`) covering:
  - EAC3-only audio stream playback
  - Multi-track EAC3 + AAC track switching
  - Non-EAC3 (AAC) playback unchanged
  - ABI library verification for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.



## 6. Update, Firebase, and security behavior

The client checks published metadata from `app_config/version_control` using numeric Android version codes. A disabled update channel intentionally suppresses update prompts; missing, unreachable, or malformed metadata produces an error/retry state. Older builds need actionable HTTPS download sources and a matching package ID/version code. Custom APK SHA-256 and release-certificate SHA-256 fields are not required. Android's package installer retains native package-signature continuity checks when installing an update; downloading a file is not treated as successful installation.

The manifest disables cleartext traffic, and the FileProvider exposes only the temporary update-cache directory. The client does not publish version configuration or write Admin moderation fields. The supplied Admin and User Firestore rule files use different role stores and do not match in every collection. No rules were deployed during this task; review and align the single live ruleset before relying on Admin access to installation records or user profiles.

## 7. Build and verification

Use a complete JDK 21 installation with `javac` and Android SDK Platform 36, then run:

```bash
bash ./gradlew clean
bash ./gradlew :app:assembleDebug
bash ./gradlew :app:testDebugUnitTest
```

Historical validation recorded for an earlier checkout: a debug APK build and 34 JVM/Robolectric unit tests passed with JDK 21 and Android API 36. This is not validation of the current checkout. The earlier APK used a sandbox-only Android debug key; it is not a production update APK. The original release keystore was not included in that validation environment. Live Firebase operations, emulator/device installation, and production update behavior were not tested. See `FINAL_AUDIT_REPORT.md` for that historical report and remaining requirements.
