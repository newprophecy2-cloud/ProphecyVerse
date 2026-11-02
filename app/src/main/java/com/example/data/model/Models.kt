package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Installation record maintained by User APK's InstallationManager.kt
 * Collection: installations/{ownerUid_installationId} or installations/{installationId}
 */
@IgnoreExtraProperties
data class InstallationRecord(
    @DocumentId val id: String = "",
    @PropertyName("installation_id") val installationId: String = "",
    @PropertyName("owner_uid") val ownerUid: String = "",
    @PropertyName("session_id") val sessionId: String = "",
    @PropertyName("session_start_at") val sessionStartAt: Long = 0L,
    @PropertyName("device_manufacturer") val deviceManufacturer: String = "",
    @PropertyName("device_model") val deviceModel: String = "",
    @PropertyName("device_brand") val deviceBrand: String = "",
    @PropertyName("android_version") val androidVersion: String = "",
    @PropertyName("sdk_int") val sdkInt: Int = 0,
    @PropertyName("app_version_name") val appVersionName: String = "",
    @PropertyName("app_version_code") val appVersionCode: Int = 0,
    @PropertyName("locale") val locale: String = "",
    @PropertyName("country_code") val countryCode: String = "",
    @PropertyName("installed_at") val installedAt: Long = 0L,
    @PropertyName("last_active_at") val lastActiveAt: Long = 0L,
    @PropertyName("last_heartbeat_at") val lastHeartbeatAt: Timestamp? = null,
    @PropertyName("activity_status") val activityStatus: String = "active",
    @PropertyName("fcm_status") val fcmStatus: String = "pending",
    @PropertyName("fcm_token") val fcmToken: String = "",
    @PropertyName("total_watch_seconds") val totalWatchSeconds: Long = 0L
) {
    /**
     * Activity Status Logic:
     * - Active Now: last_active_at within last 5 minutes
     * - Recently Active: last_active_at within last 24 hours
     * - Inactive: last_active_at > 24 hours ago
     */
    val effectiveLastActiveMillis: Long
        get() = when {
            lastActiveAt > 0L -> lastActiveAt
            lastHeartbeatAt != null -> lastHeartbeatAt.toDate().time
            installedAt > 0L -> installedAt
            else -> 0L
        }

    val isActiveNow: Boolean
        get() = (System.currentTimeMillis() - effectiveLastActiveMillis) <= 5 * 60 * 1000L

    val isRecentlyActive: Boolean
        get() = (System.currentTimeMillis() - effectiveLastActiveMillis) <= 24 * 60 * 60 * 1000L

    val statusTag: String
        get() = when {
            effectiveLastActiveMillis == 0L -> "INACTIVE"
            isActiveNow -> "ACTIVE NOW"
            isRecentlyActive -> "RECENTLY ACTIVE"
            else -> "INACTIVE"
        }

    val formattedDevice: String
        get() = listOf(deviceManufacturer, deviceModel)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android Device" }

    val formattedInstalledDate: String
        get() = if (installedAt > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(installedAt))
        } else {
            "Unknown"
        }

    val formattedLastActiveDate: String
        get() = if (effectiveLastActiveMillis > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(effectiveLastActiveMillis))
        } else {
            "Never"
        }

    val formattedWatchTime: String
        get() {
            val hrs = totalWatchSeconds / 3600
            val mins = (totalWatchSeconds % 3600) / 60
            return if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
        }

    val isUserLinked: Boolean
        get() = ownerUid.isNotBlank() && !ownerUid.startsWith("guest_")
}

/**
 * User account model matching Prophecy Verse V3 User APK schema.
 * Written by AuthManager.kt & AppAnalytics.kt
 * Collection: users/{userId}
 */
