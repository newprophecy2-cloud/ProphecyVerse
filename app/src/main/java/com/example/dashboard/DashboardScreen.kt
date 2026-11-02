package com.example.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.installations.InstallationListItem
import com.example.navigation.AdminNavDestination
import com.example.ui.components.MetricCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusBadge
import com.example.ui.theme.InstitutionalGreen
import com.example.ui.theme.StatusActive
import com.example.ui.theme.StatusSuspended
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigate: (AdminNavDestination) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (state.isLoading) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = InstitutionalGreen
                )
            }
        }

        state.errorMessage?.let { message ->
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Top Status & Quick Nav Banner
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Prophecy Verse Control Center",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(state.lastRefreshedAt))
                            Text(
                                text = "Telemetry synchronized: $timeStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.refreshDashboard() }) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh dashboard")
                            }
                            StatusBadge(status = if (state.isFirebaseConnected) "FIRESTORE LIVE" else "DATA UNAVAILABLE")
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { onNavigate(AdminNavDestination.InstallationManagement) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("quick_installations_btn")
                        ) {
                            Text("Installations (${state.installationStats.totalCount})")
                        }
                        FilledTonalButton(
                            onClick = { onNavigate(AdminNavDestination.UserManagement) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("quick_users_btn")
                        ) {
                            Text("Users (${state.totalUsersCount})")
                        }
                        FilledTonalButton(
                            onClick = { onNavigate(AdminNavDestination.VersionControl) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("quick_version_btn")
                        ) {
                            Text("Version Control")
                        }
                        FilledTonalButton(
                            onClick = { onNavigate(AdminNavDestination.NotificationManagement) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("quick_notifications_btn")
                        ) {
                            Text("Push Alerts")
                        }
                        FilledTonalButton(
                            onClick = { onNavigate(AdminNavDestination.NoticeManagement) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("quick_notices_btn")
                        ) {
                            Text("Notices")
                        }
                    }
                }
            }
        }

        // Metrics Grid Row 1 (Installations & Users)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "App Installations",
                    value = "${state.installationStats.totalCount}",
                    subtitle = "${state.installationStats.activeNowCount} active now (<5m)",
                    icon = Icons.Default.Devices,
                    badgeText = "ACTIVE",
                    badgeColor = if (state.installationStats.activeNowCount > 0) StatusActive else InstitutionalGreen,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(AdminNavDestination.InstallationManagement) }
                )
                MetricCard(
                    title = "Registered Users",
                    value = "${state.totalUsersCount}",
                    subtitle = "${state.activeUsersCount} active accounts",
                    icon = Icons.Default.People,
                    badgeText = "ACCOUNTS",
                    badgeColor = InstitutionalGreen,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(AdminNavDestination.UserManagement) }
                )
            }
        }

        // Metrics Grid Row 2 (Version & Watch Time)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val config = state.versionConfig
                val verName = config?.latestVersionName ?: "3.0.0"
                val verCode = config?.latestVersionCode ?: 5L
                MetricCard(
                    title = "Version Manifest",
                    value = "v$verName",
                    subtitle = "Code $verCode • Min ${config?.minSupportedVersionCode ?: 5}",
                    icon = Icons.Default.SystemUpdate,
                    badgeText = if (config?.forceUpdate == true) "MANDATORY" else "OPTIONAL",
                    badgeColor = if (config?.forceUpdate == true) MaterialTheme.colorScheme.error else InstitutionalGreen,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(AdminNavDestination.VersionControl) }
                )

                val hrs = state.installationStats.totalWatchSeconds / 3600
                val mins = (state.installationStats.totalWatchSeconds % 3600) / 60
                val watchStr = if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
                MetricCard(
                    title = "Total Watch Time",
                    value = watchStr,
                    subtitle = "Across all installations",
                    icon = Icons.Default.Timer,
                    badgeText = "STREAMED",
                    badgeColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(AdminNavDestination.Analytics) }
                )
            }
        }

        // Metrics Grid Row 3 (Notices & Push Notifications)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "Broadcast Notices",
                    value = "${state.noticesCount}",
                    subtitle = "${state.activeNotices.size} active announcements",
                    icon = Icons.Default.Campaign,
                    badgeText = if (state.activeNotices.isNotEmpty()) "LIVE" else "EMPTY",
                    badgeColor = if (state.activeNotices.isNotEmpty()) StatusActive else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(AdminNavDestination.NoticeManagement) }
                )
                MetricCard(
                    title = "Push Notifications",
                    value = "${state.queuedNotificationsCount}",
                    subtitle = "Queued for FCM dispatch",
                    icon = Icons.Default.NotificationsActive,
                    badgeText = "FCM QUEUE",
                    badgeColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(AdminNavDestination.NotificationManagement) }
                )
            }
        }

        // Recent Installations Feed
        item {
            SectionHeader(
                title = "Recent Installations",
                subtitle = "Latest devices running User APK",
                action = {
                    Button(
                        onClick = { onNavigate(AdminNavDestination.InstallationManagement) },
                        colors = ButtonDefaults.buttonColors(containerColor = InstitutionalGreen),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("View All (${state.installationStats.totalCount})")
                    }
                }
            )
        }

        if (state.recentInstallations.isEmpty()) {
            item {
                OutlinedCard(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (state.isFirebaseConnected) "No installations recorded yet." else "Connecting to live Firestore installations...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(state.recentInstallations.take(3), key = { it.id }) { inst ->
                InstallationListItem(
                    record = inst,
                    onClick = { onNavigate(AdminNavDestination.InstallationDetail(inst.id)) }
                )
            }
        }

        // Active Notices Feed
        item {
            SectionHeader(
                title = "Active In-App Notices",
                subtitle = "Shown in User APK notification banner",
                action = {
                    Button(
                        onClick = { onNavigate(AdminNavDestination.NoticeManagement) },
                        colors = ButtonDefaults.buttonColors(containerColor = InstitutionalGreen),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Manage All")
                    }
                }
            )
        }

        if (state.activeNotices.isEmpty()) {
            item {
                OutlinedCard(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No active notices published.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(state.activeNotices.take(3), key = { it.id }) { notice ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatusBadge(status = notice.effectiveSeverity)
                                Text(
                                    text = notice.title,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = notice.displayContent,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
