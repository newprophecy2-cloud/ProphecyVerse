# ProphecyVerse APK Repair Audit Report

## Repository state

- Repository: https://github.com/newprophecy2-cloud/ProphecyVerse
- Repair branch: https://github.com/newprophecy2-cloud/ProphecyVerse/tree/ai-studio/apk-v3-repair
- Starting commit: `743a3924237d81429fa07186fab415be19243877`
- Base branch relationship: repair branch is one commit ahead of `apk-v3` at `4341a22ef099ea85e43c98e800e548174e2fa66a`
- Working copy: isolated checkout at `/home/ubuntu/prophecyverse-repair`
- Starting working tree: clean
- Existing scheduler repair: verified and preserved; no duplicate change was made to its request-code or notification-permission fixes.

## Project inventory

The checkout contains 114 non-Git files and 50 non-Git directories, one Android `app` module, Kotlin/Compose UI, Room persistence, Retrofit/Moshi networking, Firebase integrations, Media3 playback, download management, JVM tests, and Android instrumentation tests.

## Issue register

| ID | Severity | Area | Confirmed root cause | Repair/status | Verification |
|---|---|---|---|---|---|
| PV-001 | P0 external blocker | Firebase identity | `google-services.json` has the expected APK package entry but duplicates the same `mobilesdk_app_id` for `com.prophecy.verse` and `com.aistudio.prophecyverse.vykqtb`; Console registration cannot be verified from this environment. | Unresolved externally. Documented exact Firebase Console verification steps in `APK.md`; no credentials were invented or replaced. | Static inspection of `app/google-services.json`; live Console verification not available. |
| PV-002 | P0/P1 | Installation tracking | Stable UUID and anonymous-auth attempt exist, but checked-in Firestore rules require a signed-in owner for installation writes. | Partially resolved in source; APK preserves identity and retries safely, but fully login-independent server persistence requires compatible deployed rules/trusted writer. | Inspected `InstallationManager`, `AuthManager`, and `firestore.rules`. |
| PV-003 | P1 | Download source security | Existing download rows could retain a signed playback URL and the next attempt reused it without re-resolving headers. | Fixed: every active download resolves a fresh direct-play URL and headers; persisted `downloadUrl` is cleared. | Static diff review; runtime test blocked by missing Android SDK. |
| PV-004 | P1 | Download integrity | Completion was based primarily on rename success and did not reject an empty temporary file. | Fixed: empty/unavailable temp files become `FAILED`; final file must exist and be non-empty before `COMPLETED`. | Static diff review; runtime test blocked by missing Android SDK. |
| PV-005 | P2 | Download quality UI | UI fabricated `1080p`, `720p`, `480p`, and `360p` choices when backend source metadata had no qualities. | Fixed: only distinct nonblank backend-derived qualities are shown; empty metadata yields no fabricated chips. | Static diff review. |
| PV-006 | P1 | Media container handling | Download filenames remain `.mp4` regardless of verified container. | Unresolved/partially fixed. The backend DTO does not provide a verified container field, and this pass did not invent one. Documented in `APK.md`. | Static inspection. |
| PV-007 | P1 | IP-country persistence | Client-side lookup is approximate and cannot be authoritative for public IP behind proxies. | APK-side cached lookup retained; trusted server-side detection/persistence remains required and documented. | Inspected `NoticeManager`; no location permissions present. |
| PV-008 | P1 | Build/test environment | No Android SDK is installed or configured. | External environment blocker. | `./gradlew test --no-daemon --stacktrace` failed during Android SDK discovery before compilation. |

## Changed files in this pass

- `APK.md`
- `REPAIR_AUDIT_REPORT.md`
- `app/src/main/java/com/example/data/download/DownloadManager.kt`
- `app/src/main/java/com/example/ui/screens/DownloadComponents.kt`

The existing `LocalNotificationScheduler.kt` repair from `743a392` was inspected and left intact.

## Verification performed

Passed:

- GitHub authentication and repository access.
- Remote branch enumeration and repair branch existence check.
- Repair branch HEAD and existing commit verification.
- Existing repair commit diff inspection.
- Repair branch relationship to `apk-v3`.
- Clean isolated checkout creation.
- Project inventory and identity inspection.
- Application ID `com.aistudio.prophecyverse.vykqtb` inspection.
- Version name `3.0.1` and version code `6` inspection.
- Manifest permission inspection; no location permissions are present.
- Firebase project metadata inspection.
- Existing Firestore rules inspection.
- `git diff --check` after modifications.

Not run or blocked:

- JVM/unit tests: blocked before task execution because the Android SDK location is missing.
- Android instrumentation tests: not run; no SDK/emulator/device.
- `assembleDebug`/release APK build: not run to completion; same missing SDK blocker.
- Firebase live validation: no Firebase Console/emulator access.
- Real playback, download, offline, notification, and device lifecycle tests: no emulator/device and no claim is made that they passed.

## Build status

**Not built.** The environment has Java 21 but no `javac` on PATH, no `ANDROID_HOME`/`ANDROID_SDK_ROOT`, no `adb`, and no `apksigner`. Gradle itself was downloaded successfully, but project evaluation stopped with `SDK location not found` before compilation. No APK artifact exists and none is provided.

## Permission audit

Manifest permissions:

- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`
- `android.permission.WAKE_LOCK`
- `android.permission.POST_NOTIFICATIONS`
- `android.permission.RECEIVE_BOOT_COMPLETED`
- `android.permission.REQUEST_INSTALL_PACKAGES`

Explicit location result:

- `ACCESS_FINE_LOCATION`: absent
- `ACCESS_COARSE_LOCATION`: absent
- `ACCESS_BACKGROUND_LOCATION`: absent

## Final change summary

### Fresh signed URL resolution

Before: an existing `DownloadEntry.downloadUrl` could be reused on a later attempt, even though the URL and headers may have expired.

Root cause: signed playback data was treated as durable download state.

After: download attempts always resolve through the existing source and `/api/v1/play/direct` workflow and do not persist the resolved URL.

### Download completion guard

Before: a temporary file could be renamed and marked complete without checking that it contained bytes.

After: empty or missing temporary files are marked failed, and completion requires an existing non-empty final file.

### Quality choices

Before: the download bottom sheet inserted a fixed list of qualities when no backend quality metadata existed.

After: the UI uses only backend-derived, nonblank quality values and otherwise leaves quality selection unavailable.

## Remaining external actions

1. Verify or correct the Firebase Android app registration and download the exact package-matched `google-services.json`.
2. Provide an Android SDK/build environment and a real emulator/device for validation.
3. If installation tracking must work without any authenticated Firebase identity, implement a trusted server/Cloud Function path or explicitly revise deployed rules; do not enable unrestricted public writes.
4. Implement trusted server-side IP-country detection/persistence if admin-grade country data is required.
5. Implement trusted shared-catalog synchronization/versioning if Firestore is intended to be the shared catalog authority.

No backend, deployed Firebase rules, production service, Play Store listing, or external integration was modified.