@IgnoreExtraProperties
data class UserAccount(
    @DocumentId val uid: String = "",
    @PropertyName("email") val email: String = "",
    @PropertyName("display_name") val displayName: String = "",
    @PropertyName("status") val status: String = "active", // "active", "suspended"
    @PropertyName("suspension_reason") val suspensionReason: String = "",
    @PropertyName("download_blocked") val downloadBlocked: Boolean = false,
    @PropertyName("is_anonymous") val isAnonymous: Boolean = false,
    @PropertyName("installation_id") val installationId: String = "",
    @PropertyName("app_version") val appVersion: String = "",
    @PropertyName("app_version_code") val appVersionCode: Int = 0,
    @PropertyName("total_watch_seconds") val totalWatchSeconds: Long = 0L,
    @PropertyName("created_at") val createdAtRaw: Any? = null,
    @PropertyName("last_login_at") val lastLoginAtRaw: Any? = null,
    @PropertyName("status_updated_at") val statusUpdatedAt: Timestamp? = null,
    @PropertyName("status_updated_by") val statusUpdatedBy: String = ""
) {
    val isSuspended: Boolean
        get() = status.equals("suspended", ignoreCase = true)

    val isActive: Boolean
        get() = !isSuspended

    val createdMillis: Long
        get() = parseMillis(createdAtRaw)

    val lastLoginMillis: Long
        get() = parseMillis(lastLoginAtRaw)

    val formattedWatchTime: String
        get() {
            val hrs = totalWatchSeconds / 3600
            val mins = (totalWatchSeconds % 3600) / 60
            return if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
        }

    val formattedCreatedAt: String
        get() = if (createdMillis > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(createdMillis))
        } else {
            "Unknown"
        }

    val formattedLastLogin: String
        get() = if (lastLoginMillis > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastLoginMillis))
        } else {
            "Never"
        }

    private fun parseMillis(raw: Any?): Long = when (raw) {
        is Long -> raw
        is Number -> raw.toLong()
        is Timestamp -> raw.toDate().time
        else -> 0L
    }
}

/**
 * Notice model matching User APK NoticeManager.kt & FIRESTORE_SCHEMA.md
 * Collection: notices/{noticeId}
 */
@IgnoreExtraProperties
data class NoticeItem(
    @DocumentId val id: String = "",
    @PropertyName("title") val title: String = "",
    @PropertyName("message") val message: String = "",
    @PropertyName("content") val content: String = "", // backwards-compat fallback
    @PropertyName("severity") val severity: String = "info", // "info", "warning", "critical"
    @PropertyName("type") val type: String = "info",
    @PropertyName("active") val active: Boolean = true,
    @PropertyName("published") val published: Boolean = true,
    @PropertyName("priority") val priority: Long = 0L,
    @PropertyName("actionUrl") val actionUrl: String? = null,
    @PropertyName("actionLabel") val actionLabel: String? = null,
    @PropertyName("targetVersion") val targetVersion: String? = null,
    @PropertyName("created_by") val createdBy: String = "",
    @ServerTimestamp @PropertyName("created_at") val createdAt: Timestamp? = null,
    @ServerTimestamp @PropertyName("updated_at") val updatedAt: Timestamp? = null
) {
    val displayContent: String
        get() = message.ifBlank { content }

    val isEffectivelyActive: Boolean
        get() = active && published

    val effectiveSeverity: String
        get() = severity.ifBlank { type }
}

/**
 * Version control configuration model matching User APK's VersionControlManager.kt
 * Collection: app_config/version_control
 */
@IgnoreExtraProperties
data class VersionConfig(
    @DocumentId val id: String = "version_control",
    @PropertyName("enabled") val enabled: Boolean = true,
    @PropertyName("force_update") val forceUpdate: Boolean = false,
    @PropertyName("latest_version_code") val latestVersionCode: Long = 5L,
    @PropertyName("latest_version_name") val latestVersionName: String = "3.0.0",
    @PropertyName("min_supported_version_code") val minSupportedVersionCode: Long = 5L,
    @PropertyName("package_name") val packageName: String = "com.aistudio.prophecyverse.vykqtb",
    @PropertyName("artifact_sha256") val artifactSha256: String = "",
    @PropertyName("signing_certificate_sha256") val signingCertificateSha256: String = "",
    @PropertyName("release_notes") val releaseNotes: Map<String, String> = mapOf(
        "en" to "Prophecy Verse V3 with modern streaming, download manager, and enhanced player UI.",
        "bn" to "প্রফেসি ভার্স V3 আধুনিক স্ট্রিমিং ও উন্নত প্লেয়ার ইন্টারফেস।"
    ),
    @PropertyName("update_sources") val updateSources: Map<String, Any> = emptyMap(),
    @PropertyName("downloads") val downloads: Map<String, Any> = emptyMap(),
    @ServerTimestamp @PropertyName("updated_at") val updatedAt: Timestamp? = null,
    @PropertyName("updated_by") val updatedBy: String = ""
)

