# Architecture Audit — Prophecy Verse APK

## 1. Tech Stack & Build Configuration
- **Primary Language:** Kotlin (`2.2.10` / Gradle Kotlin `2.2.21`)
- **UI Framework:** Jetpack Compose (`composeBom = 2024.09.00`) with Material 3
- **Build System:** AGP `9.1.1`, `compileSdk = 36`, `targetSdk = 36`, `minSdk = 24`, KSP `2.3.5`
- **Networking:** Retrofit `2.12.0` + OkHttp `4.10.0` + Moshi `1.15.2` (KSP codegen)
- **Local Persistence:** Room `2.7.0` (`AppDatabase` v15, 12 entities, 8 DAOs) + `SharedPreferences` (audio preferences, D1 digest)
- **Media Playback:** AndroidX Media3 `1.5.1` (`media3-exoplayer`, `media3-ui`, `media3-common`, `media3-session`, `media3-exoplayer-hls`, `media3-exoplayer-dash`, `media3-datasource-okhttp`) + Jellyfin `media3-ffmpeg-decoder` `1.5.0+1` (`jniLibs` bundled for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`)
- **DI Pattern:** Manual singleton / lazy factory pattern (`NetworkModule`, `AppDatabase.getInstance`, `AnimeRepository.getInstance`, `AppViewModelFactory`) — Hilt is not configured in Gradle.

## 2. Current Layering & Dependencies
```
MainActivity / Compose Screens (Home, Search, Detail, Player, Library, History, Downloads, Profile, Settings)
    │
    ▼
ViewModels (HomeViewModel, SearchViewModel, DetailViewModel, PlayerViewModel, LibraryViewModel, DownloadsViewModel, ProfileViewModel)
    │
    ▼
AnimeRepository (4,301 LOC) + PlayerManager + DownloadManager + Firebase / D1
    │
    ├──► ProphecyVerseApiService (Legacy: https://api.prophecyverse.tech)
    ├──► AniListGraphQLService (https://graphql.anilist.co — Home/Discovery)
    ├──► CloudflareD1DataSource (https://prophecy-verse-db.newprophecy4.workers.dev/v1/)
    ├──► Firebase (Auth, Firestore, Analytics, Messaging, Remote Config, AppCheck)
    └──► AppDatabase (Room v15)
```

## 3. Architectural Inconsistencies Found
1. **Legacy Backend URL & Routes:** `NetworkModule.PROPHECY_BASE_URL` points to `https://api.prophecyverse.tech`, and `ProphecyVerseApiService` uses legacy endpoints (`/api/v1/anime/{id}/episodes`, `/api/v1/anime/{id}/episodes/{ep}/full`, `POST /api/v1/play/direct`) that do not exist on Prophecy Verse V3 (`https://prophecy-verse-v3-backend.onrender.com`).
2. **Missing V3 Session Management:** `OkHttpClient` does not capture or attach `X-PV-Session` from V3 `/api/v1/anime/search` responses.
3. **DTO / Domain Coupling:** `DetailScreen` and `AnimeRepository` consume `ProphecySourceDto` directly, and `EpisodeEntry` lacks explicit V3 `verification` (`verified`/`unverified`) & `provenance` fields.
4. **UI/Theme Fragmentation:** `Theme.kt` exposes a single dark scheme (`SpaceDark`), `Type.kt` only overrides `bodyLarge`, and screens duplicate card/badge/error layouts inline.
5. **Direct Navigation / Context Coupling:** `MainActivity.kt` calls `AnimeRepository.getInstance(context)` directly inside Composable scope (`navigateToAnime`) and `DetailScreen` instantiates `DownloadManager` via `LocalContext`.
