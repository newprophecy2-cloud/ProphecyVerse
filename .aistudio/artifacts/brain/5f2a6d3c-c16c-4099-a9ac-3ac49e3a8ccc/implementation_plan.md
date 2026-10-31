# PROPHECY VERSE APK — MASTER UPDATE IMPLEMENTATION PLAN

## Repository & Target Branch Verification
- **Repository**: `https://github.com/newprophecy2-cloud/ProphecyVerse`
- **Active Branch**: `GAS-code` (verified: `origin/GAS-code`, commit `34155da`)
- **Working Tree**: Clean, all existing assets, JNI libraries, Firebase configurations, and Room schemas preserved.
- **Rule Adherence**: Zero fake/demo data, no rebuild from scratch, no changes to production backend, no deletion of existing user data.

---

## 1. TECHNICAL AUDIT REPORT (PHASE B)

### Objective 1: Home — Automatic Content Updates & Caching
- **Current State**:
  - `HomeViewModel` calls `refreshHome()` only once upon initialization.
  - Returning to Home from Player, Details, or another bottom navigation tab does not trigger a fresh content check.
  - No network connectivity observer exists; returning to online status after airplane mode or disconnect does not automatically refresh content.
  - Discovery refreshes sequentially (AniList discovery GraphQL -> item matching -> K-Drama search -> Movie search -> Web Series search). If one request fails or lags, the entire refresh is aborted or delayed.
  - Lack of rate-limiting/debouncing allows redundant API requests if triggered consecutively.
  - Scrolling position in `HomeScreen` is not decoupled from refresh state, which can cause list jumps.
- **Root Causes**:
  - Absence of `ConnectivityManager.NetworkCallback` or `LifecycleObserver` in Home navigation.
  - Monolithic sequential network execution in `AnimeRepository.refreshHomeDiscovery()`.
  - Missing cached timestamp checks (`CACHE_TTL`).

### Objective 2: Search — Fast and Responsive Results
- **Current State**:
  - Search runs sequential queries (ProphecyVerse API -> AniList GraphQL), waiting for both to finish before inserting `SearchResultRef` into Room.
  - Slower AniList responses delay results even when ProphecyVerse returns instant catalog matches.
  - No progressive rendering; the user waits for the entire batch.
  - In-flight HTTP calls are not cancelled when a user types a new query (only the coroutine Job is cancelled, but OkHttp calls continue).
- **Root Causes**:
  - Synchronous aggregation before Room write in `AnimeRepository.searchAnime`.
  - Lack of dual-tier progressive emission (emit verified catalog items immediately, then enhance with metadata).
  - Absence of query result in-memory cache for repeated recent searches.

### Objective 3: Player — Download System Below Video Player
- **Current State**:
  - `PlayerScreen.kt` has no download section or interface below the 16:9 player in portrait mode.
  - `DownloadManager.kt` automatically selects the first downloadable source candidate without providing quality (1080p, 720p, 480p, 360p) or audio track language selection.
  - `DownloadEntry` entity does not record chosen resolution, audio language, or subtitle track metadata.
  - Offline playback works when a download is completed, but does not present download states (active, paused, failed, completed) directly below the player.
- **Root Causes**:
  - Missing Compose UI component for download operations directly inside `PlayerScreen.kt`.
  - Lack of track extraction and selection parameter forwarding in `DownloadManager`.

### Objective 4: History — Correct Series vs. Movie History & Resume
- **Current State**:
  - `PlaybackHistory` entity uses `id = animeId` (which collapses series into one row), but clicking a history card navigates straight to `PlayerScreen` instead of opening the Series Details screen with the latest watched episode selected and ready to resume.
  - Progress saving in `PlayerViewModel` occurs primarily on a 10-second ticker while playing. Pausing, navigating away, app backgrounding, or back-press before 10 seconds misses the latest progress.
  - Movies and Anime/K-Drama series require distinct identification: movies are individual entries, whereas series must update to the latest watched episode.
- **Root Causes**:
  - History click handler in `MainActivity` routes directly to `Player` instead of `Detail` with episode focus and auto-resume.
  - Lifecycle hook missing in `PlayerScreen` (`onPause`/`DisposableEffect`) to flush final playback position before tearing down.
  - Progress saving condition does not trigger on player state change to `PAUSED` or `ENDED`.

### Objective 5: Performance — Lag, Slowness & Resource Leaks
- **Current State**:
  - `PreResolveQueueManager` queues up to 17 consecutive episodes, creating significant network and memory contention with the active video player.
  - Multiple unbounded in-memory caches in `AnimeRepository` (`memorySourcesCache`) grow indefinitely over time.
  - Jetpack Compose lists in `HomeScreen` and `PlayerScreen` re-filter collections during every composition without stable keys or memoization.
  - Missing HTTP response-level cache headers and connection pooling optimizations.
