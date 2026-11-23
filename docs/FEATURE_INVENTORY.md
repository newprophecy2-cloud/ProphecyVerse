# Feature Inventory — ProphecyVerse (`feature/libvlc-player-repair`)

- **Home / Discovery:** AniList GraphQL (`Trending`, `Seasonal`, `Popular`, `K-Dramas`, `Movies`), Continue Watching (`PlaybackHistory`), Room `AnimeEntry` cache.
- **Search:** V3 Backend `/api/v1/anime/search`, debounced `SearchViewModel` (`180ms` for `>= 2` chars), `searchJob?.cancel()`, `searchRequestSequence` stale guard, `MemoryCacheManager` TTL cache.
- **Details & Seasons & Episodes:** V3 canonical IDs, `CanonicalVerification.Verified`-only user-facing episodes, `verified_episodes` counts, favorites/watchlist toggle, download trigger.
- **Playback:** Dual-capable `PlayerManager` with Media3 ExoPlayer `1.5.1` (`DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON` + `FfmpegAudioRenderer`) AND `LibVlcEngineAdapter` (`org.videolan.android:libvlc-all:3.6.5` + `VLCVideoLayout`), PiP, audio/subtitle tracks, bounded recovery.
- **Library, History, Downloads, Profile, Settings:** Room v15 persistence, Firestore sync, `AudioPreferences`, Firebase notices & version checks.
