# ProphecyVerse APK v3 — Implementation and Validation Report

**Repository:** `https://github.com/newprophecy2-cloud/ProphecyVerse`  
**Branch:** `apk-v3`  
**Starting commit:** `dc3a9af394f659f1fbedb1393f03e0280af9111e`  
**Final commit:** `dc3a9af394f659f1fbedb1393f03e0280af9111e` (changes remain uncommitted)  
**Starting working tree:** clean  
**Final working tree:** modified, not committed; only the files listed below changed.  
**Package/version:** `com.aistudio.prophecyverse.vykqtb`, `3.0.1` / version code `6` (unchanged).

This report distinguishes source-inspection findings from executed checks. No production Firebase data, deployed Firestore rules, backend repository, credentials, or provider services were changed or accessed.

## Changes made

| Finding and impact | Evidence and repair |
|---|---|
| **Source chooser discarded the chosen source.** A user could tap a provider candidate and play the ordinary candidate order instead. | `app/src/main/java/com/example/ui/screens/DetailScreen.kt:98,781` now passes `src.id`; ordinary play actions pass `null`. `ui/navigation/Screen.kt:16-21` adds an optional URI-encoded `preferredSourceId` query parameter. `MainActivity.kt:383-413` reads it and passes it to `PlayerViewModel`; `ui/viewmodels/ViewModels.kt:337-341,454-460,529-671` uses it for the first resolution. The ViewModel now errors when the selected ID is not in that episode's source list, puts it first, and verifies the resolver's returned source ID before player initialization. A route regression test was added at `app/src/test/java/com/example/V3CriticalBugFixesTest.kt:36-46` (not run because Android SDK configuration is unavailable). Controlled fallback to another listed source remains possible if the selected source fails; the UI does not currently disclose fallback explicitly. |
| **Overlapping resolutions could update shared state out of order.** | `ui/viewmodels/ViewModels.kt:419-424,529-555,598-671,699-787` now retains/cancels a playback-resolution `Job`, increments a request generation, checks that generation after asynchronous lookups/resolution, uses a request-local candidate list, and has fallback/retry callbacks capture the request token and candidate index. Resolved identity is checked before applying the URL. Rapid-switch behavior was not dynamically tested. |
| **A resumed download could corrupt the file if the server ignored `Range` or returned an incorrect partial offset.** | `app/src/main/java/com/example/data/download/DownloadManager.kt:299-338` now restarts from byte zero after a non-206 success to a ranged request, validates `Content-Range` against the partial-file offset, and only appends when the offset matches. No real HTTP server/download acceptance test was run. |
| **History synchronization unconditionally overwrote progress.** | `app/src/main/java/com/example/data/firebase/CloudHistoryRepository.kt:46-53,72-85,163-175` now compares `lastPlayedAt` in a Room transaction for local writes and pulls, uses a Firestore transaction for remote writes, and preserves an existing completion flag. This is a best-effort timestamp conflict guard; device clock skew remains possible, and multi-device tests were not run. |
| **Installation Firestore rules combined create/update and allowed owner takeover.** | `firestore.rules:10-15,28-49` separates create/update, requires an explicit create schema allowlist, and makes `owner_uid` immutable by requiring both the old and new owner to equal the caller on update. The file is only a local source change; it was not deployed. |
| **Analytics rules did not bind event identity to an owned installation or constrain event contents.** | `firestore.rules:10-15,56-76` now allow only known client event names, the expected top-level keys, bounded identifiers, timestamp/parameter types and keys, and an installation document owned by the authenticated UID. These restrictive rules need Emulator validation against all legitimate event flows before deployment; rate limiting is not implemented by these rules. |
| **Device-scoped installation preferences were eligible for backup/transfer.** | `app/src/main/res/xml/backup_rules.xml:8-11` and `data_extraction_rules.xml:6-13` exclude `prophecy_install_prefs.xml` from cloud backup and device transfer while leaving other app data backup enabled. XML syntax was checked; actual OS restore behavior was not tested. |
| **Release test expected version code 5.** | `app/src/test/java/com/example/V3CriticalBugFixesTest.kt:30-33` now expects code 6 and retains name `3.0.1` and the required package identity. The test was not run. |
| **README described an earlier successful build/test as if it applied to this checkout.** | `README.md:110` now labels that result historical. `FINAL_AUDIT_REPORT.md` is already dated 2026-11-02 and records an older 3.0.0/code-5 APK; it remains a historical report rather than being rewritten as current evidence. |

