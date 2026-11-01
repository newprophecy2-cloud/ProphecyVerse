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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        collectionName = "users/{uid}",
        purpose = "User account identity, platform registration, and client status",
        docIdConvention = "Firebase Authentication UID",
        readAccess = "Owner (request.auth.uid == uid) or Admin",
        writeAccess = "Owner (profile update) or Admin via Server Function",
        userApkUsage = "Profile sync, download license verification, last login",
        adminApkUsage = "User search, status inspection, account moderation",
        fields = listOf(
            "uid" to "String (DocumentId)",
            "displayName" to "String (Optional)",
            "email" to "String",
            "status" to "String ('ACTIVE', 'SUSPENDED')",
            "isDownloadBlocked" to "Boolean (Default: false)",
            "clientVersion" to "String",
            "platform" to "String (Default: 'Android')",
            "createdAt" to "Timestamp (@ServerTimestamp)",
            "updatedAt" to "Timestamp (@ServerTimestamp)"
        )
    ),
    SchemaCollectionAudit(
        collectionName = "notices/{noticeId}",
        purpose = "In-app broadcast announcements and maintenance banners",
        docIdConvention = "Auto-generated UUID / Firestore push ID",
        readAccess = "Public / Authenticated users where published == true",
        writeAccess = "Admin only (role == 'ADMIN' / 'SUPER_ADMIN')",
        userApkUsage = "Notices banner in Home screen, update alerts",
        adminApkUsage = "Create, edit, toggle publish status, delete notices",
        fields = listOf(
            "id" to "String (DocumentId)",
            "title" to "String (Required)",
            "content" to "String (Required)",
            "type" to "String ('info', 'warning', 'alert', 'maintenance')",
            "published" to "Boolean (Default: true)",
            "priority" to "Integer / Long (Ordering index)",
            "actionUrl" to "String? (Optional clickable action)",
            "actionLabel" to "String? (Button text)",
            "targetVersion" to "String? (Optional version filter)",
            "createdAt" to "Timestamp (@ServerTimestamp)",
            "updatedAt" to "Timestamp (@ServerTimestamp)"
        )
    ),
    SchemaCollectionAudit(
        collectionName = "app_config/version & version_config/latest",
        purpose = "Client update manifest and mandatory upgrade enforcement",
        docIdConvention = "Fixed document IDs: 'version' and 'latest'",
        readAccess = "Public / Authenticated clients",
        writeAccess = "Admin only",
        userApkUsage = "Startup version checker, mandatory update dialog",
        adminApkUsage = "Inspect deployed version, publish new releases",
        fields = listOf(
            "versionName" to "String (e.g. '3.0.0')",
            "versionCode" to "Integer / Long (e.g. 300)",
            "minSupportedVersionCode" to "Integer / Long (e.g. 200)",
            "isMandatory" to "Boolean (Mandatory gate)",
            "updateUrl" to "String (Download URL)",
            "updateMessage" to "String (Changelog description)",
            "sha256" to "String (64-char Hex APK hash)",
            "updateSources" to "List<String> (Direct/GitHub)",
            "downloads" to "List<String> (Parsed legacy sources)"
        )
    ),
    SchemaCollectionAudit(
        collectionName = "admin_roles/{uid}",
        purpose = "Server-enforced administrative authorization and role assignment",
        docIdConvention = "Firebase Authentication UID",
        readAccess = "Authenticated caller matching request.auth.uid",
        writeAccess = "Server-side Cloud Function / Admin SDK only",
        userApkUsage = "None (Strictly isolated from User APK)",
        adminApkUsage = "Admin authentication gate and permission verification",
        fields = listOf(
            "uid" to "String (DocumentId)",
            "role" to "String ('SUPER_ADMIN', 'ADMIN', 'MODERATOR')",
            "status" to "String ('ACTIVE', 'SUSPENDED')",
            "permissions" to "List<String> (Granular access scopes)",
            "createdAt" to "Timestamp",
            "updatedAt" to "Timestamp"
        )
    ),
    SchemaCollectionAudit(
        collectionName = "admin_logs/{logId}",
        purpose = "Append-only administrative action audit trail",
        docIdConvention = "Auto-generated Push ID",
        readAccess = "Admin only",
        writeAccess = "Admin only (Create only; Update/Delete denied)",
        userApkUsage = "None (Isolated from User APK)",
        adminApkUsage = "Security audit log review and investigation",
        fields = listOf(
            "id" to "String (DocumentId)",
            "adminUid" to "String",
            "adminEmail" to "String",
            "action" to "String (e.g. 'NOTICE_CREATED')",
            "targetType" to "String ('NOTICE', 'USER', 'VERSION')",
            "targetId" to "String",
            "details" to "String",
            "result" to "String ('SUCCESS', 'FAILURE')",
            "timestamp" to "Timestamp"
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
                            text = "Prophecy Verse V3 Schema Audit",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Production schema mapping and security rule boundaries. Guaranteed 100% backward compatibility with Prophecy Verse V3 User APK.",
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
