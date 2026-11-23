# Architecture — ProphecyVerse (`feature/libvlc-player-repair`)

- **Presentation:** Jetpack Compose screens (`HomeScreen`, `SearchScreen`, `DetailScreen`, `PlayerScreen`, `LibraryScreen`, `HistoryScreen`, `DownloadsScreen`, `ProfileScreen`, `SettingsScreen`) + `ProphecyVerseTheme` (`Color.kt`, `Theme.kt`, `Type.kt`, `CommonUi.kt`).
- **ViewModels:** `HomeViewModel`, `SearchViewModel`, `DetailViewModel`, `PlayerViewModel`, `LibraryViewModel`, `DownloadsViewModel`, `ProfileViewModel` using `StateFlow` + `viewModelScope`.
- **Domain & Mapping:** `CanonicalModels.kt` (`CanonicalVerification.Verified | Unverified`), `V3Mappers.kt`, `ApiResult.kt`.
- **Data:** `AnimeRepository`, `ProphecyVerseApiService`, `V3BackendGateway`, `PvSessionManager`, `AniListGraphQLService`, `CloudflareD1DataSource`, `AppDatabase` (Room v15).
- **Playback:** `PlayerManager` + `PlaybackHealthMonitor` + `PlaybackRecoveryCoordinator` (Media3 `1.5.1` + `FfmpegAudioRenderer` + `LibVlcEngineAdapter` `3.6.5`).
