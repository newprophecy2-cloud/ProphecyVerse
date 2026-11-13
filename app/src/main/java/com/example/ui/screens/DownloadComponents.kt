package com.example.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.download.DownloadManager
import com.example.data.local.DownloadEntry
import com.example.data.local.EpisodeEntry
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentGold
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

/**
 * Compact Download Action Row placed directly beneath the video player and title.
 * Provides a small, stylish Download button that opens the Download Management System pop-up,
 * a Batch Download button to download multiple episodes, and an active queue badge.
 */
@Composable
fun CompactDownloadActionRow(
    download: DownloadEntry?,
    activeDownloadsCount: Int,
    onOpenDownloadSheet: () -> Unit,
    onOpenBatchSheet: () -> Unit,
    onOpenQueueSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Compact Download Button for Current Episode
        Surface(
            onClick = onOpenDownloadSheet,
            shape = RoundedCornerShape(18.dp),
            color = when (download?.downloadStatus) {
                "COMPLETED" -> PlayableGreen.copy(alpha = 0.15f)
                "DOWNLOADING" -> AccentCyan.copy(alpha = 0.15f)
                "PAUSED" -> AccentGold.copy(alpha = 0.15f)
                "FAILED" -> ErrorRed.copy(alpha = 0.15f)
                else -> SurfaceVariantDark
            },
            border = BorderStroke(
                1.dp,
                when (download?.downloadStatus) {
                    "COMPLETED" -> PlayableGreen
                    "DOWNLOADING" -> AccentCyan
                    "PAUSED" -> AccentGold
                    "FAILED" -> ErrorRed
                    else -> SurfaceHighlight
                }
            ),
            modifier = Modifier
                .height(36.dp)
                .testTag("compact_download_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (download?.downloadStatus) {
                    "COMPLETED" -> {
                        Icon(
                            imageVector = Icons.Default.DownloadDone,
                            contentDescription = "Downloaded",
                            tint = PlayableGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Downloaded",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = PlayableGreen
                        )
                    }
                    "DOWNLOADING" -> {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(13.dp),
                            color = AccentCyan
                        )
                        Text(
                            text = "${download.progressPercent}%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyan
                        )
                    }
                    "PAUSED" -> {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = "Paused",
                            tint = AccentGold,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Paused",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentGold
                        )
                    }
                    "FAILED" -> {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Failed",
                            tint = ErrorRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Retry",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = ErrorRed
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download",
                            tint = AccentCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Download",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                }
            }
        }

        // Compact Batch Download Button
        Surface(
            onClick = onOpenBatchSheet,
            shape = RoundedCornerShape(18.dp),
            color = SurfaceVariantDark,
            border = BorderStroke(1.dp, SurfaceHighlight),
            modifier = Modifier
                .height(36.dp)
                .testTag("compact_batch_download_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Batch Download",
                    tint = TextSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Batch Download",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary
                )
            }
        }

        // Active Downloads Badge
        if (activeDownloadsCount > 0) {
            Surface(
                onClick = onOpenQueueSheet,
                shape = RoundedCornerShape(18.dp),
                color = AccentIndigo.copy(alpha = 0.25f),
                border = BorderStroke(1.dp, AccentIndigo),
                modifier = Modifier
                    .height(36.dp)
                    .testTag("active_downloads_badge_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "$activeDownloadsCount active",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan
                    )
                }
            }
        }
    }
}

