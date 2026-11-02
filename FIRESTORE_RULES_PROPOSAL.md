# Firestore rules proposal — review before production deployment

This is a **proposal only**. It has not been applied to Firebase. The checked-in rules currently have no match blocks for `installations`, `analytics_events`, or `watch_history`, which means access is denied by default. In addition, the current `/users/{userId}` create allowlist does not include several fields written by the current User APK (`is_anonymous`, `installation_id`, `last_login_at`, `app_version`, and `app_version_code`). Those rules can block registration/tracking even though the application catches and logs the failed writes.

Merge the following scoped changes into the existing `firestore.rules`; do not replace the existing Admin authentication helpers or rules. Review the exact fields against the live project's current schema and test in the Firebase Emulator Suite before production rollout.

## 1. Replace the current `/users/{userId}` match block

```firestore
match /users/{userId} {
  allow read: if isOwner(userId) || isModerator();

  // The current User APK creates/merges only its own account metadata.
  // Do not allow self-service writes to moderation or Admin privilege fields.
  allow create: if isOwner(userId)
    && request.resource.data.get("uid", userId) == userId
    && request.resource.data.get("status", "active") == "active"
    && request.resource.data.get("download_blocked", false) == false
    && request.resource.data.keys().hasOnly([
      "uid", "email", "is_anonymous", "installation_id",
      "last_login_at", "app_version", "app_version_code",
      "status", "download_blocked", "created_at", "total_watch_seconds"
    ]);

  allow update: if isAdmin()
    || (isModerator() && request.resource.data.diff(resource.data).affectedKeys()
      .hasOnly(["status", "suspension_reason", "download_blocked", "status_updated_at", "status_updated_by"]))
    || (isOwner(userId) && request.resource.data.diff(resource.data).affectedKeys()
      .hasOnly([
        "email", "is_anonymous", "installation_id", "last_login_at",
        "app_version", "app_version_code", "total_watch_seconds"
      ]));

  allow delete: if isAdmin();
}
```

## 2. Add owner-scoped installation and analytics access

```firestore
match /installations/{installationDocId} {
  // Admin screens query the whole collection; owner reads are limited to their own records.
  allow read: if isModerator()
    || (isAuthenticated() && resource.data.owner_uid == request.auth.uid);

  // InstallationManager uses document IDs `{uid}_{installationId}` and writes owner_uid.
  allow create, update: if isAuthenticated()
    && request.resource.data.owner_uid == request.auth.uid
    && request.resource.data.keys().hasOnly([
      "owner_uid", "installation_id", "session_id", "session_start_at",
      "device_manufacturer", "device_model", "device_brand", "android_version",
      "sdk_int", "app_version_name", "app_version_code", "locale", "country_code",
      "installed_at", "last_active_at", "last_heartbeat_at", "activity_status",
      "fcm_status", "fcm_token", "total_watch_seconds"
    ]);

  allow delete: if isModerator()
    || (isAuthenticated() && resource.data.owner_uid == request.auth.uid);
}

match /analytics_events/{eventId} {
  allow read: if isModerator();
  allow create: if isAuthenticated()
    && request.resource.data.user_id == request.auth.uid
    && request.resource.data.keys().hasOnly([
      "event_name", "installation_id", "user_id", "timestamp", "params"
    ]);
  allow update, delete: if false;
}
```

## 3. Add scoped watch-history access used by Admin details and the User APK

```firestore
match /watch_history/{userId}/episodes/{episodeId} {
  allow read: if isOwner(userId) || isModerator();
  allow create, update, delete: if isOwner(userId) || isAdmin();
}
```

The `notices` rule already permits published notices to be read and Admin-authorized writes. However, inspection of the current `apk-V3` source shows that its `NoticeManager` does not read Firestore notices at all; deploying notice rules alone will **not** make Admin-published notices appear in that User APK. A separate User APK source change is needed for that feature.

## Deployment cautions

These rules intentionally do not grant public writes. The app's client-side analytics and installation fields are still user-supplied and must not be treated as tamper-proof security or billing data. Confirm all data fields with the production Firebase project and test owner, Admin, moderator, unauthenticated, and anonymous-user cases in an emulator before publishing. No service-account key or production database access was used for this review.
