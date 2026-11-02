package com.example.analytics

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.MetricCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusBadge
import com.example.ui.theme.InstitutionalGreen
import com.example.ui.theme.StatusActive
import com.example.ui.theme.StatusSuspended

@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel
) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("analytics_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        state.errorMessage?.let { message ->
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Analytics data unavailable",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Header
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(InstitutionalGreen.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Analytics,
                                    contentDescription = null,
                                    tint = InstitutionalGreen,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "System Analytics & Telemetry",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Real metrics aggregated from User APK installations & playback",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.fetchAnalytics() }) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh Telemetry")
                        }
                    }
                }
            }
        }

        // Section A: Installation & Active Telemetry
        item {
            SectionHeader(
                title = "Installation & Activity Analytics",
                subtitle = "Active device presence based on last_active_at timestamps"
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "Total Installations",
                    value = "${state.installationStats.totalCount}",
                    subtitle = "${state.installationStats.activeNowCount} active now (<5m)",
                    icon = Icons.Default.Devices,
                    badgeText = "TELEMETRY",
                    badgeColor = InstitutionalGreen,
                    modifier = Modifier.weight(1f)
                )

                MetricCard(
                    title = "Recent Active",
                    value = "${state.installationStats.recentlyActiveCount}",
                    subtitle = "${state.installationStats.inactiveCount} inactive (>24h)",
                    icon = Icons.Default.PhoneAndroid,
                    badgeText = "24H ACTIVE",
                    badgeColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Version & OS Distribution Breakdown
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "App Version Distribution",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (state.installationStats.versionDistribution.isEmpty()) {
                        Text(
                            text = "Awaiting installation telemetry...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val total = state.installationStats.totalCount.coerceAtLeast(1)
                        state.installationStats.versionDistribution.forEach { (ver, count) ->
                            val pct = (count * 100) / total
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "v$ver", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                                    Text(text = "$count devices ($pct%)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                LinearProgressIndicator(
                                    progress = { count.toFloat() / total },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = InstitutionalGreen
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section B: Watch Analytics
        item {
            SectionHeader(
                title = "Watch-Time & Viewing Analytics",
                subtitle = "Calculated from total_watch_seconds and watch_history"
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val totalSec = state.watchAnalytics.totalWatchSeconds.coerceAtLeast(state.installationStats.totalWatchSeconds)
                val hrs = totalSec / 3600
                val mins = (totalSec % 3600) / 60
                val watchDisplay = if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"

                MetricCard(
                    title = "Total Watch Time",
                    value = watchDisplay,
                    subtitle = "All installations combined",
                    icon = Icons.Default.Timer,
                    badgeText = "WATCHED",
                    badgeColor = InstitutionalGreen,
                    modifier = Modifier.weight(1f)
                )

                MetricCard(
                    title = "Completed Episodes",
                    value = "${state.watchAnalytics.totalCompletedEpisodes}",
                    subtitle = "Finished playback sessions",
                    icon = Icons.Default.CheckCircle,
                    badgeText = "COMPLETED",
                    badgeColor = StatusActive,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Top Series by Viewing
        if (state.watchAnalytics.topSeries.isNotEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Movie, contentDescription = null, tint = InstitutionalGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Most-Recorded Anime (Watch History)",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        state.watchAnalytics.topSeries.forEachIndexed { idx, series ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "#${idx + 1}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = InstitutionalGreen,
                                        modifier = Modifier.width(28.dp)
                                    )
                                    Text(
                                        text = series.animeTitle,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1
                                    )
                                }
                                Text(
                                    text = "${series.totalEpisodesWatched} saved episodes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section C: Backend Health Probes
        item {
            SectionHeader(
                title = "Backend Service Probes",
                subtitle = "Live round-trip latency and HTTP response status"
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "GET /health",
                    value = if (state.healthReport?.isReachable == true) "${state.healthReport?.latencyMs} ms" else "Offline",
                    subtitle = "Code: ${state.healthReport?.httpCode ?: 0}",
                    icon = Icons.Default.Speed,
                    badgeText = if (state.healthReport?.isReachable == true) "200 OK" else "FAILED",
                    badgeColor = if (state.healthReport?.isReachable == true) StatusActive else StatusSuspended,
                    modifier = Modifier.weight(1f)
                )

                MetricCard(
                    title = "GET /ready",
                    value = if (state.readinessReport?.isReachable == true) "${state.readinessReport?.latencyMs} ms" else "Offline",
                    subtitle = "Code: ${state.readinessReport?.httpCode ?: 0}",
                    icon = Icons.Default.CheckCircle,
                    badgeText = if (state.readinessReport?.isReachable == true) "200 OK" else "FAILED",
                    badgeColor = if (state.readinessReport?.isReachable == true) StatusActive else StatusSuspended,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
