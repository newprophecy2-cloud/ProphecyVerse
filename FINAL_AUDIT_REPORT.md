# ProphecyVerse User APK — Final Source Audit

**Audit date:** 2026-11-02
**Inputs:** User and Admin source ZIPs supplied for this task. The ZIP trees matched the existing GitHub branches at the start of work (`apk-V3` and `admin-v2`).

## Changes in the User APK

- Removed mandatory custom artifact SHA-256 and release-certificate SHA-256 verification. Legacy metadata keys may be read for compatibility but are not prerequisites and do not gate update availability.
- Kept numeric version-code comparison, Firestore server reads, package-name/version validation of downloaded APKs, HTTPS-source validation, FileProvider delivery, install-permission handling, and Android's native package-signature checks.
- Added `update_sources` fallback when `downloads` is absent/empty and retained actionable external HTTPS sources when a direct APK source is unavailable.
- Ensured a newly created user profile writes the safe `status=active` and `download_blocked=false` values required by the supplied User rules; existing moderation state is not reset.
- Kept the real Firestore notice listener. Its `published == true` query and `active` filtering match the Admin notice contract.
- Added a numeric version-code comparison regression test and clarified update-screen signature/install messaging.

## Firebase data and root-cause findings

### Admin User section

The User APK writes registered profiles to `users/{Firebase UID}` with fields including `email`, `is_anonymous`, `installation_id`, `last_login_at`, `app_version`, and `app_version_code`; `total_watch_seconds` may also be present. Admin reads now decode the fields actually written, use document IDs as UIDs, and page through users instead of silently stopping at the first 50 records.

A separate sign-in blocker was found in the Admin source: `AdminAuthManager` tested `installations` access and signed out the entire Admin session on `PERMISSION_DENIED`. The supplied Admin rules have no `/installations` match; the Admin rules proposal independently notes this gap. The probe was removed so Admin login is not conflated with a collection read, and installation/user screens surface permission errors. This does not grant Firestore access or bypass deployed rules.

The source rule files also use different role stores (`admin_users` versus `admin_roles`), and the supplied Admin rule file's `/users` create allowlist does not match the User APK's actual snake_case writes. The live deployed ruleset was not inspected. One canonical, reviewed ruleset must be aligned and deployed by an authorized Firebase operator before actual records can be guaranteed to load.

### Notice synchronization

The current User source already reads the real Firestore `notices` collection; it is not a placeholder-text implementation. New Admin notice writes use `published` and `active`, `message`, `severity`, `priority`, and the optional action aliases consumed by the User listener. Thus no new-notice field mismatch was found in the supplied app source. A legacy document without `published=true` will not be returned by the User query, and live rules/data were unavailable, so the precise cause of any historical production notice failure cannot be confirmed from ZIPs alone. Admin editing now initializes from effective values and clears removed optional action fields instead of retaining stale values.

### Update behavior

- **Missing Update button / “Version verification failed”:** the User updater required both custom fingerprints before offering an update, while the Admin publisher required the same fingerprints. Missing fields therefore produced a verification error and prevented an actionable update state. Those custom prerequisites were removed; the package installer still performs native signing-identity continuity checks.
- **Repeated update prompt:** the supplied client already fetches metadata from Firestore using `Source.SERVER` and compares numeric version codes. An added test confirms equal installed/latest codes resolve to `UpToDate`. No repeat prompt is reproducible with matching current metadata in source-level tests. The exact historical cause cannot be identified without the live `app_config/version_control` document; check its `latest_version_code`, `min_supported_version_code`, `enabled`, and package name against the installed APK.

## Installation and country detection

The User APK generates a persistent installation UUID and writes device, session, app-version, activity/heartbeat, FCM, and country fields to `installations/{ownerUid}_{installationId}` when Firebase Authentication is available. Country detection uses `api.country.is`, falls back to `ipapi.co/country`, and caches locally before writing `country_code`. The Admin APK has screens/repositories for installation records, device/country/version/activity details, and statistics. Whether this works against production depends on deployed rules and authentication; no live Firebase read/write was performed.

## Automated checks and APK metadata

- User `:app:testDebugUnitTest`: **34 tests, 0 failures, 0 errors, 0 skipped**.
- User `:app:assembleDebug`: **PASS**.
- User APK: `com.aistudio.prophecyverse.vykqtb`, version `3.0.0`, versionCode `5`, minSdk 24, targetSdk 36; non-empty, verified APK Signature Scheme v2.
- No JavaScript/TypeScript/TSX files exist in either supplied project; JSON blueprint validation passed.
- Existing compiler/deprecation/opt-in warnings remain; no compilation errors were reported.

This APK was signed with a sandbox-only Android debug key. The authorized release keystore and credentials were not supplied or found, and no replacement release key was generated. This debug APK cannot update an installed production APK signed by the authorized release identity. A production release build must be made with that original key.

## Live and manual validation still required

- Have the Firebase owner review/deploy a single least-privilege ruleset consistent with the `admin_roles` authorization model and the User APK's actual `users`, `installations`, and `notices` fields. The checked-in rules differ between projects; none were deployed in this task.
- Verify Admin role provisioning and actual permission-denied behavior in the production Firebase project.
- Publish a real notice and release configuration, then test the User listener and older/current APK update flows on devices. These live operations were not tested.
- Supply the original release keystore (expected alias `upload`) and its protected build credentials to produce APKs installable as updates to production installations. No private key or password belongs in the source ZIP or Git repository.
- Device/emulator install, user authentication, FCM delivery, IP country lookup, and real update installation were not performed in this sandbox.
