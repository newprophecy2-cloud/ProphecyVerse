# Shared Firestore Catalog implementation report

## Status

APK-side repository integration and real activity hooks have been added to the prior partial source. Production sharing is **not verified or operational under the checked-in Firestore rules**, which deny the new paths by default. The updated implementation has not compiled or run tests in this environment because Android SDK configuration is missing.

## Implemented

- `SharedCatalogRepository` uses the existing Firebase Android Firestore and Auth SDKs. It supports cache-first lookup, bounded direct document reads, schema/document/content validation, a separate Room cache, and transaction-based writes that compare `metadataHash` before changing a document.
- Backend remains authoritative. Backend metadata is stored locally and queued when new/changed or when a server read indicates the shared record is missing/stale. Shared data is cached separately and does not overwrite app catalog records.
- Search consumes actual ProphecyVerse search DTOs and syncs at most 20 explicitly typed Anime/Movie items per event from a separate lifecycle-scoped coroutine after local Room results are visible.
- Detail refresh publishes the actual backend detail plus bounded real season/episode records and supplied genre values. Movie detail does not create seasons or episodes. User-selected season triggers a repository operation based on actual stored season/episode rows. Episode-open metadata is taken from actual backend-derived Room episode records in a separate lifecycle-scoped coroutine, so Firestore work does not delay playback source resolution; source candidates and playback URLs are excluded.
- Category collection routing exists, but the supplied API DTOs contain no category catalog list, so no category documents are fabricated.
- Room queue is persistent, document-key deduplicated, capped at 500 total records (pending records are not aged out), and processed sequentially in batches of at most 20. Retry uses exponential backoff with jitter, up to six failures. Permission/auth/invalid-field errors become terminal and are not retried indefinitely. Work runs off the main thread, checks Android internet capability, honors coroutine cancellation, and persists on app restarts. Pending-only recovery runs at Home activation and when network connectivity returns; no startup catalog scan occurs.
- Firestore timestamps use server transforms. `updatedAt` only changes when the metadata hash differs. Writes use allow-listed collections and fields. Concurrent clients use transactions and deterministic document IDs.
- Existing Firebase rules were inspected and left unchanged.

## Not implemented / not verified

- The existing rules have no `shared_catalog_*` matches; expected read/write result is permission denial. No permissions were bypassed, and no rule deployment was made. Cross-user reads/writes cannot be claimed.
- Direct APK write permissions, Firebase Authentication/rules behavior, Emulator tests, and live production access were not verified. Client-side checks/hashes cannot prove metadata came from the trusted backend.
- Queue retries are attempted on subsequent app activation, activity-triggered sync, or network restoration. No WorkManager dependency or exact-time scheduled retry was added; a process that stays open without another trigger will not wake exactly at `nextAttemptAt`. Startup recovery flushes only the first due batch (20 records) and does not scan the catalog.
- No server-authoritative backend revision/timestamp exists in the observed DTO contract. Conflict policy prefers the latest backend response processed by the APK. A malicious or stale authorized client cannot be reliably distinguished using client clocks/hash values alone.
- No dedicated category DTO/endpoint exists. Genre records use only real genre strings supplied in anime detail.

## Validation report

- Archive/source identity: application ID `com.aistudio.prophecyverse.vykqtb`, namespace `com.example`, version `3.0.1` / code `6`, SDK values, Firebase project `prophecy-verse-483df` and backend URL were preserved.
- Gradle test attempt: `./gradlew testDebugUnitTest --tests com.example.SharedCatalogMetadataTest`; failed before task execution because Android SDK is missing (`ANDROID_HOME`/`ANDROID_SDK_ROOT` unset; no `local.properties`). No Android tests, migration tests, compile, or APK build completed.
- Firebase Emulator unavailable/not run. No test pass is claimed.
- Updated ZIP integrity check: run during packaging; record final hash in the task response/checksum file.

## Modified files across both implementation passes

- `APK.md`
- `FIRESTORE_SCHEMA.md`
- `SHARED_FIRESTORE_IMPLEMENTATION_REPORT.md`
- `FIRESTORE_SECURITY_REVIEW.md`
- `app/src/main/java/com/example/data/firebase/SharedCatalogMetadata.kt`
- `app/src/main/java/com/example/data/firebase/SharedCatalogRepository.kt`
- `app/src/main/java/com/example/data/local/SharedCatalogEntities.kt`
- `app/src/main/java/com/example/data/local/SharedCatalogDao.kt`
- `app/src/main/java/com/example/data/local/AppDatabase.kt`
- `app/src/main/java/com/example/data/repository/AnimeRepository.kt`
- `app/src/main/java/com/example/ui/viewmodels/ViewModels.kt`
- `app/src/main/java/com/example/data/download/DownloadManager.kt`
- `app/src/test/java/com/example/SharedCatalogMetadataTest.kt`
- `app/src/test/java/com/example/SharedCatalogRepositoryContractTest.kt`

## Room migration report

Database v7 advances to v8 using additive migration `MIGRATION_7_8`, creating `shared_catalog_cache` and `shared_catalog_pending` plus indices. Existing history, watchlist, downloads, preferences, profiles, anime, seasons/episodes, and source cache tables are not dropped or rebuilt. Migration execution remains unverified because Android SDK/test execution is unavailable.
