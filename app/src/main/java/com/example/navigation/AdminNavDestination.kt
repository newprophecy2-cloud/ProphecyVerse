package com.example.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schema
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.ui.graphics.vector.ImageVector

sealed class AdminNavDestination(
    val route: String,
    val title: String,
    val icon: ImageVector? = null
) {
    data object Dashboard : AdminNavDestination("dashboard", "Dashboard", Icons.Default.Dashboard)
    data object InstallationManagement : AdminNavDestination("installations", "Installations", Icons.Default.Devices)
    data object UserManagement : AdminNavDestination("users", "Users", Icons.Default.People)
    data object NotificationManagement : AdminNavDestination("notifications", "Push Alerts", Icons.Default.NotificationsActive)
    data object NoticeManagement : AdminNavDestination("notices", "Notices", Icons.Default.Campaign)
    data object VersionControl : AdminNavDestination("version", "Version Control", Icons.Default.SystemUpdate)
    data object Analytics : AdminNavDestination("analytics", "Analytics", Icons.Default.Analytics)
    data object ActivityLogs : AdminNavDestination("logs", "Activity Logs", Icons.Default.History)
    data object SchemaAudit : AdminNavDestination("schema_audit", "Schema Audit", Icons.Default.Schema)
    data object Settings : AdminNavDestination("settings", "Settings", Icons.Default.Settings)

    // Sub-screens
    data class InstallationDetail(val id: String) : AdminNavDestination("installation_detail/$id", "Installation Detail")
    data class UserDetail(val uid: String) : AdminNavDestination("user_detail/$uid", "User Detail")
    data class NoticeEditor(val noticeId: String? = null) : AdminNavDestination(
        if (noticeId != null) "notice_edit/$noticeId" else "notice_create",
        if (noticeId != null) "Edit Notice" else "New Notice"
    )

    companion object {
        val mainNavItems: List<AdminNavDestination>
            get() = listOf(
                Dashboard,
                InstallationManagement,
                UserManagement,
                NotificationManagement,
                NoticeManagement,
                VersionControl,
                Analytics,
                ActivityLogs,
                SchemaAudit,
                Settings
            )
    }
}
