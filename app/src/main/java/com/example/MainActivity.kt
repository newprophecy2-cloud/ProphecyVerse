package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.analytics.AnalyticsScreen
import com.example.analytics.AnalyticsViewModel
import com.example.auth.AdminAuthManager
import com.example.auth.AuthStatus
import com.example.dashboard.DashboardScreen
import com.example.dashboard.DashboardViewModel
import com.example.installations.InstallationDetailScreen
import com.example.installations.InstallationManagementScreen
import com.example.installations.InstallationViewModel
import com.example.logs.ActivityLogsScreen
import com.example.logs.LogsViewModel
import com.example.navigation.AdminNavDestination
import com.example.notices.NoticeEditorScreen
import com.example.notices.NoticeManagementScreen
import com.example.notices.NoticeViewModel
import com.example.notifications.NotificationManagementScreen
import com.example.notifications.NotificationViewModel
import com.example.schema.SchemaAuditScreen
import com.example.settings.SettingsScreen
import com.example.settings.SettingsViewModel
import com.example.ui.components.AdminScaffold
import com.example.ui.theme.ProphecyVerseAdminTheme
import com.example.users.UserDetailScreen
import com.example.users.UserManagementScreen
import com.example.users.UserViewModel
import com.example.version.VersionControlScreen
import com.example.version.VersionViewModel

class MainActivity : ComponentActivity() {

    private var authManager: AdminAuthManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { enableEdgeToEdge() }
        try {
            authManager = AdminAuthManager(applicationContext)
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "Failed to initialize AdminAuthManager: ${t.message}", t)
        }

        setContent {
            ProphecyVerseAdminTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var initError by remember { mutableStateOf<String?>(null) }

                    val manager = authManager ?: remember {
                        try {
                            AdminAuthManager(applicationContext).also { authManager = it }
                        } catch (t: Throwable) {
                            initError = t.localizedMessage ?: "Failed to initialize Admin manager"
                            null
                        }
                    }

                    if (manager != null) {
                        AdminAppRoot(authManager = manager)
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            androidx.compose.material3.Card(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                androidx.compose.foundation.layout.Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
                                ) {
                                    Text(
                                        text = "Prophecy Verse Admin",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                    Text(
                                        text = initError ?: "Initializing administrator services...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    androidx.compose.material3.Button(
                                        onClick = {
                                            initError = null
                                            try {
                                                authManager = AdminAuthManager(applicationContext)
                                            } catch (t: Throwable) {
                                                initError = t.localizedMessage ?: "Retry failed"
                                            }
                                        }
                                    ) {
                                        Text("Retry Launch")
                                    }
                                }
                            }
                        }
                    }
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
        val installationVm: InstallationViewModel = viewModel()
        val noticeVm: NoticeViewModel = viewModel()
        val userVm: UserViewModel = viewModel()
        val versionVm: VersionViewModel = viewModel()
        val notificationVm: NotificationViewModel = viewModel()
        val analyticsVm: AnalyticsViewModel = viewModel()
        val logsVm: LogsViewModel = viewModel()
        val settingsVm: SettingsViewModel = viewModel()

        // Handle Back button for nested sub-screens
        if (currentDestination !in AdminNavDestination.mainNavItems) {
            BackHandler {
                when (currentDestination) {
                    is AdminNavDestination.InstallationDetail -> currentDestination = AdminNavDestination.InstallationManagement
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
                    is AdminNavDestination.InstallationManagement -> installationVm.refreshAll()
                    is AdminNavDestination.UserManagement -> userVm.loadUsers()
                    is AdminNavDestination.NotificationManagement -> notificationVm.loadNotifications()
                    is AdminNavDestination.NoticeManagement -> noticeVm.loadNotices()
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
                is AdminNavDestination.InstallationManagement -> InstallationManagementScreen(
                    viewModel = installationVm,
                    onNavigate = { currentDestination = it }
                )
                is AdminNavDestination.InstallationDetail -> InstallationDetailScreen(
                    installationDocId = dest.id,
                    viewModel = installationVm,
                    adminEmail = activeAdminEmail,
                    onBack = { currentDestination = AdminNavDestination.InstallationManagement },
                    onNavigateToUser = { uid -> currentDestination = AdminNavDestination.UserDetail(uid) }
                )
                is AdminNavDestination.UserManagement -> UserManagementScreen(
                    viewModel = userVm,
                    onNavigate = { currentDestination = it }
                )
                is AdminNavDestination.UserDetail -> UserDetailScreen(
                    uid = dest.uid,
                    viewModel = userVm,
                    adminEmail = activeAdminEmail,
                    onBack = { currentDestination = AdminNavDestination.UserManagement },
                    onNavigateToInstallation = { instId -> currentDestination = AdminNavDestination.InstallationDetail(instId) }
                )
                is AdminNavDestination.NotificationManagement -> NotificationManagementScreen(
                    viewModel = notificationVm,
                    adminEmail = activeAdminEmail
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
