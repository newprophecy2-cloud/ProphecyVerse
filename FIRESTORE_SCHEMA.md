# Prophecy Verse V3 — Complete Firestore Schema Specification

This document provides the definitive data model, schema definition, document structures, validation requirements, and security rules for the Prophecy Verse V3 ecosystem.

---

## 1. Summary of Collections

| Collection Name | Document ID | Access Level | Description |
|---|---|---|---|
| `app_config` | `version_control` | Public Read / Admin Write | App versioning, force update status, download channels, release notes |
| `update_history` | Auto-generated UUID | Admin Read & Write | Audit history of past published app updates and releases |
| `users` | User UID (`request.auth.uid`) | User Owner Read/Write, Admin Full | User profile, account status (`active`/`suspended`), download permissions |
| `admin_users` | Admin User UID | Authenticated Read, Admin Write | List of authorized administrative operators |
| `installations` | Device Installation ID or `uid_deviceId` | Public Create/Update, Admin Full | App installation telemetry, device hardware, active session, FCM token |
| `notices` | Auto-generated ID | Public Read, Admin Write | Global and targeted in-app floating/banner announcements |
| `notifications` | Auto-generated ID | Public Read, Admin Write | FCM push notification queue for VPS backend HTTP v1 dispatch |
| `security_logs` | Auto-generated ID | Authenticated Create, Admin Read | Immutable audit trail for admin actions and authentication events |
| `watch_history` | User UID | Owner Read/Write, Admin Read | Playback progress, completed episodes, continue watching state |
| `watchlist` | User UID | Owner Read/Write, Admin Read | Bookmarked and favorite anime series |
| `analytics_events` | Auto-generated ID | Client Create, Admin Read | Installation, session start, and playback event telemetry |

---

## 2. Detailed Collection Schemas

### 2.1. `app_config/version_control`
Single document governing application rollout and mandatory update gating across all clients.

```json
{
  "latest_version_code": 5,
  "latest_version_name": "3.0.0",
  "min_supported_version_code": 5,
  "force_update": true,
  "artifact_sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "enabled": true,
  "update_sources": {
    "telegram": {
      "url": "https://t.me/prophecyverse",
      "enabled": true,
      "priority": 1,
      "label": "Telegram Channel"
    },
    "website": {
      "url": "https://prophecyverse.tech/download",
      "enabled": true,
      "priority": 2,
      "label": "Official Website"
    },
    "backup": {
      "url": "https://cdn.prophecyverse.tech/apk/prophecyverse-v3.0.0.apk",
      "enabled": true,
      "priority": 3,
      "label": "Direct CDN Server"
    }
  },
  "release_notes": {
    "en": "Prophecy Verse V3 Modernization release with high-speed streaming, download manager, and enhanced player UI.",
    "bn": "প্রফেসি ভার্স ভার্সন ৩ আপডেট! নতুন প্লেয়ার, উন্নত ডাউনলোড ম্যানেজার এবং দ্রুত স্ট্রিমিং।"
  },
  "updated_at": "Timestamp",
  "package_name": "com.aistudio.prophecyverse.vykqtb",
  "signing_certificate_sha256": "<release certificate SHA-256>",
  "updated_by": "admin@prophecyverse.tech"
}
```

#### Field Specifications:
- `latest_version_code` (`number`): The highest available build code.
- `latest_version_name` (`string`): Human-readable version string shown in update dialogs.
- `min_supported_version_code` (`number`): The absolute threshold below which apps are permanently blocked.
- `force_update` (`boolean`): If `true`, any client where `app_version_code < latest_version_code` is treated as a Mandatory Update.
- `artifact_sha256` (`string`): 64-character hexadecimal SHA-256 hash of the published APK file for verification prior to package installer launch.
- `update_sources` (`map`): Map of download sources ordered by priority (`telegram`, `website`, `backup`).

---

### 2.2. `users/{userId}`
User profile records for registered accounts.

