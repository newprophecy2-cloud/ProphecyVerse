# Prophecy Verse APK 3.0.1 — Baseline Forensic Audit

**Repository:** `newprophecy2-cloud/ProphecyVerse`  
**Branch:** `apk-v3`  
**Audited baseline:** `9265cdd3880068c1524d6bdfe70d891f38bf0308`  
**Starting working tree:** clean  
**Package/version:** `com.aistudio.prophecyverse.vykqtb`, version name `3.0.1`, version code `6`.

## Architecture observed

The app uses Retrofit/Moshi for the ProphecyVerse API, Room for device-local catalog/history/watchlist/download data, Compose/StateFlow for UI, and Media3 for playback. `AnimeRepository.refreshAnimeDetails` reads API details and episode lists, maps them into Room, and replaces the episode snapshot only for a nonempty response considered complete (`app/src/main/java/com/example/data/repository/AnimeRepository.kt:573-763`). Detail observes Room flows and a full all-episodes list (`app/src/main/java/com/example/ui/viewmodels/ViewModels.kt:189-203`); the Compose episode list is lazy, but repository/DAO access is not paginated (`app/src/main/java/com/example/data/local/Daos.kt:94-101`). Room schema is version 5 and includes anime, seasons, episodes, personal data, and downloads (`app/src/main/java/com/example/data/local/AppDatabase.kt:10-27`).

## Confirmed issues against the implementation prompt

1. **No shared catalog metadata store/writer is present.** Firebase client code handles personal history/watchlist, authentication, notices, analytics, installation, and version control; it does not read/write anime, season, or episode metadata. `firestore.rules:50-53` contains personal-data paths and public-readable app config/notices; there are no catalog matches, and unmatched paths deny access. No trusted synchronization writer is present in this repository. Therefore shared catalog writes are blocked unless an already-authorized external trusted writer is supplied; APK code must not bypass this.
2. **No bounded episode window.** The API/repository and Room observation load the complete episode list; the current pre-resolve queue handles two upcoming items after playback starts, not a metadata window (`PreResolveQueueManager.kt:21-36,73-92`).
3. **Source discovery is not automatic on Anime selection.** Detail initialization refreshes catalog metadata; source fetch occurs from the explicit source chooser or when entering playback (`ViewModels.kt:252-302,529-595`). Existing background pre-resolution is limited to next playback episodes.
4. **Provider identity is not explicit in the source DTO.** `ProphecySourceDto` has `id`, `label`, quality, codec, language, size, downloadable, and expiry fields, but no provider ID/name/structured metadata (`data/remote/ProphecyVerseDtos.kt:60-70`). The actual server payload was not queried; field semantics cannot be confirmed from this source-only audit.
5. **Source candidate handling has potential lossy/incorrect state semantics.** `fetchEpisodeSources` treats absent `sources` as an empty successful list, filters blank IDs and expired sources, then deduplicates by source ID alone (`AnimeRepository.kt:805-846`). It does not interpret `sources_status`, `sources_partial`, or `source_error` although those are modeled. This may conflate partial/error/empty results and cannot distinguish identical source IDs from different providers without backend identity fields. `label` and source IDs remain available in the UI.
6. **Firestore and backend failures are not unified into the requested explicit loading states.** The source result API is `Result<List<...>>`; detail UI/viewmodel has basic busy/error states, but not the full per-provider and Firestore quota state models required in the prompt.
7. **Performance constraints are partly addressed but not measured.** Repository methods use IO context and some in-memory request deduplication; lists remain unbounded. No instrumentation/performance measurements were found. WorkManager is not present.

## Safety and build constraints

- Existing API contracts and Media3 should remain untouched except where an APK-side defect is established.
- Firestore rules must not be changed or bypassed to enable global writes. The app can implement safe read-only/offline-compatible shared reads only if a compatible existing authorized collection/contract is proven; current source does not establish one.
- Room migrations must preserve history/watchlist/preferences; current schema has migration history and no destructive fallback (`AppDatabase.kt:42-173`).
- Gradle project configuration resolves successfully. The execution environment currently has no `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `adb`, `sdkmanager`, or `apksigner`; local Android build/test/install verification is presently blocked by missing SDK.
- The checked-in audit/execution reports refer to earlier baselines/build environments and are not validation of this commit. In particular, `FINAL_AUDIT_REPORT.md` describes version 3.0.0/code 5, while the current Gradle configuration correctly specifies 3.0.1/code 6.

This is a source inspection of the APK repository only. No live backend response, Firebase permissions, production rules, provider availability, playback, device behavior, or global quota was verified.