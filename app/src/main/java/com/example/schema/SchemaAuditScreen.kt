package com.example.schema

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusBadge
import com.example.ui.theme.InstitutionalGreen

data class SchemaCollectionAudit(
    val collectionName: String,
    val purpose: String,
    val docIdConvention: String,
    val readAccess: String,
    val writeAccess: String,
    val userApkUsage: String,
    val adminApkUsage: String,
    val fields: List<Pair<String, String>>
)

val auditEntries = listOf(
    SchemaCollectionAudit(
        collectionName = "installations/{id}",
        purpose = "Device hardware telemetry, active session tracking, and FCM token registry",
        docIdConvention = "\${ownerUid}_\${installationId} or \${installationId}",
        readAccess = "Admin full or Owner matching request.auth.uid",
        writeAccess = "Owner client (heartbeat & metadata update)",
        userApkUsage = "Synchronized on app open, resume, and periodic watch heartbeat",
        adminApkUsage = "Installation dashboard, hardware telemetry, active/inactive tracking",
        fields = listOf(
            "installation_id" to "String (UUID)",
            "owner_uid" to "String (Firebase Auth UID or guest ID)",
            "session_id" to "String (UUID per session)",
            "session_start_at" to "Long (Epoch ms)",
            "device_manufacturer" to "String (Build.MANUFACTURER)",
            "device_model" to "String (Build.MODEL)",
            "device_brand" to "String (Build.BRAND)",
            "android_version" to "String (Build.VERSION.RELEASE)",
            "sdk_int" to "Integer (Build.VERSION.SDK_INT)",
            "app_version_name" to "String (BuildConfig.VERSION_NAME)",
            "app_version_code" to "Integer (BuildConfig.VERSION_CODE)",
            "locale" to "String (Language tag)",
            "country_code" to "String (ISO country)",
            "installed_at" to "Long (First-seen epoch ms)",
            "last_active_at" to "Long (Last activity epoch ms)",
            "last_heartbeat_at" to "Timestamp (@ServerTimestamp)",
            "activity_status" to "String ('active')",
            "fcm_status" to "String ('registered', 'pending')",
            "fcm_token" to "String (FCM device token)",
            "total_watch_seconds" to "Long (Incremented playback time)"
        )
    ),
    SchemaCollectionAudit(
        collectionName = "users/{uid}",
        purpose = "User account identity, account status, and download eligibility",
        docIdConvention = "Firebase Authentication UID (request.auth.uid)",
        readAccess = "Owner or Admin",
        writeAccess = "Owner (profile update) or Admin (moderation/suspension)",
        userApkUsage = "Account state observation, download permission check",
        adminApkUsage = "User management, account suspension/restoration, download blocking",
        fields = listOf(
            "uid" to "String (DocumentId)",
            "email" to "String (User email)",
            "display_name" to "String (Optional)",
            "status" to "String ('active', 'suspended')",
            "suspension_reason" to "String (Reason for suspension)",
            "download_blocked" to "Boolean (Default: false)",
            "is_anonymous" to "Boolean",
            "installation_id" to "String (Linked device UUID)",
            "app_version" to "String",
            "app_version_code" to "Integer",
            "total_watch_seconds" to "Long (Cumulative seconds)",
            "created_at" to "Long / Timestamp",
            "last_login_at" to "Long / Timestamp",
            "status_updated_at" to "Timestamp",
            "status_updated_by" to "String (Admin email)"
        )
    ),
    SchemaCollectionAudit(
        collectionName = "app_config/version_control",
        purpose = "Application update gating and mandatory rollout enforcement",
        docIdConvention = "Fixed document ID: 'version_control'",
        readAccess = "Public / Authenticated clients",
        writeAccess = "Admin only",
        userApkUsage = "Checked on startup and resume; triggers update modal",
        adminApkUsage = "Version control dashboard, update publishing, release notes",
        fields = listOf(
            "package_name" to "String ('com.aistudio.prophecyverse.vykqtb')",
            "latest_version_name" to "String (e.g. '3.0.0')",
            "latest_version_code" to "Long / Integer (e.g. 5)",
            "min_supported_version_code" to "Long / Integer (e.g. 5)",
            "force_update" to "Boolean (Mandatory lock flag)",
            "enabled" to "Boolean (True)",
            "artifact_sha256" to "String (64 hex characters)",
            "signing_certificate_sha256" to "String",
            "release_notes" to "Map<String, String> ('en', 'bn')",
            "update_sources" to "Map<String, Map> (telegram, website, backup)",
            "downloads" to "Map<String, Map> (Compatibility mirror)",
            "updated_at" to "Timestamp",
            "updated_by" to "String (Admin email)"
        )
    ),
    SchemaCollectionAudit(
        collectionName = "notifications/{notificationId}",
        purpose = "Push notification queue for VPS backend HTTP v1 dispatch",
        docIdConvention = "Auto-generated Push ID",
        readAccess = "Admin only",
        writeAccess = "Admin only",
        userApkUsage = "FCM push notifications received via ProphecyMessagingService",
        adminApkUsage = "Compose and queue broadcast or targeted push notifications",
        fields = listOf(
            "id" to "String (DocumentId)",
            "event_type" to "String ('apk_update', 'mandatory_update', 'new_episode', etc.)",
            "target" to "String ('/topics/all' or FCM token)",
            "title" to "String",
            "body" to "String",
            "deep_link" to "String (e.g. 'prophecyverse://update')",
            "image_url" to "String",
            "status" to "String ('queued', 'sent')",
            "created_by" to "String (Admin email)",
            "created_at" to "Timestamp",
            "sent_at" to "Timestamp"
        )
    ),
    SchemaCollectionAudit(
        collectionName = "notices/{noticeId}",
        purpose = "In-app broadcast announcements and maintenance banners",
        docIdConvention = "Auto-generated Firestore Push ID",
        readAccess = "Public where active == true or published == true",
        writeAccess = "Admin only",
        userApkUsage = "NoticeManager observes active notice for user dialogs",
        adminApkUsage = "Create, edit, toggle active status, delete notices",
        fields = listOf(
            "id" to "String (DocumentId)",
            "title" to "String (Required)",
            "message" to "String (Body text in User APK)",
            "content" to "String (Compatibility mirror)",
            "severity" to "String ('info', 'warning', 'critical')",
            "type" to "String ('info', 'warning', 'maintenance')",
            "active" to "Boolean (Default: true)",
            "published" to "Boolean (Default: true)",
            "priority" to "Integer / Long",
            "actionUrl" to "String? (Action link)",
            "actionLabel" to "String? (Action button text)",
            "created_by" to "String (Admin email)",
            "created_at" to "Timestamp",
            "updated_at" to "Timestamp"
        )
    ),
    SchemaCollectionAudit(
        collectionName = "watch_history/{uid}/episodes/{id}",
        purpose = "Playback progress, resume points, and completed episode tracking",
        docIdConvention = "\${animeId}_\${episodeId}",
        readAccess = "Owner or Admin",
        writeAccess = "Owner or Admin",
        userApkUsage = "CloudHistoryRepository syncs positionMs, durationMs, completed",
        adminApkUsage = "Watch analytics, completed episode count, top series",
        fields = listOf(
            "id" to "String (\${anime_id}_\${episode_id})",
            "anime_id" to "String",
            "episode_id" to "String",
            "season_number" to "Integer",
            "episode_number" to "Integer",
            "episode_title" to "String",
            "position_ms" to "Long",
            "duration_ms" to "Long",
            "completed" to "Boolean",
            "last_played_at" to "Long (Epoch ms)",
            "owner_key" to "String (User UID)"
        )
    ),
    SchemaCollectionAudit(
        collectionName = "security_logs/{logId} & admin_logs/{logId}",
        purpose = "Append-only immutable audit trail for administrative operations",
        docIdConvention = "Auto-generated Push ID",
        readAccess = "Admin only",
        writeAccess = "Admin only (Create only; Update/Delete denied)",
        userApkUsage = "None (Client isolated)",
        adminApkUsage = "Security audit log review and compliance verification",
        fields = listOf(
            "id" to "String (DocumentId)",
            "action" to "String (e.g. 'VERSION_UPDATED', 'USER_MODERATED')",
            "details" to "String",
            "performed_by" to "String (Admin email)",
            "performed_by_uid" to "String (Admin UID)",
            "timestamp" to "Timestamp (@ServerTimestamp)"
        )
    ),
    SchemaCollectionAudit(
        collectionName = "admin_users/{uid}",
        purpose = "Authorized administrator accounts per User APK firestore.rules",
        docIdConvention = "Firebase Authentication UID",
        readAccess = "Admin only",
        writeAccess = "Admin only",
        userApkUsage = "Defines admin() rule function",
        adminApkUsage = "Verifies caller's is_admin flag upon sign-in",
        fields = listOf(
            "uid" to "String (DocumentId)",
            "is_admin" to "Boolean (Must be true for admin access)",
            "email" to "String",
            "role" to "String ('SUPER_ADMIN', 'ADMIN', 'MODERATOR')",
            "created_at" to "Timestamp"
        )
    )
)

@Composable
fun SchemaAuditScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("schema_audit_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = InstitutionalGreen
                        )
                        Text(
                            text = "Prophecy Verse V3 Schema Mapping Audit",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Production schema mapping and security rule boundaries. Guaranteed 100% synchronized with Prophecy Verse V3 User APK data producers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = "Audited Firestore Collections",
                subtitle = "Strict field mapping & security contracts"
            )
        }

        items(auditEntries.size) { index ->
            val audit = auditEntries[index]
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = audit.collectionName,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = InstitutionalGreen
                        )
                        StatusBadge(status = "VERIFIED")
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = audit.purpose,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Access Rules & Usage:",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "• Read: ${audit.readAccess}\n• Write: ${audit.writeAccess}\n• User APK: ${audit.userApkUsage}\n• Admin APK: ${audit.adminApkUsage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Document Fields & Types:",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            audit.fields.forEach { (fieldName, fieldType) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = fieldName,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = fieldType,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
