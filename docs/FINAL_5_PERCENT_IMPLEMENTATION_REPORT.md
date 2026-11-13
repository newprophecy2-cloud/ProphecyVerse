# Final 5% Implementation Report

## A. Repository

- Repository: `https://github.com/newprophecy2-cloud/ProphecyVerse.git`
- Branch: `repair/apk-v3-root-cause`
- Initial commit: `b63c949fb1e022b5883939f1dca4a6fefaaab8e0`
- Final implementation commit: `5ee4fb951faeb697b6215a1a4714cb94104f50fc`

## B. Eight bug fixes

1. **Foreground FCM image:** `ProphecyMessagingService.kt` now prioritizes `notification.imageUrl`, then `data["image_url"]`; downloads only HTTP(S) images off the main thread, caps response size, decodes safely, uses `BigPictureStyle`, and falls back to text on any failure while preserving title, body, channel, and tap extras.
2. **Intermittent pre-resolving:** `PreResolvePlan` and `PreResolveQueueManager` now use same-anime/same-season stable identity, cancellation-safe generation checks, bounded serialized IO processing, controlled retry, and verified-only cache insertion.
3. **Unnecessary re-resolving:** valid prepared sources remain reusable; expired cache entries are invalidated before retry; duplicate work is prevented by the serialized queue and active download job map.
4. **Technical errors:** `UserFacingErrorMapper` centralizes network, timeout, HTTP, and unexpected error copy. Raw endpoint/JSON/exception text is no longer placed in the covered source-resolution/download UI paths.
5. **Next 17:** planner returns at most 17 episodes in the current season, with explicit fast 7 and background 10 partitions; the worker handles the fast partition first and never launches 17 simultaneous requests.
6. **Previous 6:** planner exposes the preceding six episodes in the current season and does not fabricate records or cross season boundaries.
7. **Download before resolution:** downloads begin in `PENDING_RESOLUTION`; the row transitions to `DOWNLOADING` only after direct source resolution succeeds, avoiding a false immediate failure.
8. **Accurate download progress:** transfer progress continues to use actual bytes read; the manager now publishes final persisted byte counts, measured speed, and ETA only when total length and measured speed are known. Existing range/resume, pause, cancel, interruption, and finalization logic is preserved.

## C. Resolution system

The next window is 17 episodes. Episodes 18–24 relative to episode 17 are fast priority; episodes 25–34 are background priority. Previous episodes 11–16 are a separate lower-priority planning window. Work is bounded to the existing serialized IO worker, request identity includes backend anime, season, and episode identity, verified cache entries are reused, and expired entries are not treated as valid.

## D. Download

A click while resolution is pending is represented as `PENDING_RESOLUTION` and remains under the existing active-job deduplication guard. Successful source verification automatically transitions the same task to transfer. Progress uses received bytes; speed is measured from elapsed transfer time; ETA is null when total length or speed is unknown. Existing pause/resume, range requests, restart recovery through partial files, and final file checks remain in the existing manager.

## E. FCM

Foreground image handling is implemented with bounded asynchronous HTTP retrieval and `NotificationCompat.BigPictureStyle`. No notification channel or deep-link/tap behavior was removed. Background behavior was not replaced; the same service and channel remain registered.

## F. APK

- Version name: `3.0.2`
- Version code: `7`
- Package ID: `com.aistudio.prophecyverse.vykqtb`
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Size: approximately 37 MB
- Signature verification: APK Signature Scheme v2 verified
- Release APK: not built because no release signing credentials were available in the environment; no credentials were added or committed.

## G. Tests and verification

- Unit tests: **PASS**, `./gradlew testDebugUnitTest --no-daemon`, 130 tests completed successfully.
- Build: **PASS**, `./gradlew assembleDebug --no-daemon`.
- APK metadata: verified with `aapt dump badging`; package/version values match above.
- Diff hygiene: `git diff --check` passes.
- Static review: no backend files, Firebase project configuration, package ID, or codec-related files/configuration were changed.
- Emulator/device tests: unavailable; no emulator or physical Android device was present in the terminal environment. Therefore no real-device, FCM delivery, network-transition, or install/runtime result is claimed.

## H. Codec protection

**NO CODEC CHANGE.** No Media3/ExoPlayer, renderer, decoder, FFmpeg, MIME, codec dependency, fallback, hardware/software selection, or playback-format file was modified.

## I. GitHub

- Branch: `repair/apk-v3-root-cause`
- Push result: confirmed by GitHub; implementation commit `5ee4fb951faeb697b6215a1a4714cb94104f50fc`
- Repository: `https://github.com/newprophecy2-cloud/ProphecyVerse.git`

## J. Remaining limitations

Physical/emulator verification and a signed release APK remain unavailable in this sandbox. The implementation does not claim those tests were performed.
