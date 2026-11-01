package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.analytics.AnalyticsScreen
import com.example.analytics.AnalyticsViewModel
import com.example.auth.AdminAuthManager
import com.example.auth.AuthStatus
import com.example.dashboard.DashboardScreen
import com.example.dashboard.DashboardViewModel
import com.example.logs.ActivityLogsScreen
import com.example.logs.LogsViewModel
import com.example.navigation.AdminNavDestination
import com.example.notices.NoticeEditorScreen
import com.example.notices.NoticeManagementScreen
import com.example.notices.NoticeViewModel
import com.example.schema.SchemaAuditScreen
import com.example.settings.SettingsScreen
import com.example.settings.SettingsViewModel
import com.example.ui.components.AdminScaffold
import com.example.ui.theme.InstitutionalGreen
import com.example.ui.theme.ProphecyVerseAdminTheme
import com.example.users.UserDetailScreen
import com.example.users.UserManagementScreen
import com.example.users.UserViewModel
import com.example.version.VersionControlScreen
import com.example.version.VersionViewModel

class MainActivity : ComponentActivity() {

    private lateinit var authManager: AdminAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        authManager = AdminAuthManager(applicationContext)

        setContent {
            ProphecyVerseAdminTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AdminAppRoot(authManager = authManager)
                }
            }
        }
    }
}

@Composable
fun AdminAppRoot(authManager: AdminAuthManager) {
    val authStatus by authManager.authStatus.collectAsState()
    var currentDestination by remember { mutableStateOf<AdminNavDestination>(AdminNavDestination.Dashboard) }

    val isAuthenticated = authStatus is AuthStatus.AuthenticatedAdmin
    val activeAdminEmail = if (authStatus is AuthStatus.AuthenticatedAdmin) {
        (authStatus as AuthStatus.AuthenticatedAdmin).email
    } else {
        ""
    }

    if (!isAuthenticated) {
        com.example.auth.AuthScreen(authManager = authManager)
    } else {
        val dashboardVm: DashboardViewModel = viewModel()
        val noticeVm: NoticeViewModel = viewModel()
        val userVm: UserViewModel = viewModel()
        val versionVm: VersionViewModel = viewModel()
        val analyticsVm: AnalyticsViewModel = viewModel()
        val logsVm: LogsViewModel = viewModel()
        val settingsVm: SettingsViewModel = viewModel()

        // Handle Back button for nested sub-screens
        if (currentDestination !in AdminNavDestination.mainNavItems) {
            BackHandler {
                when (currentDestination) {
                    is AdminNavDestination.UserDetail -> currentDestination = AdminNavDestination.UserManagement
                    is AdminNavDestination.NoticeEditor -> currentDestination = AdminNavDestination.NoticeManagement
                    else -> currentDestination = AdminNavDestination.Dashboard
                }
            }
        }

        AdminScaffold(
            currentDestination = currentDestination,
            onNavigate = { destination -> currentDestination = destination },
            onLogout = {
                authManager.signOut()
                currentDestination = AdminNavDestination.Dashboard
            },
            onRefresh = {
                when (currentDestination) {
                    is AdminNavDestination.Dashboard -> dashboardVm.refreshDashboard()
                    is AdminNavDestination.NoticeManagement -> noticeVm.loadNotices()
                    is AdminNavDestination.UserManagement -> userVm.loadUsers()
                    is AdminNavDestination.VersionControl -> versionVm.loadVersionConfig()
                    is AdminNavDestination.Analytics -> analyticsVm.fetchAnalytics()
                    is AdminNavDestination.ActivityLogs -> logsVm.loadLogs()
                    else -> {}
                }
            },
            isFirebaseReady = authManager.isFirebaseReady(),
            adminEmail = activeAdminEmail
        ) {
            when (val dest = currentDestination) {
                is AdminNavDestination.Dashboard -> DashboardScreen(
                    viewModel = dashboardVm,
                    onNavigate = { currentDestination = it }
                )
                is AdminNavDestination.UserManagement -> UserManagementScreen(
                    viewModel = userVm,
                    onNavigate = { currentDestination = it }
                )
                is AdminNavDestination.UserDetail -> UserDetailScreen(
                    uid = dest.uid,
                    viewModel = userVm,
                    adminEmail = activeAdminEmail,
                    onBack = { currentDestination = AdminNavDestination.UserManagement }
                )
                is AdminNavDestination.NoticeManagement -> NoticeManagementScreen(
                    viewModel = noticeVm,
                    adminEmail = activeAdminEmail,
                    onNavigate = { currentDestination = it }
                )
                is AdminNavDestination.NoticeEditor -> NoticeEditorScreen(
                    noticeId = dest.noticeId,
                    viewModel = noticeVm,
                    adminEmail = activeAdminEmail,
                    onBack = { currentDestination = AdminNavDestination.NoticeManagement }
                )
                is AdminNavDestination.VersionControl -> VersionControlScreen(
                    viewModel = versionVm,
                    adminEmail = activeAdminEmail
                )
                is AdminNavDestination.Analytics -> AnalyticsScreen(
                    viewModel = analyticsVm
                )
                is AdminNavDestination.ActivityLogs -> ActivityLogsScreen(
                    viewModel = logsVm
                )
                is AdminNavDestination.SchemaAudit -> SchemaAuditScreen()
                is AdminNavDestination.Settings -> SettingsScreen(
                    viewModel = settingsVm,
                    adminEmail = activeAdminEmail,
                    isFirebaseReady = authManager.isFirebaseReady()
                )
            }
        }
    }
}
