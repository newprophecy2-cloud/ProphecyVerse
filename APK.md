# ProphecyVerse Android APK

## Verified project identity

- Repository: `newprophecy2-cloud/ProphecyVerse`
- Repair branch: `ai-studio/apk-v3-repair`
- Starting commit for this repair pass: `743a3924237d81429fa07186fab415be19243877`
- Application ID: `com.aistudio.prophecyverse.vykqtb`
- Namespace: `com.example`
- Version name: `3.0.1`
- Version code: `6`
- Minimum SDK: 24
- Target/compile SDK: 36
- Backend base URL: `https://api.prophecyverse.tech`

## Architecture

The APK is a single Kotlin/Jetpack Compose module. Room provides the local catalog, episode, source-metadata, watchlist, watch-history, preference, profile, and download cache. Retrofit/Moshi access the ProphecyVerse API. Firebase Auth, Firestore, Messaging, Analytics, and Remote Config-related version verification provide account, operational, notification, analytics, and update features. Media3/ExoPlayer performs online and local playback.

The backend remains authoritative for catalog metadata, seasons, episodes, source IDs, fresh playback URLs, and playback resolution. Resolved playback URLs and request headers are intentionally not stored in the source metadata cache.

## Firebase identity and registration

The checked-in configuration targets Firebase project `prophecy-verse-483df` (project number `357805299753`, storage bucket `prophecy-verse-483df.firebasestorage.app`) and contains package entries for both `com.prophecy.verse` and `com.aistudio.prophecyverse.vykqtb`. The two entries currently contain the same `mobilesdk_app_id`; this must be verified in Firebase Console before release. The APK source does not invent or replace Firebase credentials.

Required console verification:

1. Open Firebase Console and select project `prophecy-verse-483df`.
2. Open Project settings > Your apps.
3. Confirm that an Android app exists for package `com.aistudio.prophecyverse.vykqtb`.
4. Confirm that its `mobilesdk_app_id`, API key, and certificate restrictions are the values intended for that Android app, not a copied value from `com.prophecy.verse`.
5. Download the generated `google-services.json` for the exact package and replace `app/google-services.json` only after reviewing it.
6. Confirm that Anonymous Authentication, Firestore, Cloud Messaging, and Analytics are enabled as required by the deployed application.

No Firebase Console action was performed by this repair pass, and deployed Firestore rules were not changed.

## Firestore paths and security boundary

The APK uses the existing paths for `users/{uid}`, `installations/{uid}_{installationId}`, `watch_history/{uid}/...`, `watchlist/{uid}/...`, `notices/{noticeId}`, `app_config/version_control`, and `analytics_events/{id}`. Admin collections remain server/admin-only. The checked-in `firestore.rules` requires a signed-in owner for installation and analytics writes; the APK therefore uses stable local identity plus anonymous Firebase Auth when available and falls back to local guest mode when Firebase is unavailable. It must not claim that unauthenticated Firestore registration is complete when rules do not authorize it.

## Installation identity and FCM

A cryptographically random UUID is generated once and persisted in app-private SharedPreferences. It is reused across process restarts and upgrades, and is independent of login/logout and FCM token rotation. Reinstalling the app or clearing app data creates a new local identity. Installation records use an owner UID plus installation ID as an idempotent document key. FCM token refresh updates the same installation record.

Installation metadata is intentionally limited to operational device/app fields. The APK does not request GPS or collect IMEI, serial number, MAC address, contacts, SMS, photos, or microphone data.

## Country tracking

Country detection is approximate IP-based lookup with a cached result and no location permission. The APK does not trust client-supplied proxy headers. The current implementation uses the existing network path to query country services and retains the prior value on failure. This is not GPS accuracy and may be wrong for VPNs, proxies, carrier NAT, or mobile networks.

For authoritative analytics or persistence, the backend must determine the public client IP behind verified proxy infrastructure and write country data through a trusted server path. No new backend endpoint was invented by this repair.

## Permissions

The manifest currently declares only INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, and REQUEST_INSTALL_PACKAGES. It does not declare ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, or ACCESS_BACKGROUND_LOCATION. Notification delivery checks POST_NOTIFICATIONS on Android 13+.