```json
{
  "email": "otaku@example.com",
  "display_name": "Subaru",
  "status": "active",
  "download_blocked": false,
  "created_at": "Timestamp",
  "last_login_at": "Timestamp",
  "status_updated_at": "Timestamp",
  "status_updated_by": "admin@prophecyverse.tech"
}
```

#### Field Specifications:
- `status` (`string`): Either `"active"` or `"suspended"`.
  - When `"suspended"`, client downloads are blocked, and account settings display a prominent suspended warning badge.
- `download_blocked` (`boolean`): Explicit flag to revoke download capabilities while retaining streaming access.

---

### 2.3. `installations/{installationId}`
Maintained by `InstallationManager.kt` on Android clients.

```json
{
  "installation_id": "848e10d2-990a-4228-b0a5-f8511bfcb31e",
  "session_id": "902bd3f4-3ab2-4b21-8ec7-5291da93f019",
  "session_start_at": "Timestamp",
  "device_manufacturer": "Google",
  "device_model": "Pixel 8 Pro",
  "os_api_level": 34,
  "app_version_name": "3.0.0",
  "app_version_code": 5,
  "fcm_token": "eXamPleFcmToKen123...",
  "fcm_token_status": "synced",
  "country_code": "BD",
  "owner_uid": "FirebaseUserUID_or_null",
  "installed_at": "Timestamp",
  "last_active_at": "Timestamp",
  "total_watch_seconds": 3600
}
```

#### Activity Status Logic:
- `ACTIVE NOW`: `last_active_at` within last 2 minutes.
- `RECENTLY ACTIVE`: `last_active_at` within last 24 hours.
- `INACTIVE`: `last_active_at` > 24 hours.

---

### 2.4. `notifications/{notificationId}`
Queued push notification requests processed by the VPS backend.

```json
{
  "event_type": "apk_update",
  "target": "/topics/all",
  "title": "Prophecy Verse V3 is Here!",
  "body": "Download the latest version with modern UI and high-speed anime streaming.",
  "deep_link": "prophecyverse://update",
  "image_url": "https://prophecyverse.tech/banner.png",
  "status": "queued",
  "created_by": "admin@prophecyverse.tech",
  "created_at": "Timestamp",
  "sent_at": "Timestamp"
}
```

#### Event Types Supported:
1. `apk_update` (Triggers in-app update check)
2. `mandatory_update` (Forces immediate lock screen)
3. `new_episode` (Direct route to anime episode player)
4. `system_notice` (In-app dialog announcement)
5. `account_status` (Triggers user status refresh)
6. `maintenance` (Displays scheduled downtime message)
7. `stream_provider_alert` (Notifies user of provider status changes)
8. `download_ready` (Opens download manager)
9. `security_alert` (Security warning or required action)
10. `general` (Standard broadcast)

---

### 2.5. `notices/{noticeId}`
Floating or banner in-app announcements managed by an authorized external backend process.

```json
{
  "title": "Server Maintenance Notice",
  "message": "Streaming provider 2 is undergoing scheduled maintenance between 2:00 AM - 4:00 AM UTC.",
  "severity": "warning",
  "active": true,
  "created_by": "admin@prophecyverse.tech",
  "created_at": "Timestamp"
}
```

---

### 2.6. `security_logs/{logId}`
Append-only tamper-proof audit trail for administrative operations.

```json
{
  "action": "VERSION_UPDATED",
  "details": "Updated app version to 3.0.0 (5), min=5, force=true",
  "performed_by": "admin@prophecyverse.tech",
  "performed_by_uid": "AdminUID123",
  "timestamp": "Timestamp"
}
```

## Android client safety

The Android client reads this document but never creates or overwrites it. Missing, disabled, malformed, or unreachable version configuration produces a retry-only verification error; it is never treated as up to date. Privileged fields such as `status`, `download_blocked`, and administration records are server/admin controlled.
