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

Movies are represented by the backend's actual metadata and episode/source identifiers. The APK does not invent a movie endpoint or fabricate an episode when the backend does not provide one. Episode data preserves backend IDs and season/episode numbers; partial responses are merged without deleting cached records.

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
