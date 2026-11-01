package com.example.dashboard

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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

        // Top Status Banner
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
                                text = "Last telemetry sync: $timeStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusBadge(status = if (state.healthReport?.isReachable == true) "ONLINE" else "DISCONNECTED")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { onNavigate(AdminNavDestination.NoticeEditor()) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).testTag("quick_new_notice_btn")
                        ) {
                            Text("New Notice", style = MaterialTheme.typography.labelMedium)
                        }
                        FilledTonalButton(
                            onClick = { onNavigate(AdminNavDestination.VersionControl) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).testTag("quick_version_btn")
                        ) {
                            Text("Version", style = MaterialTheme.typography.labelMedium)
                        }
                        FilledTonalButton(
                            onClick = { onNavigate(AdminNavDestination.SchemaAudit) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).testTag("quick_schema_btn")
                        ) {
                            Text("Schema Audit", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // Metrics Grid (2x2)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "Backend Health",
                    value = if (state.healthReport?.isReachable == true) "200 OK" else "Unreachable",
                    subtitle = if (state.healthReport?.isReachable == true) "${state.healthReport?.latencyMs} ms latency" else "Check API Base URL",
                    icon = Icons.Default.Speed,
                    badgeText = if (state.healthReport?.isReachable == true) "READY" else "OFFLINE",
                    badgeColor = if (state.healthReport?.isReachable == true) StatusActive else StatusSuspended,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(AdminNavDestination.Analytics) }
                )
                MetricCard(
                    title = "App Version",
                    value = state.versionConfig?.versionName ?: "3.0.0",
                    subtitle = "Code: ${state.versionConfig?.versionCode ?: 300}",
                    icon = Icons.Default.SystemUpdate,
                    badgeText = if (state.versionConfig?.isMandatory == true) "MANDATORY" else "OPTIONAL",
                    badgeColor = if (state.versionConfig?.isMandatory == true) MaterialTheme.colorScheme.error else InstitutionalGreen,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(AdminNavDestination.VersionControl) }
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "Live Notices",
                    value = "${state.noticesCount}",
                    subtitle = "${state.activeNotices.size} published to users",
                    icon = Icons.Default.Campaign,
                    badgeText = if (state.isFirebaseConnected) "FIRESTORE" else "AWAITING CONFIG",
                    badgeColor = if (state.isFirebaseConnected) StatusActive else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(AdminNavDestination.NoticeManagement) }
                )
                MetricCard(
                    title = "Readiness",
                    value = if (state.readinessReport?.isReachable == true) "Ready" else "Standby",
                    subtitle = "Endpoint: /ready",
                    icon = if (state.readinessReport?.isReachable == true) Icons.Default.CloudDone else Icons.Default.CloudOff,
                    badgeText = if (state.readinessReport?.isReachable == true) "READY" else "STANDBY",
                    badgeColor = if (state.readinessReport?.isReachable == true) StatusActive else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(AdminNavDestination.Analytics) }
                )
            }
        }

        // Active Notices Feed
        item {
            SectionHeader(
                title = "Active Broadcast Notices",
                subtitle = "Broadcasting to Prophecy Verse V3 User APK",
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
                            text = if (state.isFirebaseConnected) "No active notices published." else "Awaiting live Firestore connection to load active notices.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(state.activeNotices.take(3)) { notice ->
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
                                StatusBadge(status = notice.type)
                                Text(
                                    text = notice.title,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = notice.content,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}