## Backend API contract

The APK uses only the existing routes:

- `GET /api/v1/anime/search`
- `GET /api/v1/anime/{id}`
- `GET /api/v1/anime/{id}/episodes/{episode_id}/full`
- `POST /api/v1/play/direct` with `{ "source_id": "..." }`

Source discovery and direct-play resolution are separate states. A candidate is not presented as playback-ready merely because it was discovered. Direct-play data is accepted only when the response is successful, has a non-empty HTTP(S)/file URL, matches the requested source ID, and is not expired.

## Movie and episode handling

Movies are represented by the backend's actual metadata and episode/source identifiers. The APK does not invent a movie endpoint or fabricate an episode when the backend does not provide one. Episode data preserves backend IDs and season/episode numbers; backend season canonical IDs are stored separately from the local Room key. Partial responses are merged without deleting cached records.

### Focused Movie/catalog repair pass (2026-11-10)

- The backend search response explicitly returns `media_type: "movie"`, while a live detail response for a Movie-shaped entry may return `media_type: "series"` together with the real Season 1 / Episode 1 record. Search mapping now preserves explicit type, and detail refresh explicitly keeps an already-confirmed Movie category even when detail says `series`. Movie UI continues to suppress series season chips and next/previous episode pagination.
- Movie home entries no longer receive invented `Movie, Action, Animation` genres. Only genres already available from stored metadata or the backend are retained.
- The catalog-membership badge now says `Available in catalog`, not `Playable in app`; actual playback readiness still requires a real source candidate and successful direct-play resolution.
- Season/Episode parsing now skips blank IDs and non-positive backend season/episode numbers instead of coercing them to synthetic Season 1, retains absent titles as absent, and persists `seasons[].canonical_id` to `SeasonEntry.backendSeasonId`. The Room database moves forward from schema v6 to v7 with a nullable-column-only migration; the existing local `SeasonEntry.id` stays intact. A valid containing season may supply an omitted nested episode season. No season or episode is generated from an empty response.
- A live public API sample was observed through the existing contract: search returns an encoded backend ID, canonical ID, title, `media_type`, year and poster; detail returns `partial`, `seasons[].number`, and `seasons[].episodes[]` with backend episode IDs and numbers; episode-full returns `selected_episode` and `sources[]` including source `id` and `label`. These keys align with the current Moshi DTOs and the APK's full-episode request. Direct playback remains a separate `POST /api/v1/play/direct`; successful playback was not tested.
- This is a point-in-time sample, not a guarantee that every title/provider returns complete data. A detail response with `partial: true` is merged as non-authoritative; no Movie episode or source is synthesized.
- The pre-resolve cache previously judged freshness only by a 25-minute local TTL, even if the backend's signed playback URL `expires_at` was earlier. That is a confirmed APK-side stale-URL defect which could cause playback failure or a false source-retry path; whether it caused a specific reported incident is unknown without that URL's actual expiry. Prepared data now expires at either the backend expiry or local TTL. On playback retry, the client drops the failed prepared/direct response and force-refreshes source candidates before resolving again.
- The normal player's expired-link retry also previously allowed a fresh-looking source snapshot and its in-memory direct response to be reused. It now invalidates the failed direct response and requests a forced source refresh. This closes a verified local retry/cache defect. The sample live episode-full response had a valid source candidate and matching JSON fields, but the exact user-reported case of Termux returning candidates while the APK omits candidates was not reproduced; that symptom is not claimed fixed.
- Catalog refresh loading now resets in a tested `finally` helper for success, empty result, exception, and cancellation. Cancellation in optional title matching and AniList detail requests now propagates. Partial Season/Episode persistence previously read/merged/wrote the two Room tables separately; it now merges and writes both in a Room transaction.

### Verification outcome for this pass

