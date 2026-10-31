package com.example.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.local.DownloadEntry
import com.example.ui.components.EmptyState
import com.example.ui.theme.AccentCoral
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentIndigo
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.PlayableGreen
import com.example.ui.theme.SpaceDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.SurfaceVariantDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodels.DownloadsViewModel

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onPlayOffline: (animeId: String, episodeId: String) -> Unit,
    onAnimeClick: (animeId: String) -> Unit
) {
    val context = LocalContext.current
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val completedDownloads by viewModel.completedDownloads.collectAsStateWithLifecycle()
    val storageStats by viewModel.storageStats.collectAsStateWithLifecycle()
    val liveProgressMap by viewModel.liveProgressMap.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(0) } // 0: Downloaded, 1: Downloading
    var itemToDelete by remember { mutableStateOf<DownloadEntry?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SpaceDark)
            .testTag("downloads_screen")
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Downloads",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "App-private offline media storage",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentCyan,
                    fontSize = 11.sp
                )
            }
        }

        // Storage Usage Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Private App Storage",
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            fontSize = 13.sp
                        )
                    }
                    val usedStr = Formatter.formatFileSize(context, storageStats.usedBytes)
                    val freeStr = Formatter.formatFileSize(context, storageStats.freeBytes)
                    Text(
                        text = "$usedStr Used  •  $freeStr Free",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val usedRatio = if (storageStats.totalDeviceBytes > 0) {
                    (storageStats.usedBytes.toFloat() / storageStats.totalDeviceBytes.toFloat()).coerceIn(0.01f, 1f)
                } else 0.05f
                LinearProgressIndicator(
                    progress = { usedRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = AccentIndigo,
                    trackColor = SurfaceHighlight
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Media files are stored securely in app-private storage and do not appear in Gallery.",
                    color = TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Two Section Tabs: Downloaded (X) vs Downloading (Y)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilterChip(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                label = { Text("Downloaded (${completedDownloads.size})", fontSize = 13.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.DownloadDone,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = SurfaceVariantDark,
                    labelColor = TextSecondary,
                    selectedContainerColor = AccentIndigo,
                    selectedLabelColor = Color.White
                ),
                border = null,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("tab_downloaded")
            )

            FilterChip(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                label = { Text("Downloading (${activeDownloads.size})", fontSize = 13.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = SurfaceVariantDark,
                    labelColor = TextSecondary,
                    selectedContainerColor = AccentCyan,
                    selectedLabelColor = SpaceDark
                ),
                border = null,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("tab_downloading")
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Content Lists
        if (selectedTab == 0) {
            // Completed Downloads
            if (completedDownloads.isEmpty()) {
                EmptyState(
                    title = "No Downloaded Media",
                    message = "Episodes or movies you download will appear here for completely offline playback."
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(completedDownloads, key = { it.id }) { item ->
                        DownloadedItemCard(
                            item = item,
                            onPlayClick = { onPlayOffline(item.animeId, item.id) },
                            onMediaPageClick = { onAnimeClick(item.animeId) },
                            onDeleteClick = { itemToDelete = item }
                        )
                    }
                }
            }
        } else {
            // Downloading in-progress
            if (activeDownloads.isEmpty()) {
                EmptyState(
                    title = "No Active Downloads",
                    message = "Tap Download on any episode or movie source to download it for offline viewing."
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(activeDownloads, key = { it.id }) { item ->
                        val live = liveProgressMap[item.id]
                        DownloadingItemCard(
                            item = item,
                            liveProgress = live,
                            onPause = { viewModel.pauseDownload(item.id) },
                            onResume = { viewModel.resumeDownload(item.id) },
                            onCancel = { viewModel.cancelDownload(item.id) }
                        )
                    }
                }
            }
        }
    }

    if (itemToDelete != null) {
        val toDel = itemToDelete!!
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete Download?", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Text(
                    "Delete downloaded file for ${toDel.animeTitle} (S${toDel.seasonNumber} : E${toDel.episodeNumber}) from device storage?",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteDownload(toDel.id)
                        itemToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { itemToDelete = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}

@Composable
fun DownloadedItemCard(
    item: DownloadEntry,
    onPlayClick: () -> Unit,
    onMediaPageClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlayClick)
            .testTag("downloaded_card_${item.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Poster thumbnail
            Card(
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (!item.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.animeTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SurfaceHighlight),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = TextMuted)
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.animeTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "S${item.seasonNumber} • Ep ${item.episodeNumber}   ${item.episodeTitle ?: ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentCyan,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                val sizeStr = Formatter.formatFileSize(context, item.downloadedBytes.coerceAtLeast(item.totalBytes))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = PlayableGreen.copy(alpha = 0.2f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = PlayableGreen,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Offline Ready",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = PlayableGreen
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = sizeStr,
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlayClick) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Offline",
                        tint = PlayableGreen,
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadingItemCard(
    item: DownloadEntry,
    liveProgress: com.example.data.download.DownloadManager.LiveProgress?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val currentBytes = liveProgress?.downloadedBytes ?: item.downloadedBytes
    val totalBytes = liveProgress?.totalBytes ?: item.totalBytes
    val progress = if (totalBytes > 0L) (currentBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val isPaused = item.downloadStatus == "PAUSED"
    val isFailed = item.downloadStatus == "FAILED"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.animeTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Season ${item.seasonNumber} • Episode ${item.episodeNumber}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentCyan,
                        fontSize = 11.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isFailed) {
                        IconButton(onClick = onResume, modifier = Modifier.size(34.dp)) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Retry", tint = AccentIndigo)
                        }
                    } else if (isPaused) {
                        IconButton(onClick = onResume, modifier = Modifier.size(34.dp)) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Resume", tint = PlayableGreen)
                        }
                    } else {
                        IconButton(onClick = onPause, modifier = Modifier.size(34.dp)) {
                            Icon(imageVector = Icons.Default.Pause, contentDescription = "Pause", tint = AccentCyan)
                        }
                    }
                    IconButton(onClick = onCancel, modifier = Modifier.size(34.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel", tint = TextMuted)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (isPaused) TextMuted else if (isFailed) ErrorRed else AccentCyan,
                trackColor = SurfaceHighlight
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val downloadedStr = Formatter.formatFileSize(context, currentBytes)
                val totalStr = if (totalBytes > 0) Formatter.formatFileSize(context, totalBytes) else "..."
                val statusText = when {
                    isFailed -> item.errorMessage ?: "Failed"
                    isPaused -> "Paused"
                    liveProgress != null && liveProgress.speedBytesPerSec > 0 -> {
                        val speedStr = Formatter.formatFileSize(context, liveProgress.speedBytesPerSec) + "/s"
                        "${(progress * 100).toInt()}% • $speedStr"
                    }
                    else -> "${(progress * 100).toInt()}%"
                }
                Text(
                    text = statusText,
                    color = if (isFailed) ErrorRed else TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "$downloadedStr / $totalStr",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}
