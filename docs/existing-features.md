# Existing Feature Inventory — Prophecy Verse APK

| Feature | Screen / Component | Implementation & Data Source | Status / Notes |
|---|---|---|---|
| **Home / Discovery** | `HomeScreen` + `HomeViewModel` | AniList GraphQL (`Trending`, `Seasonal`, `Popular`, `K-Dramas`, `Movies`) + Room `AnimeEntry` + Continue Watching (`PlaybackHistory`) | Working; preserve AniList-only discovery separation |
| **Search** | `SearchScreen` + `SearchViewModel` | Debounced search + Genre/Format filter chips + pagination (`AnimeRepository.searchAnimePage`) | Uses legacy search DTOs; needs V3 `/api/v1/anime/search` + `X-PV-Session` |
| **Anime Details & Seasons** | `DetailScreen` + `DetailViewModel` | Canonical ID resolution, poster/backdrop, synopsis, genres, season selector chips, episode list, favorites toggle | Uses legacy `/episodes`; needs V3 `/api/v1/anime/{id}`, `/seasons`, `/seasons/{id}/episodes`, and strict `Verified`-only filtering |
| **Episode Sources** | `DetailScreen` (source sheet) + `PlayerViewModel` | Source list by episode with quality/codec/language badges | Uses legacy `/full`; needs V3 `/api/v1/episodes/{id}/sources` preserving `playability_status`, `verification_status`, `audio_tracks` |
| **Media3 Playback** | `PlayerScreen` + `PlayerManager` + `PlayerViewModel` | Media3 ExoPlayer `1.5.1` + FFmpeg audio decoder (EAC3/AC3/DTS), DASH/HLS/MP4, PiP (`onUserLeaveHint`), audio/subtitle track selection, recovery coordinator | Uses legacy `POST /api/v1/play/direct`; needs V3 `POST /api/v1/episodes/{id}/resolve` + header forwarding |
| **Watch History** | `HistoryScreen` + `LibraryViewModel` | Room `PlaybackHistory` + `CloudHistoryRepository` (Firestore sync) | Working; preserve Room v15 schema & cloud sync |
| **Library / Watchlist** | `LibraryScreen` + `LibraryViewModel` | Room `WatchlistEntry` + `CloudHistoryRepository` | Working; preserve watchlist & status tracking |
| **Downloads** | `DownloadsScreen` + `DownloadsViewModel` + `DownloadManager` | Room `DownloadEntry` + offline playback | Working; preserve |
| **Profile & Account** | `ProfileScreen` + `ProfileViewModel` + `AccountDialog` | Local profile (`LocalProfile`) + Firebase Auth (`AuthManager`) | Working; preserve |
| **Settings & Audio Priority** | `SettingsScreen` + `AudioPreferences` + `AudioPriorityDialog` | Preferred audio language priority (`hi`, `en`, `ja`, etc.), cache clear, autoplay/skip/subtitles | Working; preserve all preferences |
| **Notices & Force Update** | `BilingualNoticeDialog` + `ForceUpdateScreen` + `VersionControlManager` | Firebase Remote Config / Firestore notices & FCM `ProphecyMessagingService` | Working; preserve |
