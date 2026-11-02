# ProphecyVerse Firebase data contract (source-level)

This document records the paths and fields observed in the supplied User and Admin APK source. It is not a live database export, a migration, or a claim that production Firestore rules have been deployed. No live Firebase credentials were used for this audit. Do not treat illustrative field names as evidence that a production document currently exists.

## Version publishing and update checks

**Document:** `app_config/version_control`

The Admin APK writes the metadata consumed by the User APK. The document uses numeric version fields and contains:

| Field | Type | Meaning |
|---|---|---|
| `enabled` | Boolean | Whether update checks are enabled. |
| `latest_version_code` | Number | Published Android `versionCode`. |
| `latest_version_name` | String | Human-readable release name. |
| `min_supported_version_code` | Number | Minimum supported Android `versionCode`. |
| `force_update` | Boolean | Makes an available newer build mandatory. |
| `package_name` | String | Must match the User APK application ID. |
| `downloads` | Map | Published HTTPS download sources keyed by source ID. |
| `update_sources` | Map | Compatibility mirror/fallback for download sources. |
| `release_notes` | Map | Optional localized notes, including `en` and `bn`. |
| `updated_at` | Firestore timestamp | Server-side publish time. |
| `updated_by` | String | Admin identity recorded by the publisher. |

Each download-source map may contain `enabled` (Boolean), `label` (String), `url` (String), `priority` (Number), and `isDirectApk` (Boolean). Admin publishing requires an enabled direct HTTPS APK URL; the User APK also supports other enabled HTTPS actions as external links. The Admin writes both `downloads` and `update_sources`; the User reads `downloads` first and falls back to `update_sources` if needed.

The current User APK compares numeric version codes. If the installed code equals or exceeds the latest and minimum codes, it is up to date. If an update is needed, the User APK validates metadata, package ID, download URL, downloaded APK package ID, and numeric version code. Android's native package installer remains responsible for signature continuity. Neither `artifact_sha256` nor `signing_certificate_sha256` is required or published as a release prerequisite; prior legacy values may be ignored/removed by the publisher.

No release values or APK URL are specified here: use only values actually published by an authorized Admin. Never copy sample values from an old document into production.

## Registered user profiles

**Document:** `users/{Firebase Authentication UID}`

The User APK's `AuthManager` writes the actual profile fields `uid`, `email` (when available), `is_anonymous`, `installation_id`, `last_login_at`, `app_version`, and `app_version_code`. For a newly created profile it also supplies the safe defaults `status=active` and `download_blocked=false`, as required by the supplied User rules. `last_login_at` is epoch milliseconds. `InstallationManager`/analytics may add or increment `total_watch_seconds`.

The Admin APK reads the document ID as the UID and tolerates optional fields. It displays only fields present in the document; `display_name` and `created_at` are not written by the current User APK. Admin moderation may write `status`, `suspension_reason`, `download_blocked`, `status_updated_at`, `status_updated_by`, and legacy compatibility mirrors where applicable.

## Installation and activity records

**Document:** `installations/{ownerUid}_{installationId}`

The User APK creates or merges the record after Firebase Authentication is available. Actual fields include `owner_uid`, `installation_id`, `session_id`, `session_start_at`, `device_manufacturer`, `device_model`, `device_brand`, `android_version`, `sdk_int`, `app_version_name`, `app_version_code`, `locale`, `country_code`, `installed_at`, `last_active_at`, `last_heartbeat_at`, `activity_status`, `fcm_status`, and optional `fcm_token`. Watch activity may add or increment `total_watch_seconds`.

`session_start_at`, `installed_at`, and `last_active_at` are epoch-millisecond numbers; `last_heartbeat_at` is a Firestore server timestamp. `InstallationManager` obtains a persistent installation UUID, refreshes heartbeat/activity fields, and updates app version fields. Country detection first calls `https://api.country.is/`, then `https://ipapi.co/country/`, caches the result locally, and writes the detected `country_code` when syncing installation data.

The Admin APK has installation list/detail/statistics readers and presents available device, country, app-version, activity, and date fields. It cannot read actual records unless the live rules authorize its Admin account.

## Notices

**Collection:** `notices/{noticeId}`

The Admin publisher writes `title`, `message`, `content` compatibility mirror, `severity`, `type` compatibility mirror, `active`, `published`, numeric `priority`, timestamps, and optional action/target fields. Optional action aliases are `actionUrl`/`action_url` and `actionLabel`/`action_label`; `targetVersion`/`target_version` may also be present. The User APK listens to documents with `published == true`, then excludes `active == false`; it displays `message` (falling back to `content`) and `severity` (falling back to `type`). Admin edits, publish/deactivate operations, and deletes are reflected by the listener when Firebase access is available.

## Firebase rules and deployment boundary

The two uploaded projects contain different Firestore rule files and role conventions (`admin_users` in the User project versus `admin_roles` in the Admin rules). The checked-in Admin rules do not currently include an `/installations` read match, while the User APK writes that collection. The Admin project's `FIRESTORE_RULES_PROPOSAL.md` documents scoped rule changes for review. No rules were deployed or relaxed. Before production, review and test one canonical least-privilege ruleset against the live project's Admin roles, user profile create/update writes, installations, and published notices; never grant public writes or embed service-account credentials in an APK.
