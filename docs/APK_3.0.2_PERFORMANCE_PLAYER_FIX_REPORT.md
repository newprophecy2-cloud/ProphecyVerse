# APK 3.0.2 Performance, Download, and Player Fix Report

## Repository and scope

- Repository: `https://github.com/newprophecy2-cloud/ProphecyVerse.git`
- Branch: `repair/apk-v3-root-cause`
- Starting reference: `b63c949fb1e022b5883939f1dca4a6fefaaab8e0`
- Package ID: `com.aistudio.prophecyverse.vykqtb`
- Firebase project/configuration: unchanged

This pass was limited to resolving performance, large-catalog pre-resolving, download resolution/network handling, and targeted Player reliability. The prior eight fixes were preserved rather than reimplemented.

## Problem 01 — excessive resolving time

### Confirmed root cause

`AnimeRepository` already protected source metadata and direct-play calls with mutexes, but those mutex maps were instance-local. PlayerViewModel, PreResolveQueueManager, and DownloadManager each construct repository instances, so simultaneous requests from different workflows could still perform duplicate source/direct operations. Existing source and direct caches were also instance-local. The code already logs HTTP/source/direct timings, but there was no process-wide single-flight boundary.

### Fix

Added `EpisodeResolutionCoordinator.kt`, a process-wide in-flight Deferred registry keyed by backend anime, season, episode identity, and preferred source. Added `resolveVerifiedPlaybackForEpisode()` as the shared verified path. It:

- reuses the existing source and direct caches;
- waits for an existing operation for the same episode instead of starting another;
- applies an 18-second overall resolution bound;
- preserves source ID, URL, expiry, and existing verification checks;
- tries only usable, distinct source candidates;
- retains controlled existing retry/backoff behavior;
- logs total resolution timing and cache-hit timing without logging URLs, tokens, or credentials.

Player startup, pre-resolve, and download resolution now use this shared path. Player fallback behavior still uses the existing source-switch/fallback logic.

## Problem 02 — large anime catalogs and pre-resolving

### Confirmed root cause

The PlayerViewModel observed the entire anime catalog and called `slideWindow()` on each Room emission. The queue then rebuilt/cancelled work even when the relevant window had not changed. The prior planner handled only the forward window, and Player next-episode selection could cross season boundaries.

### Fix

- Player pre-resolve scheduling now passes only the current season’s episodes.
- `PreResolveQueueManager` computes a stable window signature and skips unchanged active windows.
- Forward work remains bounded to the next 17 episodes, with the immediate next episode first, fast work before background work, and previous 6 episodes appended at low priority.
- Previous episodes never precede the immediate-next/forward work.
- Duplicate identities are removed with backend + season + episode identity.
- Obsolete work is cancelled on an actual window change; stale results cannot overwrite the current queue state because generation checks remain in place.
- Valid prepared sources remain reusable and expired entries are removed before retry.
- `nextEpisode` now uses the same-season planner instead of scanning across all seasons.

### Measurements

No reliable before/after end-to-end network measurement was available in the terminal environment, so none is claimed. A focused synthetic Kotlin test exercises a 2,400-entry two-season catalog and verifies that only a 17-episode same-season window is produced in under 1 second. The separate pure planner benchmark observed approximately 0.009 ms for 100 entries, 0.034 ms for 500 entries, and 0.071 ms for 1,200 entries in this sandbox; these are local planning measurements, not API or playback timings.

## Problem 03 — download failures

### Confirmed root cause

`DownloadManager` marked a row as pending but then performed its own source discovery/direct resolution through its own `AnimeRepository` instance. It could therefore race Player/pre-resolve work and report a failure while another operation was still resolving. Temporary resolution/network failures also had only a single attempt in this path.

### Fix

- Download now calls the shared verified episode resolver.
- If resolution is already running, download waits for it.
- If no operation exists, download starts the shared operation and waits.
- A verified source transitions the persisted row from `PENDING_RESOLUTION` to `DOWNLOADING`.
- Temporary resolution failures receive one controlled 1.5-second retry; permanent failures are mapped to user-safe copy.
- Existing active-job deduplication, range/resume handling, pause/cancel behavior, byte-based progress, speed/ETA, final file checks, and storage checks remain intact.

## Player issues and targeted improvements

The Player had two confirmed reliability issues in the inspected flow: next-episode derivation used the full catalog and could cross seasons, and the same episode could be started again while a valid player was already active. The lifecycle observer also handled both `ON_PAUSE` and `ON_STOP`, causing duplicate pause calls and duplicate progress flushes during normal Activity transitions.

Changes:

- shared verified resolution is used before the existing fallback initializer;
- duplicate start requests for the same already-active episode are ignored;
- next episode is same-season and uses the highest pre-resolve priority;
- normal pause/progress handling is performed once through `ON_PAUSE`; the duplicate `ON_STOP` handling was removed;
- existing controls, audio/subtitle selection, fullscreen, PiP, orientation, history, and source fallback paths were preserved.

## Files modified

- `app/src/main/java/com/example/data/repository/EpisodeResolutionCoordinator.kt`
- `app/src/main/java/com/example/data/repository/AnimeRepository.kt`
- `app/src/main/java/com/example/data/download/DownloadManager.kt`
- `app/src/main/java/com/example/player/PreResolveQueueManager.kt`
- `app/src/main/java/com/example/ui/viewmodels/ViewModels.kt`
- `app/src/main/java/com/example/ui/screens/PlayerScreen.kt`
- `app/src/test/java/com/example/EpisodeResolutionCoordinatorTest.kt`
- `app/src/test/java/com/example/PreResolvePlanTest.kt`

No backend files, Firebase project files, package identity, Gradle/JDK versions, or codec-related files/configuration were modified.

## Tests and build

- Focused tests: **PASS**, including shared single-flight, bounded same-season planning, Player redirect policy, download/playback regressions, and v3 metadata tests.
- Full Gradle tests: **PASS**, `./gradlew test --no-daemon`.
- Lint: **PASS**, `./gradlew lint --no-daemon`.
- APK build: **PASS**, `./gradlew assembleDebug --no-daemon`.
- Emulator testing: not available in the terminal environment.
- Physical-device testing: not performed.
- Integration tests against live source providers: not performed; no network performance result is claimed.

## APK details

- Version name: `3.0.2`
- Version code: `7`
- Variant: `debug`
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Size: approximately 38 MB
- Package metadata verified with `aapt dump badging`.
- APK v2 signature verification passed.
- Release-signed APK was not built because release signing credentials were unavailable.

## Restrictions verification

- **No codec behavior modified.** Media3/ExoPlayer, decoder selection, MediaCodec behavior, H.264/H.265 handling, audio/video codec support, playback format selection, renderer configuration, FFmpeg dependencies, and codec Gradle configuration were not changed.
- **No backend changes.** No backend repository, route, contract, database, provider, or deployment changes were made.
- **No Firebase changes.** Firebase project configuration and rules were not modified.
- **No package ID changes.** `com.aistudio.prophecyverse.vykqtb` remains unchanged.
- **No unnecessary redesign.** Only the four requested work areas and focused tests/report were changed.
- **Previous eight fixes preserved.** Earlier completed fixes were not reimplemented; only the download/resolution path was minimally updated where required for shared operation behavior.