## Existing features observed

Source inspection found a real byte-based download progress path already present: `DownloadManager` increments progress from streamed bytes, publishes a `StateFlow`, persists progress periodically, and the Downloads screen observes the live map. Thus the prompt's “progress not appearing” report was not confirmed as a missing byte counter in this checkout. The repository does not show persistent WorkManager/foreground-service execution or guaranteed recovery after process death.

Media3's playback path already applies resolved headers to its HTTP data source. The audit did find that download headers are held only in memory after resolution, so a later resume/retry using the stored URL may omit provider-required headers. This was not changed because durable source/header metadata needs a schema and migration decision. Direct Play error bodies are also flattened to generic status messages in `AnimeRepository`; actual backend error schema was unavailable, so structured parsing was not changed.

## Audited issues not repaired or not validated

- **Room migration history loss:** `AppDatabase.MIGRATION_2_3` (source audit: `app/src/main/java/com/example/data/local/AppDatabase.kt:83-123`) intentionally retains only the latest episode per anime. Data already dropped by an applied migration cannot be reconstructed. No migration was rewritten because silently changing shipped migration behavior risks user databases.
- **Download lifecycle:** synchronous OkHttp calls, in-memory job ownership, process-death recovery, progress persistence at pause, and completion validation remain concerns. No real pause/resume/cancel, restart, network interruption, or offline playback acceptance test was possible.
- **Analytics rules:** the checked-in rules need Firebase Emulator tests; there is no Firebase CLI/emulator in this environment. No production deployment was attempted.
- **Release signing:** `debug.keystore` is tracked and is referenced by the configured debug build. It was preserved to avoid breaking the existing debug/AI Studio signing setup. Release keystore and signing credentials were unavailable; no release APK or signing-certificate fingerprint can be reported.
- **Actual backend/provider playback and 502 attribution:** no backend repository or provider endpoint was used, so no server-side/upstream/media-server attribution or real playback result is claimed.

## Validation results

**Passed:**

- Repository/branch/baseline identity confirmed. The starting branch was `apk-v3`, clean, at the exact requested baseline.
- `git diff --check` passed after changes.
- Python `xml.etree.ElementTree` parsed `backup_rules.xml`, `data_extraction_rules.xml`, and `AndroidManifest.xml` successfully.
- Static inspection confirmed the required package, version name, and version code remain unchanged.

**Failed / blocked:**

- Command attempted: `./gradlew :app:testDebugUnitTest --no-daemon --console=plain`. Gradle 9.3.1 downloaded and configured, then failed before compilation with `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in .../ProphecyVerse/local.properties`.
- No Android SDK/API 36, `adb`, Firebase CLI/emulator, or connected device is installed/available in this sandbox. A release signing keystore and the required signing environment variables were also absent.

**Not tested:**

- Android compilation, all JVM/Robolectric tests (including the new route test), instrumentation tests, APK build/install/signature verification, Firestore Emulator rules tests, migration tests, device backup/restore, real playback/provider fallback, download progress/control/persistence/offline tests, and notifications.

## Changed files

`README.md`; `app/src/main/java/com/example/MainActivity.kt`; `app/src/main/java/com/example/data/download/DownloadManager.kt`; `app/src/main/java/com/example/data/firebase/CloudHistoryRepository.kt`; `app/src/main/java/com/example/ui/navigation/Screen.kt`; `app/src/main/java/com/example/ui/screens/DetailScreen.kt`; `app/src/main/java/com/example/ui/viewmodels/ViewModels.kt`; `app/src/main/res/xml/backup_rules.xml`; `app/src/main/res/xml/data_extraction_rules.xml`; `app/src/test/java/com/example/V3CriticalBugFixesTest.kt`; `firestore.rules`.

No commit was created. The release APK deliverable is blocked by the missing Android SDK/API 36 and release signing materials; no APK artifact or SHA-256 is claimed.
