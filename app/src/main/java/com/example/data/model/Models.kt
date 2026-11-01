package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

/**
 * User account model matching existing Prophecy Verse V3 Firestore schema.
 * Collection: users/{uid}
 */
@IgnoreExtraProperties
data class UserAccount(
    @DocumentId val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val status: String = "ACTIVE", // ACTIVE, SUSPENDED, PENDING_REVIEW
    val isDownloadBlocked: Boolean = false,
    val role: String = "USER", // USER, ADMIN, MODERATOR
    val clientVersion: String = "",
    val platform: String = "Android",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val lastLoginAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Notice model matching existing Prophecy Verse V3 User APK notice schema.
 * Collection: notices/{noticeId}
 */
@IgnoreExtraProperties
data class NoticeItem(
    @DocumentId val id: String = "",
    val title: String = "",
    val content: String = "",
    val type: String = "info", // "info", "warning", "alert", "maintenance"
    val published: Boolean = true,
    val priority: Long = 0L,
    val actionUrl: String? = null,
    val actionLabel: String? = null,
    val targetVersion: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Version control configuration model matching Prophecy Verse V3 update checker.
 * Collection: app_config/version or version_config/latest
 */
@IgnoreExtraProperties
data class VersionConfig(
    @DocumentId val id: String = "latest",
    val versionName: String = "3.0.0",
    val versionCode: Long = 300L,
    val minSupportedVersionCode: Long = 200L,
    val isMandatory: Boolean = false,
    val updateUrl: String = "https://github.com/prophecyverse/releases",
    val updateMessage: String = "A new update of Prophecy Verse is available with new features and performance enhancements.",
    val sha256: String = "",
    val updateSources: List<String> = listOf("DIRECT", "GITHUB"),
    val downloads: List<String> = emptyList(), // Parsed legacy field compatibility
    @ServerTimestamp val releaseDate: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Tamper-resistant administrative activity log.
 * Collection: admin_logs/{logId}
 */
@IgnoreExtraProperties
data class AdminActivityLog(
    @DocumentId val id: String = "",
    val adminUid: String = "",
    val adminEmail: String = "",
    val action: String = "", // NOTICE_CREATED, NOTICE_EDITED, VERSION_UPDATED, USER_MODERATED
    val targetType: String = "", // NOTICE, USER, VERSION, BACKEND
    val targetId: String = "",
    val details: String = "",
    val result: String = "SUCCESS", // SUCCESS, FAILURE
    @ServerTimestamp val timestamp: Timestamp? = null
)

/**
 * Admin role definition from admin_roles/{uid}
 */
@IgnoreExtraProperties
data class AdminRoleRecord(
    @DocumentId val uid: String = "",
    val role: String = "ADMIN", // SUPER_ADMIN, ADMIN, MODERATOR
    val status: String = "ACTIVE", // ACTIVE, SUSPENDED
    val email: String = "",
    val permissions: List<String> = listOf("users:read", "users:moderate", "notices:write", "version:write", "logs:read"),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