- Confirmed package ID `com.aistudio.prophecyverse.vykqtb`, version name `3.0.1`, and version code `6` in `app/build.gradle.kts`; no GPS/location permission was added.
- Added regression tests for Movie type preservation, the observed Movie S1/E1 DTO including backend season canonical ID, partial-cache retention, loading cleanup, direct-play response parsing, and backend expiry of prepared playback data. Existing suite includes source DTO parsing, filtering, caching and retry-backoff tests. Tests and `assembleDebug` could not run because this environment has no Android SDK (`ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset and no conventional SDK directory is installed). Gradle stopped during configuration with `SDK location not found`; no test pass or APK build is claimed.
- `git diff --check` was not available because the supplied source ZIP is not a Git working tree. Whitespace and archive checks are recorded separately in the delivery report. Live API inspection was read-only. Direct-play playback, on-device UI, network failure/retry under a failing provider, Firestore reads/writes and app runtime were not exercised.

### Shared Firestore catalog status

No shared Firestore catalog read/cache or publisher exists in the APK sources or checked-in rules. Existing Firestore use is for account/installation data, private watch history/watchlists, notices, analytics and version configuration; backend/AniList metadata is cached in Room. There is no authenticated trusted metadata write endpoint, catalog document schema, publication/version service, or public-read catalog rule. Existing Analytics events are restricted by rules to a small private/operational allowlist and cannot be repurposed for catalog records. No client-side shared-catalog write, persistent publication queue, or Firestore catalog read was added: without a trusted publisher/schema contract, a queue would have no authorized consumer and could collect indefinitely. Private user data remains separate.

#### Proposed server contract (not implemented or deployed)

Subject to backend/Firebase owner review, a minimal contract could use a public-read `shared_catalog/{backend_content_id}` root document with backend-returned fields only (`content_id`, `media_type`, title/available alternates, artwork references, description, genres, release year/status), plus `seasons/{backend_season_id}` and `episodes/{backend_episode_id}` subcollections carrying actual IDs/numbers/titles. The publisher adds `schema_version`, an optional backend metadata timestamp only when supplied, a trusted `updated_at` server timestamp, and a `published` state. A trusted publisher—not APK clients—would fetch/validate authoritative backend data and write idempotently only on metadata changes. Proposed Firestore policy: clients may read only documents where `published == true`; client writes remain denied; only the trusted server/Admin publisher writes. Only backend-provided metadata belongs in catalog documents; source IDs are not needed for shared display metadata, and playback URLs/headers are expressly excluded. The collection paths and fields above are a proposal for review, not an existing schema.

To support the requested activity-triggered publication, the backend must provide a documented authenticated endpoint or trusted Cloud Function with Firebase Auth/App Check or equivalent validation, strict allowlisted payload validation, idempotency/deduplication, concurrency-safe publication, rate limits, and bounded retry semantics. The trusted service should resolve content IDs against the backend itself rather than trust arbitrary client-submitted metadata. Until that exists, APK activity cannot safely publish shared metadata and no metadata-upload queue/read integration is claimed. If an offline queue is later authorized, add a bounded Room table in a forward migration from the current database v7 with migration/rollback tests; do not reuse private watchlist/history or analytics collections.

#### Firebase registration evidence

`google-services.json` names project `prophecy-verse-483df` and includes an exact package entry for `com.aistudio.prophecyverse.vykqtb` as well as an entry for `com.prophecy.verse`; both list the same Firebase Android app ID. The APK application ID has a matching entry, so the extra entry alone does not prove a mismatch. Source inspection cannot establish that this is the intended live registration/project; verify in Firebase Console before release. No credentials or configuration were changed.

## Downloads and offline behavior

Downloads require the existing authenticated-account policy. Media is written to app-private storage through a temporary file and is published only after a non-empty file has been received. Pausing preserves the temporary file and resume uses HTTP range validation when supported. Completed local records are available to the offline player after logout as long as the installation and local file remain.

Fresh direct-play resolution is performed for every download attempt so expired signed URLs and their headers are not reused. Resolved URLs are not persisted in the download record. The current implementation still uses the established `.mp4` filename convention; container-specific extension selection and deep media validation remain a known improvement area because the backend contract does not currently expose a verified container field in the APK.

The download UI displays only qualities returned by the backend. It does not fabricate 1080p, 720p, 480p, or 360p choices when no quality metadata exists.

## Notifications

Published notices are read from Firestore, cached locally for scheduling, and scheduled with per-slot request codes. Existing repair commit `743a392` is preserved: cancellation uses the same `1000..1028` request-code range as scheduling, and notification delivery checks POST_NOTIFICATIONS before calling notify().

## Build and test

From the repository root:

```bash
./gradlew test
./gradlew assembleDebug
```

The exact environment status and test/build outcome for a repair pass must be recorded in the accompanying audit report. A successful Gradle task is not a substitute for a real emulator/device test. The APK must not be described as production-signed unless a real release signing key was used.

## External blockers and required server work

1. Verify the exact Firebase Android registration and download a fresh package-matched `google-services.json` if the current duplicate app ID is not intentional.
2. If login-independent installation analytics are required for all installations, provide a trusted server/Cloud Function write path or rules that explicitly authorize the intended anonymous identity without allowing arbitrary public writes.
3. Provide trusted backend IP-country detection/persistence if country data must be authoritative or queryable by Admin.
4. Provide backend catalog synchronization/versioning if Firestore is intended to be a shared catalog source. The APK must not let every installation write shared catalog records.
5. Preserve the existing source and playback API contracts; no backend deployment was performed by this repair.


## Current repair-pass addendum (2026-11-10)

This pass preserves the project identity and original API contracts. Confirmed APK-side fixes include authoritative movie classification from backend `media_type`, nullable nested episode-season parsing with containing-season fallback, and a terminal error state after the final playback candidate fails. A regression test covers omitted nested season fields. The Gradle wrapper executable bit was restored. README and implementation-report source-cache claims were aligned with the bounded persistent Room source-metadata cache.

The source archive was validated with `unzip -t`; no APK was built in this sandbox because Android SDK Platform 36 and a configured SDK path were unavailable. Unit tests, device playback, Firestore live reads/writes, emulator rules tests, and real installation acceptance therefore remain unverified. The full status, modified file list, blockers, security notes, and original archive hash are in `FINAL_REPAIR_REPORT.md` and `ORIGINAL_ZIP_SHA256.txt`.

Shared Firestore catalog publication/read/write remains intentionally blocked: the checked-in rules and source contain no authorized shared catalog schema or trusted writer. No live rules, Firebase settings, backend services, or production data were changed.

## Shared catalog implementation status (source-only)
The source contains metadata mapping and local Room v8 cache/queue schema for a proposed shared catalog. Existing checked-in rules contain no `shared_catalog_*` match, so shared catalog access is currently denied by Firestore's default deny. This source snapshot does not claim live shared reads/writes or a deployed rules change. See `SHARED_FIRESTORE_IMPLEMENTATION_REPORT.md`, `FIRESTORE_SCHEMA.md`, and `FIRESTORE_SECURITY_REVIEW.md`.

## Shared Firestore Catalog (APK-side source integration)
The APK now has a direct Firestore repository for allow-listed shared metadata reads, hash-checked transaction writes, local Room caching, and a bounded durable queue. Search results (maximum 20 per event), authoritative detail responses, actual nested seasons/episodes, supplied genres, explicit season selection and episode opening from the existing backend-derived Room record feed this path. Shared documents remain separate from app catalog entities; backend API data continues to drive the app. The queue is flushed only for due items in batches of at most 20, checks connectivity, retries transient failures with bounded exponential backoff/jitter, and terminally marks permission/invalid-data failures. Pending-only queue recovery runs at Home activation/network restoration; there is no startup catalog scan or dedicated WorkManager scheduling.

Important blocker: checked-in `firestore.rules` contains no `shared_catalog_*` matches. Firestore will deny those accesses unless the Firebase project's active rules separately authorize them. No rules were weakened/deployed and sharing is not claimed to work. Build/tests and migration tests remain unverified because the task environment lacks an Android SDK. See `SHARED_FIRESTORE_IMPLEMENTATION_REPORT.md` and `FIRESTORE_SECURITY_REVIEW.md`.
