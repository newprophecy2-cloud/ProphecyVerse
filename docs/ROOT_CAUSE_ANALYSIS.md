# Root Cause Analysis

1. **Home Playback Failing While Search Works**
   - **File:** `app/src/main/java/com/example/data/remote/V3BackendGateway.kt`
   - **Root Cause:** `PvSessionManager.currentSessionId()` remained `null` when navigating from Home directly to `getDetailsCompat` using cached `CatalogMapping`.
   - **Fix:** Added `V3BackendGateway.ensureSession` and `withSessionRetry` (bounded 1-retry on `401`).
   - **Verification:** `LibVlcAndMovieRepairTest.homeEntryAutoMintsSessionWhenNullAndRetriesOnceOn401`.

2. **Movies Not Playing / Source Unavailable**
   - **File:** `app/src/main/java/com/example/data/repository/AnimeRepository.kt`
   - **Root Cause:** `refreshAnimeDetails` discarded `rawDtoEpisodes` when `isMovie == true` without recording `discoveredBackendEpisodeIds[scopeKey]`.
   - **Fix:** Bind `discoveredBackendEpisodeIds[scopeKey] = movieEp.id` while keeping `episodeDao` clean for movies.
   - **Verification:** `LibVlcAndMovieRepairTest.movieRefreshPersistsCanonicalEpisodeAndDiscoversSources`.

3. **APK Size Bloat (228M)**
   - **File:** `app/build.gradle.kts`, `app/proguard-rules.pro`
   - **Root Cause:** Packaging uncompressed `libvlc.so` across 4 ABIs without R8 shrinking.
   - **Fix:** `abiFilters += listOf("arm64-v8a")`, `useLegacyPackaging = true`, `isMinifyEnabled = true`, `isShrinkResources = true`.
   - **Verification:** `assembleRelease` -> `29,698,212` bytes (`29M` signed).