/**
 * Push notification model for VPS backend HTTP v1 dispatch
 * Collection: notifications/{notificationId}
 */
@IgnoreExtraProperties
data class PushNotification(
    @DocumentId val id: String = "",
    @PropertyName("event_type") val eventType: String = "general",
    @PropertyName("target") val target: String = "/topics/all",
    @PropertyName("title") val title: String = "",
    @PropertyName("body") val body: String = "",
    @PropertyName("deep_link") val deepLink: String = "",
    @PropertyName("image_url") val imageUrl: String = "",
    @PropertyName("status") val status: String = "queued", // "queued", "sent", "failed"
    @PropertyName("created_by") val createdBy: String = "",
    @ServerTimestamp @PropertyName("created_at") val createdAt: Timestamp? = null,
    @ServerTimestamp @PropertyName("sent_at") val sentAt: Timestamp? = null
)

/**
 * Audit log matching both security_logs/{id} and admin_logs/{id}
 */
@IgnoreExtraProperties
data class AdminActivityLog(
    @DocumentId val id: String = "",
    @PropertyName("action") val action: String = "",
    @PropertyName("details") val details: String = "",
    @PropertyName("performed_by") val performedBy: String = "",
    @PropertyName("performed_by_uid") val performedByUid: String = "",
    @PropertyName("adminUid") val adminUid: String = "",
    @PropertyName("adminEmail") val adminEmail: String = "",
    @PropertyName("targetType") val targetType: String = "",
    @PropertyName("targetId") val targetId: String = "",
    @PropertyName("result") val result: String = "SUCCESS",
    @ServerTimestamp @PropertyName("timestamp") val timestamp: Timestamp? = null
)

/**
 * Admin user record matching admin_users/{uid} (and admin_roles/{uid})
 */
@IgnoreExtraProperties
data class AdminRoleRecord(
    @DocumentId val uid: String = "",
    @PropertyName("is_admin") val isAdmin: Boolean = true,
    @PropertyName("role") val role: String = "ADMIN", // SUPER_ADMIN, ADMIN, MODERATOR
    @PropertyName("status") val status: String = "ACTIVE",
    @PropertyName("email") val email: String = "",
    @PropertyName("permissions") val permissions: List<String> = listOf(
        "installations:read", "installations:delete", "users:read", "users:moderate",
        "notices:write", "version:write", "notifications:send", "logs:read"
    ),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Watch history entry from watch_history/{uid}/episodes/{historyId}
 */
@IgnoreExtraProperties
data class WatchHistoryEntry(
    @DocumentId val id: String = "",
    @PropertyName("anime_id") val animeId: String = "",
    @PropertyName("episode_id") val episodeId: String = "",
    @PropertyName("season_number") val seasonNumber: Int = 1,
    @PropertyName("episode_number") val episodeNumber: Int = 1,
    @PropertyName("episode_title") val episodeTitle: String = "",
    @PropertyName("position_ms") val positionMs: Long = 0L,
    @PropertyName("duration_ms") val durationMs: Long = 0L,
    @PropertyName("completed") val completed: Boolean = false,
    @PropertyName("last_played_at") val lastPlayedAt: Long = 0L,
    @PropertyName("owner_key") val ownerKey: String = ""
)

/**
 * Aggregated analytics calculated from real Firestore records
 */
data class RealAnalyticsOverview(
    val totalInstallations: Int = 0,
    val activeNowInstallations: Int = 0,
    val recentlyActiveInstallations: Int = 0,
    val inactiveInstallations: Int = 0,
    val totalUsers: Int = 0,
    val activeUsers: Int = 0,
    val suspendedUsers: Int = 0,
    val totalWatchHours: Double = 0.0,
    val versionDistribution: Map<String, Int> = emptyMap(),
    val countryDistribution: Map<String, Int> = emptyMap(),
    val manufacturerDistribution: Map<String, Int> = emptyMap(),
    val topWatchedAnime: List<Pair<String, Long>> = emptyList()
)