/**
 * Download Management System Pop-up (ModalBottomSheet).
 * Handles Current Episode download, Batch Download for multiple episodes with quality & language selection,
 * and Active Downloads queue inspection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagementBottomSheet(
    animeTitle: String,
    selectedSeason: Int,
    seasons: List<Int>,
    currentEpisode: EpisodeEntry?,
    allEpisodes: List<EpisodeEntry>,
    currentDownload: DownloadEntry?,
    animeDownloads: List<DownloadEntry>,
    liveProgressMap: Map<String, DownloadManager.LiveProgress>,
    availableQualities: List<String>,
    availableLanguages: List<String>,
    storageStats: DownloadManager.StorageStats,
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    onStartSingleDownload: (quality: String?, audioLang: String?) -> Unit,
    onStartBatchDownload: (episodes: List<EpisodeEntry>, quality: String?, audioLang: String?) -> Unit,
    onPauseDownload: (episodeId: String) -> Unit,
    onResumeDownload: (episodeId: String, quality: String?, audioLang: String?) -> Unit,
    onCancelDownload: (episodeId: String) -> Unit,
    onDeleteDownload: (episodeId: String) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var activeBatchSeason by remember(selectedSeason) { mutableIntStateOf(selectedSeason) }

    val defaultQualities = remember(availableQualities) {
        availableQualities.distinct().filter { it.isNotBlank() }
    }
    var singleQuality by remember(defaultQualities) {
        mutableStateOf(defaultQualities.firstOrNull())
    }
    var singleLanguage by remember(availableLanguages) {
        mutableStateOf(availableLanguages.firstOrNull())
    }

    var batchQuality by remember(defaultQualities) {
        mutableStateOf(defaultQualities.firstOrNull())
    }
    var batchLanguage by remember(availableLanguages) {
        mutableStateOf(availableLanguages.firstOrNull())
    }

    val seasonEps = remember(allEpisodes, activeBatchSeason) {
        allEpisodes.filter { it.seasonNumber == activeBatchSeason }.sortedBy { it.episodeNumber }
    }

    val downloadsByEpisodeId = remember(animeDownloads) {
        animeDownloads.associateBy { it.id }
    }

    var selectedBatchEpisodeIds by remember(seasonEps, downloadsByEpisodeId) {
        val nonCompleted = seasonEps.filter { ep ->
            downloadsByEpisodeId[ep.id]?.downloadStatus != "COMPLETED"
        }.map { it.id }.toSet()
        mutableStateOf(nonCompleted)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        modifier = Modifier.testTag("download_management_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .padding(bottom = 28.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Download Manager",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "$animeTitle • Season $selectedSeason",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_download_sheet_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextMuted
                    )
                }
            }

            // Storage Stats Bar
            if (storageStats.totalDeviceBytes > 0) {
                val freeStr = Formatter.formatFileSize(context, storageStats.freeBytes)
                val totalStr = Formatter.formatFileSize(context, storageStats.totalDeviceBytes)
                val usedPct = (((storageStats.totalDeviceBytes - storageStats.freeBytes).toFloat() / storageStats.totalDeviceBytes.toFloat())).coerceIn(0f, 1f)

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Free Device Storage: $freeStr of $totalStr",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { usedPct },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = AccentIndigo,
                    trackColor = SurfaceHighlight
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Navigation Tabs
            val activeCount = animeDownloads.count { it.downloadStatus == "DOWNLOADING" || it.downloadStatus == "PENDING_RESOLUTION" || it.downloadStatus == "WAITING_FOR_SOURCE" || it.downloadStatus == "PAUSED" }
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = SurfaceVariantDark,
                contentColor = AccentCyan,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = AccentCyan
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = "This Episode",
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 0) AccentCyan else TextMuted
                        )
                    },
                    modifier = Modifier.testTag("tab_this_episode")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = "Batch (${seasonEps.size} eps)",
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 1) AccentCyan else TextMuted
                        )
                    },
                    modifier = Modifier.testTag("tab_batch_download")
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Text(
                            text = if (activeCount > 0) "Queue ($activeCount)" else "Queue (${animeDownloads.size})",
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 2) AccentCyan else TextMuted
                        )
                    },
                    modifier = Modifier.testTag("tab_queue_download")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedTab) {
                0 -> {
                    CurrentEpisodeDownloadContent(
                        episode = currentEpisode,
                        download = currentDownload,
                        availableQualities = defaultQualities,
                        availableLanguages = availableLanguages,
                        selectedQuality = singleQuality,
                        selectedLanguage = singleLanguage,
                        onQualityChange = { singleQuality = it },
                        onLanguageChange = { singleLanguage = it },
                        onStartDownload = { onStartSingleDownload(singleQuality, singleLanguage) },
                        onPauseDownload = { currentEpisode?.let { onPauseDownload(it.id) } },
                        onResumeDownload = { currentEpisode?.let { onResumeDownload(it.id, singleQuality, singleLanguage) } },
                        onCancelDownload = { currentEpisode?.let { onCancelDownload(it.id) } },
                        onDeleteDownload = { currentEpisode?.let { onDeleteDownload(it.id) } },
                        onRetryDownload = { onStartSingleDownload(singleQuality, singleLanguage) }
                    )
                }
                1 -> {
                    BatchDownloadContent(
                        seasons = seasons,
                        activeSeason = activeBatchSeason,
                        onSeasonChange = { activeBatchSeason = it },
                        seasonEpisodes = seasonEps,
                        downloadsByEpisodeId = downloadsByEpisodeId,
                        selectedEpisodeIds = selectedBatchEpisodeIds,
                        onToggleEpisode = { epId ->
                            selectedBatchEpisodeIds = if (selectedBatchEpisodeIds.contains(epId)) {
                                selectedBatchEpisodeIds - epId
                            } else {
                                selectedBatchEpisodeIds + epId
                            }
                        },
                        onSelectAll = {
                            val allNonCompleted = seasonEps.filter {
                                downloadsByEpisodeId[it.id]?.downloadStatus != "COMPLETED"
                            }.map { it.id }.toSet()
                            selectedBatchEpisodeIds = allNonCompleted
                        },
                        onDeselectAll = {
                            selectedBatchEpisodeIds = emptySet()
                        },
                        availableQualities = defaultQualities,
                        availableLanguages = availableLanguages,
                        selectedQuality = batchQuality,
                        selectedLanguage = batchLanguage,
                        onQualityChange = { batchQuality = it },
                        onLanguageChange = { batchLanguage = it },
                        onStartBatchDownload = {
                            val toDownload = seasonEps.filter { selectedBatchEpisodeIds.contains(it.id) }
                            if (toDownload.isNotEmpty()) {
                                onStartBatchDownload(toDownload, batchQuality, batchLanguage)
                                selectedTab = 2
                            }
                        }
                    )
                }
                2 -> {
                    QueueDownloadsContent(
                        downloads = animeDownloads,
                        liveProgressMap = liveProgressMap,
                        onPauseEpisode = onPauseDownload,
                        onResumeEpisode = { epId, q, l -> onResumeDownload(epId, q, l) },
                        onCancelEpisode = onCancelDownload,
                        onDeleteEpisode = onDeleteDownload,
                        onPauseAll = onPauseAll,
                        onResumeAll = onResumeAll
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentEpisodeDownloadContent(
    episode: EpisodeEntry?,
    download: DownloadEntry?,
    availableQualities: List<String>,
    availableLanguages: List<String>,
    selectedQuality: String?,
    selectedLanguage: String?,
    onQualityChange: (String?) -> Unit,
    onLanguageChange: (String?) -> Unit,
    onStartDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onRetryDownload: () -> Unit
) {
    val context = LocalContext.current

    if (episode == null) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No active episode selected", color = TextMuted)
        }
        return
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
        border = BorderStroke(1.dp, SurfaceHighlight),
        modifier = Modifier.fillMaxWidth().testTag("current_episode_download_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Episode ${episode.episodeNumber}${if (!episode.title.isNullOrBlank()) ": ${episode.title}" else ""}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Season ${episode.seasonNumber}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentCyan
                    )
                }

                if (download != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = when (download.downloadStatus) {
                            "COMPLETED" -> PlayableGreen.copy(alpha = 0.2f)
                            "DOWNLOADING" -> AccentCyan.copy(alpha = 0.2f)
                            "PAUSED" -> AccentGold.copy(alpha = 0.2f)
                            "FAILED" -> ErrorRed.copy(alpha = 0.2f)
                            else -> SpaceDark
                        }
                    ) {
                        Text(
                            text = download.downloadStatus,
                            color = when (download.downloadStatus) {
                                "COMPLETED" -> PlayableGreen
                                "DOWNLOADING" -> AccentCyan
                                "PAUSED" -> AccentGold
                                "FAILED" -> ErrorRed
                                else -> TextSecondary
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                download == null -> {
                    // Quality Selection Chips
                    if (availableQualities.isNotEmpty()) {
                        Text("Download Quality", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            items(availableQualities) { q ->
                                FilterChip(
                                    selected = selectedQuality == q,
                                    onClick = { onQualityChange(q) },
                                    label = { Text(q, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = SpaceDark,
                                        labelColor = TextSecondary,
                                        selectedContainerColor = AccentIndigo,
                                        selectedLabelColor = Color.White
                                    ),
                                    border = null,
                                    modifier = Modifier.testTag("download_quality_chip_$q")
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Audio Language Selection Chips
                    if (availableLanguages.size > 1) {
                        Text("Audio Language", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            items(availableLanguages) { lang ->
                                FilterChip(
                                    selected = selectedLanguage == lang,
                                    onClick = { onLanguageChange(lang) },
                                    label = { Text(lang, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = SpaceDark,
                                        labelColor = TextSecondary,
                                        selectedContainerColor = AccentIndigo,
                                        selectedLabelColor = Color.White
                                    ),
                                    border = null,
                                    modifier = Modifier.testTag("download_lang_chip_$lang")
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Button(
                        onClick = onStartDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp).testTag("start_download_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download Episode in $selectedQuality", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                download.downloadStatus == "DOWNLOADING" || download.downloadStatus == "PENDING_RESOLUTION" || download.downloadStatus == "WAITING_FOR_SOURCE" -> {
                    val progressFloat = (download.progressPercent / 100f).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progressFloat },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = AccentCyan,
                        trackColor = SurfaceHighlight
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${download.progressPercent}% • ${download.formattedSpeed}",
                            color = AccentCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        val downloadedStr = Formatter.formatFileSize(context, download.downloadedBytes)
                        val totalStr = Formatter.formatFileSize(context, download.totalBytes)
                        Text(
                            text = if (download.totalBytes > 0) "$downloadedStr / $totalStr" else downloadedStr,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onPauseDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceHighlight),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(38.dp).testTag("pause_download_button")
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = TextPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause", color = TextPrimary, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = onCancelDownload,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                            modifier = Modifier.weight(1f).height(38.dp).testTag("cancel_download_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel", fontSize = 12.sp)
                        }
                    }
                }

                download.downloadStatus == "PAUSED" -> {
                    Text(
                        text = "Paused at ${download.progressPercent}% (${Formatter.formatFileSize(context, download.downloadedBytes)})",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onResumeDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(38.dp).testTag("resume_download_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onCancelDownload,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                            modifier = Modifier.weight(1f).height(38.dp).testTag("cancel_download_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel", fontSize = 12.sp)
                        }
                    }
                }

                download.downloadStatus == "COMPLETED" -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Downloaded & Ready for Offline Play",
                                color = PlayableGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val sizeStr = Formatter.formatFileSize(context, download.totalBytes)
                            Text(
                                text = "${download.quality ?: "Original"} • ${download.audioLanguage ?: "Default Audio"} • $sizeStr",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        IconButton(
                            onClick = onDeleteDownload,
                            modifier = Modifier.testTag("delete_download_button")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = ErrorRed)
                        }
                    }
                }

                download.downloadStatus == "FAILED" -> {
                    Text(
                        text = download.errorMessage ?: "Download encountered an error.",
                        color = ErrorRed,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onRetryDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(38.dp).testTag("retry_download_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retry Download", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onDeleteDownload,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted),
                            modifier = Modifier.weight(1f).height(38.dp).testTag("dismiss_download_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Dismiss", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchDownloadContent(
    seasons: List<Int>,
    activeSeason: Int,
    onSeasonChange: (Int) -> Unit,
    seasonEpisodes: List<EpisodeEntry>,
    downloadsByEpisodeId: Map<String, DownloadEntry>,
    selectedEpisodeIds: Set<String>,
    onToggleEpisode: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    availableQualities: List<String>,
    availableLanguages: List<String>,
    selectedQuality: String?,
    selectedLanguage: String?,
    onQualityChange: (String?) -> Unit,
    onLanguageChange: (String?) -> Unit,
    onStartBatchDownload: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Season Selector (if multi-season)
        if (seasons.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(seasons) { sNum ->
                    FilterChip(
                        selected = activeSeason == sNum,
                        onClick = { onSeasonChange(sNum) },
                        label = { Text("Season $sNum", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = SpaceDark,
                            labelColor = TextSecondary,
                            selectedContainerColor = AccentIndigo,
                            selectedLabelColor = Color.White
                        ),
                        border = null
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Quality and Language selection for batch
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Quality Chips
            Column(modifier = Modifier.weight(1f)) {
                Text("Quality", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(availableQualities) { q ->
                        FilterChip(
                            selected = selectedQuality == q,
                            onClick = { onQualityChange(q) },
                            label = { Text(q, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = SurfaceVariantDark,
                                labelColor = TextSecondary,
                                selectedContainerColor = AccentIndigo,
                                selectedLabelColor = Color.White
                            ),
                            border = null
                        )
                    }
                }
            }

            // Audio Language (if detected)
            if (availableLanguages.size > 1) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Audio Language", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(availableLanguages) { lang ->
                            FilterChip(
                                selected = selectedLanguage == lang,
                                onClick = { onLanguageChange(lang) },
                                label = { Text(lang, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = SurfaceVariantDark,
                                    labelColor = TextSecondary,
                                    selectedContainerColor = AccentIndigo,
                                    selectedLabelColor = Color.White
                                ),
                                border = null
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Selection Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${selectedEpisodeIds.size} of ${seasonEpisodes.size} selected",
                color = AccentCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = onSelectAll,
                    shape = RoundedCornerShape(6.dp),
                    color = SurfaceHighlight,
                    modifier = Modifier.height(28.dp).testTag("select_all_episodes_button")
                ) {
                    Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        Text("Select All", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Surface(
                    onClick = onDeselectAll,
                    shape = RoundedCornerShape(6.dp),
                    color = SurfaceHighlight,
                    modifier = Modifier.height(28.dp).testTag("deselect_all_episodes_button")
                ) {
                    Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        Text("Clear", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Episode Checklist (max height constrained for bottom sheet)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .background(SurfaceVariantDark, RoundedCornerShape(10.dp))
                .padding(vertical = 4.dp)
        ) {
            items(seasonEpisodes, key = { it.id }) { ep ->
                val dl = downloadsByEpisodeId[ep.id]
                val isCompleted = dl?.downloadStatus == "COMPLETED"
                val isDownloading = dl?.downloadStatus == "DOWNLOADING" || dl?.downloadStatus == "PENDING_RESOLUTION" || dl?.downloadStatus == "WAITING_FOR_SOURCE"
                val isChecked = selectedEpisodeIds.contains(ep.id)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isCompleted && !isDownloading) { onToggleEpisode(ep.id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Checkbox(
                            checked = isChecked || isCompleted,
                            onCheckedChange = { onToggleEpisode(ep.id) },
                            enabled = !isCompleted && !isDownloading,
                            colors = CheckboxDefaults.colors(
                                checkedColor = if (isCompleted) PlayableGreen else AccentCyan,
                                uncheckedColor = TextMuted
                            ),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Ep ${ep.episodeNumber}${if (!ep.title.isNullOrBlank()) ": ${ep.title}" else ""}",
                            color = if (isCompleted) PlayableGreen else TextPrimary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    when {
                        isCompleted -> {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = PlayableGreen.copy(alpha = 0.2f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = PlayableGreen, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("Downloaded", color = PlayableGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        isDownloading -> {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = AccentCyan.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "${dl?.progressPercent ?: 0}%",
                                    color = AccentCyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Start Batch Download Button
        Button(
            onClick = onStartBatchDownload,
            enabled = selectedEpisodeIds.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = PlayableGreen,
                disabledContainerColor = SurfaceHighlight
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .testTag("download_selected_button")
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (selectedEpisodeIds.isNotEmpty()) {
                    "Download ${selectedEpisodeIds.size} Episodes in $selectedQuality"
                } else {
                    "Select episodes to download"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun QueueDownloadsContent(
    downloads: List<DownloadEntry>,
    liveProgressMap: Map<String, DownloadManager.LiveProgress>,
    onPauseEpisode: (String) -> Unit,
    onResumeEpisode: (episodeId: String, quality: String?, audioLang: String?) -> Unit,
    onCancelEpisode: (String) -> Unit,
    onDeleteEpisode: (String) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit
) {
    val context = LocalContext.current

    if (downloads.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("No downloads for this series yet", color = TextMuted, fontSize = 13.sp)
        }
        return
    }

    val activeCount = downloads.count { it.downloadStatus == "DOWNLOADING" || it.downloadStatus == "PENDING_RESOLUTION" || it.downloadStatus == "WAITING_FOR_SOURCE" }
    val pausedCount = downloads.count { it.downloadStatus == "PAUSED" }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Quick Actions (Pause All / Resume All)
        if (activeCount > 0 || pausedCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (activeCount > 0) {
                    Surface(
                        onClick = onPauseAll,
                        shape = RoundedCornerShape(6.dp),
                        color = SurfaceHighlight,
                        modifier = Modifier.height(28.dp).testTag("pause_all_button")
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                            Text("Pause All", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                if (pausedCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        onClick = onResumeAll,
                        shape = RoundedCornerShape(6.dp),
                        color = AccentIndigo,
                        modifier = Modifier.height(28.dp).testTag("resume_all_button")
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                            Text("Resume All", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // List of all downloads
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(downloads, key = { it.id }) { item ->
                val live = liveProgressMap[item.id]
                val currentPct = if (live != null && live.totalBytes > 0) {
                    ((live.downloadedBytes.toFloat() / live.totalBytes) * 100).toInt()
                } else item.progressPercent

                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
                    border = BorderStroke(1.dp, SurfaceHighlight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ep ${item.episodeNumber}${if (!item.episodeTitle.isNullOrBlank()) ": ${item.episodeTitle}" else ""}",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Season ${item.seasonNumber} • ${item.quality ?: "Original"}",
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                when (item.downloadStatus) {
                                    "DOWNLOADING" -> {
                                        IconButton(
                                            onClick = { onPauseEpisode(item.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = AccentGold, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = { onCancelEpisode(item.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = ErrorRed, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    "PAUSED" -> {
                                        IconButton(
                                            onClick = { onResumeEpisode(item.id, item.quality, item.audioLanguage) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = AccentCyan, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = { onCancelEpisode(item.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = ErrorRed, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    "COMPLETED" -> {
                                        Icon(Icons.Default.DownloadDone, contentDescription = null, tint = PlayableGreen, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { onDeleteEpisode(item.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    "FAILED" -> {
                                        IconButton(
                                            onClick = { onResumeEpisode(item.id, item.quality, item.audioLanguage) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = ErrorRed, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = { onDeleteEpisode(item.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = TextMuted, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }

                        if (item.downloadStatus == "DOWNLOADING" || item.downloadStatus == "PENDING_RESOLUTION" || item.downloadStatus == "WAITING_FOR_SOURCE") {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (currentPct / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = AccentCyan,
                                trackColor = SpaceDark
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "$currentPct% • ${item.formattedSpeed}",
                                    color = AccentCyan,
                                    fontSize = 10.sp
                                )
                                Text(
                                    text = Formatter.formatFileSize(context, item.downloadedBytes),
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
