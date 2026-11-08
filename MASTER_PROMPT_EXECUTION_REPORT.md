# ProphecyVerse Master Prompt Execution Report

## Repository and baseline

- Repository: `newprophecy2-cloud/ProphecyVerse`
- Branch: `apk-v3`
- Required starting commit: `8963be855e69fe2698f79c52a3e24a5a2f9b7d91`
- Starting source was verified before edits. The previously requested tracked `debug.keystore` commit was preserved.
- Package ID: `com.aistudio.prophecyverse.vykqtb`
- Version name: `3.0.1`
- Version code: `6`

## Implemented fixes

### Source discovery and episode isolation

- Source discovery now filters blank IDs, expired source entries, and duplicate source IDs before exposing them to the UI.
- Source-fetch locking is keyed by backend anime ID plus episode ID instead of using one global mutex. A slow episode cannot block discovery for unrelated episodes.
- Playback state is cleared when changing episodes so the prior episode's source, audio, subtitle, quality, and decoder metadata cannot remain visible while the new episode resolves.
- Pre-resolved playback entries now include backend anime identity and episode identity in their keys. A prepared URL cannot be reused for another catalog identity.

### Direct playback resolution

- Concurrent direct-play requests for the same source ID are serialized and share the resolved cache.
- Cached results are reused only within the short cache lifetime and while their server expiry is valid.
- Resolved data is rejected when the returned source ID does not match the requested source, the URL is not an allowed HTTP(S)/file URL, or the expiry timestamp is already past.
- Pre-resolution performs the same source-ID, URL, and expiry checks.

### Catalog matching and grouping safety

- Removed the hardcoded One Piece catalog mapping and title-specific matching exceptions. Matching now relies on the available catalog metadata and explicit mappings rather than inventing a relationship for a particular anime.
- Existing backend-provided seasons and episode lists continue to be persisted under the selected anime identity. Separate titles are not merged without relationship data.

### Firebase installation tracking

- Installation writes now use a keyed mutex to prevent racing writes from startup, auth callbacks, and FCM token refresh.
- A durable pending-sync marker remains set after a failed write, so a later launch/heartbeat retries the stable installation identity instead of creating another identity.
- Existing authentication, analytics, and messaging behavior was preserved.

### Firestore notices and local notifications

- Added local AlarmManager scheduling for published notices, independent of FCM delivery.
- Schedules at most 29 deterministic notification slots per installation per 24-hour cycle, cancels/replaces the same slots when notice data changes, persists the latest published notice snapshot locally, and reschedules after reboot.
- The implementation is explicitly best-effort per installation; Android background limits mean it cannot guarantee globally delivered notifications or exact timing.
- Existing foreground Firestore notice display remains in place.

## Validation performed

- Kotlin compilation: `./gradlew --no-daemon :app:compileDebugKotlin` — **passed**.
- `git diff --check` — **passed**.
- Version and package configuration inspected — version name `3.0.1`, version code `6`, package ID unchanged.
- The previously pushed `debug.keystore` was verified with alias `androiddebugkey`, password `android`, and SHA-256 file hash `b218ed21793ca91216e6223837b97d190e3c322d8a7ca17bfea3aea75e9c564a`.

## Build/test limitations

A full `test` and `assembleDebug` invocation was attempted after installing Android API 36, Build Tools 36.0.0, and a full JDK 21. The build reached resource processing but failed because this ARM64 environment received x86-64 AAPT2 binaries from the Android SDK/Maven artifacts; the binaries cannot execute here (`Exec format error` / AAPT2 daemon startup failure). A resource-skipped unit-test attempt cannot produce a valid test resource APK, so unit tests are not claimed as passed.

Consequently, no signed APK artifact, APK SHA-256, APK certificate fingerprint, physical-device playback test, live-provider test, live-Firebase rules test, reboot delivery test, or performance trace is claimed as verified in this environment. The debug signing keystore is present and tracked, but it is not a release signing identity.

## Files changed

- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/data/firebase/InstallationManager.kt`
- `app/src/main/java/com/example/data/firebase/VersionControlManager.kt`
- `app/src/main/java/com/example/data/notice/LocalNotificationScheduler.kt`
- `app/src/main/java/com/example/data/notice/NoticeManager.kt`
- `app/src/main/java/com/example/data/repository/AnimeRepository.kt`
- `app/src/main/java/com/example/data/repository/TitleMatcher.kt`
- `app/src/main/java/com/example/player/PlayerManager.kt`
- `app/src/main/java/com/example/player/PreResolveQueueManager.kt`
- `app/src/main/java/com/example/ui/viewmodels/ViewModels.kt`