- **Root Causes**:
  - Excessive pre-resolution window size (17 episodes is too aggressive for mobile networks).
  - Recomposition churn caused by raw lambda references and lack of derived state in player HUDs.

---

## 2. FILE-BY-FILE IMPLEMENTATION PLAN (PHASE C)

### File 1: `app/src/main/java/com/example/data/repository/AnimeRepository.kt`
- **Existing Problem**:
  - `refreshHomeDiscovery()` is monolithic, sequential, and lacks time-based caching and concurrency boundaries.
  - `searchAnime()` waits for all providers before writing to Room; does not emit results progressively.
  - History progress is not flushed immediately on pause/stop.
- **Proposed Modification**:
  1. Add concurrency-bounded, parallel dispatch for Home discovery sections (Anime, K-Drama, Movies, Series) using `coroutineScope` and `async` with a 5-minute memory cache throttle.
  2. Implement progressive search: write and emit confirmed ProphecyVerse playable catalog items to Room immediately (Tier 1), then merge AniList metadata enhancements as they arrive (Tier 2).
  3. Ensure `updatePlaybackProgress()` handles both series (keyed by `animeId`) and movies accurately, preserving poster, title, season, episode, position, and duration without deleting existing records.
- **Expected Result**: Home discovery refreshes 3-4x faster without UI blocking; search results appear immediately as providers respond; history stays accurate.
- **Possible Risks**: Room transaction conflicts if concurrent writes occur; mitigate with proper mutex and `withTransaction`.

### File 2: `app/src/main/java/com/example/ui/viewmodels/ViewModels.kt`
- **Existing Problem**:
  - `HomeViewModel` has no awareness of network connectivity or screen resume lifecycle; lacks debounce against duplicate refreshes.
  - `SearchViewModel` does not cancel outdated in-flight queries and lacks query caching.
  - `PlayerViewModel` does not flush playback position on `onPause`/`onStop` and does not expose download control states to the Player UI.
- **Proposed Modification**:
  1. In `HomeViewModel`: Add `lastRefreshTimestamp`, `checkAndRefresh(force: Boolean)`, and network observer callback hook. Keep existing cached data visible while background refresh executes silently.
  2. In `SearchViewModel`: Add query caching (`LruCache`), reduce debounce to 250ms, cancel previous active search jobs immediately on new query.
  3. In `PlayerViewModel`:
     - Flush progress immediately on pause, stop, and ViewModel `onCleared()`.
     - Expose available download qualities, audio languages, download progress, and actions (start, pause, resume, cancel, delete) for the current episode.
- **Expected Result**: Instant responsive UI, zero duplicate calls, reliable progress persistence, full download control below the player.
- **Possible Risks**: Stale cached query results; mitigate with short TTL (10 minutes) for search queries.

### File 3: `app/src/main/java/com/example/ui/screens/HomeScreen.kt`
- **Existing Problem**:
  - No lifecycle resume hook to refresh content when the user navigates back to Home.
  - Recomputes filtered lists on every recomposition.
  - Missing persistent `LazyListState` preservation during silent background updates.
- **Proposed Modification**:
  1. Use `rememberSaveable(saver = LazyListState.Saver) { LazyListState() }` to retain scroll position across refreshes.
  2. Add `LifecycleEventEffect(Lifecycle.Event.ON_RESUME)` to trigger a non-disruptive background refresh if data is stale (> 15 minutes).
  3. Optimize list filtering with memoized `derivedStateOf`.
- **Expected Result**: Home stays up-to-date automatically without jumping scroll position or reloading UI unnecessarily.
- **Possible Risks**: Minor CPU overhead on resume; mitigated by checking `lastRefreshTime`.

### File 4: `app/src/main/java/com/example/ui/screens/SearchScreen.kt`
- **Existing Problem**:
  - Shows empty screen or spinner until entire search finishes.
  - Filters trigger full re-searches rather than fast memory/Room filtering.
- **Proposed Modification**:
  1. Support progressive rendering: display first results as soon as provider items are written to Room.
  2. Add progressive loading indicator and clear distinction between playable verified items and catalog items.
  3. Keep search input fluid with keyboard actions and query clear.
- **Expected Result**: Instant search feedback with sub-second initial result display.
- **Possible Risks**: Minor list flickering when second-tier results are appended; mitigated by stable Room primary keys.

### File 5: `app/src/main/java/com/example/ui/screens/PlayerScreen.kt`
- **Existing Problem**:
  - No download interface below the 16:9 video player in portrait mode.
  - Does not flush playback position on back click or system pause.
