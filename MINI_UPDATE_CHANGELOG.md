# Prophecy Verse User APK — Mini Update

## Implemented

- **Downloads:** Preserved the existing anime → season → episode grouping and player-style cards. Download records continue to carry the original anime title, poster URL, season number, episode number, status, progress, and file-size metadata. Episode IDs remain the Room primary key, preventing duplicate download entries.
- **Update notice:** “Remind Me Later” now stores both the optional update version code and dismissal timestamp in local preferences. The same optional update is suppressed immediately, may reappear after the 24-hour reminder interval or a later app launch, and a newer or mandatory update is not suppressed by an older dismissal.
- **History:** Playback records now use `history_<animeId>_<episodeId>` consistently for local writes and cloud sync. Room version 5 repairs legacy keys, preserves the most recently watched row for duplicate anime/episode pairs, and adds a unique `(animeId, episodeId)` index. Repeated playback callbacks therefore update one record instead of creating duplicates.
- **Anime details:** Preserved the existing backend-driven season chips, episode list, loading/empty/error states, source selection, and playback navigation. The same season and episode metadata remains shared with downloads and history.

## Changed files

- `app/src/main/java/com/example/MainActivity.kt`
- `app/src/main/java/com/example/data/local/Entities.kt`
- `app/src/main/java/com/example/data/local/AppDatabase.kt`
- `app/src/main/java/com/example/data/repository/AnimeRepository.kt`
- `app/src/main/java/com/example/data/firebase/CloudHistoryRepository.kt`

## Verification

- Source inspection completed for download metadata propagation, update UI wiring, history writes/listeners/queries, and detail season/episode rendering.
- `git diff --check` is pending repository initialization in the extracted source archive.
- Gradle wrapper downloaded successfully, but the sandbox has no Android SDK (`ANDROID_HOME`/`ANDROID_SDK_ROOT` unset and no `adb`, `sdkmanager`, or `aapt2` found). Therefore `:app:testDebugUnitTest` and `:app:assembleDebug` could not be executed in this environment.
- A release APK was not fabricated. Build the supplied source on an Android SDK-equipped machine using `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`.