- **Proposed Modification**:
  1. Directly below the video player (above seasons/episodes), insert a dedicated **Player Download Panel**:
     - Action button: Download / Downloading (% and speed) / Paused / Completed.
     - Available Quality Selector Chip/Dropdown: populated strictly from verified available sources (`1080p`, `720p`, `480p`, `360p`). Never fabricated.
     - Available Audio Language Selector: populated strictly from detected audio streams/sources (`Japanese`, `English`, `Hindi`, `Tamil`, etc.).
     - Subtitle selector when supported.
     - Controls: Pause, Resume, Cancel, Retry, Delete.
  2. Implement `DisposableEffect` to flush playback position to database immediately upon leaving the screen or pausing.
- **Expected Result**: Complete, intuitive download management directly below the player; zero lost watch progress.
- **Possible Risks**: Layout crowding on small screens; mitigated by compact collapsible card design following Material 3 guidelines.

### File 6: `app/src/main/java/com/example/data/download/DownloadManager.kt`
- **Existing Problem**:
  - Does not accept user-specified quality, audio track, or subtitles.
  - Does not store track metadata in `DownloadEntry`.
- **Proposed Modification**:
  1. Extend `startOrResumeDownload` to accept `preferredQuality: String?`, `preferredLanguage: String?`, and `preferredSourceId: String?`.
  2. Resolve the matching `ProphecySourceDto` based on user selection; validate URL headers and Range support.
  3. Store quality and language metadata in `DownloadEntry`.
  4. Provide helper queries for download status, retry, pause, and deletion.
- **Expected Result**: Accurate multi-quality and multi-audio download execution with robust resumability and offline playback.
- **Possible Risks**: HTTP Range 416 on certain CDN edge servers; already handled by existing fallback restart.

### File 7: `app/src/main/java/com/example/ui/screens/HistoryScreen.kt` & `MainActivity.kt`
- **Existing Problem**:
  - Clicking a history card navigates directly to `PlayerScreen` instead of opening the Series Details screen with the latest watched episode selected and ready to resume.
  - Does not format season/episode/time progress according to the new specification.
- **Proposed Modification**:
  1. Update `onPlayHistoryClick` in `HistoryScreen.kt` and `MainActivity.kt`:
     - For Series (Anime/K-Drama/Web Series): Navigate to `DetailScreen(animeId, resumeEpisodeId = item.episodeId, resumePositionMs = item.positionMs)`.
     - For Movies: Open directly in player or detail with resume position.
  2. In `DetailScreen`: When navigating with resume parameters, highlight the latest watched episode with a prominent "Resume Episode X (mm:ss)" hero card.
  3. Ensure history card displays full series name, poster, season number, episode number, episode title, progress bar, current position, total duration, and formatted last-played timestamp.
- **Expected Result**: Perfectly complies with requirement 5: One entry per series, clicking opens details with latest watched episode selected and resume position ready.
- **Possible Risks**: Navigation argument formatting; safely pass via query params or Shared ViewModel.

### File 8: `app/src/main/java/com/example/player/PreResolveQueueManager.kt`
- **Existing Problem**:
  - Pre-resolves up to 17 episodes in the background, consuming excessive network bandwidth, memory, and CPU.
- **Proposed Modification**:
  1. Reduce `WINDOW_SIZE` from 17 to a conservative, adaptive 2-3 episodes.
  2. Pause background pre-resolution automatically whenever the active player is buffering or when network quality is constrained.
  3. Clean up expired cached items aggressively to free memory.
- **Expected Result**: Eliminates network contention, reduces player buffering lag, saves memory and CPU.
- **Possible Risks**: None; 2-3 episodes is optimal for seamless next-episode playback.

### File 9: `app/src/main/java/com/example/data/local/AppDatabase.kt` & `Entities.kt`
- **Existing Problem**:
  - `DownloadEntry` needs optional fields for `quality` and `audioLanguage` to display downloaded track info.
- **Proposed Modification**:
  1. Add `quality: String? = null` and `audioLanguage: String? = null` with `@ColumnInfo(defaultValue = "NULL")` to `DownloadEntry`.
  2. Add safe Room `MIGRATION_3_4` executing `ALTER TABLE download_entries ADD COLUMN quality TEXT DEFAULT NULL` and `ALTER TABLE download_entries ADD COLUMN audioLanguage TEXT DEFAULT NULL`.
  3. Preserve all existing user history, watchlist, and downloads without wiping the database.
- **Expected Result**: 100% backward-compatible database update with zero data loss.
- **Possible Risks**: Database version mismatch; mitigated by strictly incrementing to version 4 with tested migration.

---

## 3. STRICT SCOPE CHECKLIST
- [x] Area 1: Home automatic content updates & background caching.
- [x] Area 2: Faster progressive search with bounded concurrency.
- [x] Area 3: Download system below video player with quality & language selection.
- [x] Area 4: Correct Series vs. Movie History with Detail resume.
- [x] Area 5: Performance, CPU/memory optimization, and crash-risk reduction.
- [x] No modifications to `main` or `Apk-V2` branches.
- [x] Branch is exclusively `GAS-code`.
- [x] No demo/fake data.
- [x] Existing user data preserved.
