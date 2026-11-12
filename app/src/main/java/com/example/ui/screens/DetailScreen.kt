package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.statusBarsPadding
import com.example.data.download.DownloadManager
import com.example.data.local.DownloadEntry
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.local.EpisodeEntry
import com.example.data.remote.ProphecySourceDto
import com.example.ui.components.PlayableBadge
import com.example.ui.theme.AccentCoral
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentGold
import com.example.ui.theme.AccentIndigo
import com.example.ui.theme.PlayableGreen
import com.example.ui.theme.SpaceDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.SurfaceVariantDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodels.DetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBackClick: () -> Unit,
    onPlayEpisode: (String, String, String?) -> Unit // animeId, episodeId, preferredSourceId
) {
    val anime by viewModel.animeEntry.collectAsStateWithLifecycle()
    val isMovie = anime?.mediaCategory?.equals("MOVIE", ignoreCase = true) == true ||
            anime?.format?.equals("MOVIE", ignoreCase = true) == true ||
            anime?.format?.equals("FILM", ignoreCase = true) == true
    val seasons by viewModel.seasons.collectAsStateWithLifecycle()
    val selectedSeason by viewModel.selectedSeason.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val episodeSourceStates by viewModel.episodeSourceStates.collectAsStateWithLifecycle()
    val episodeWindowStart by viewModel.episodeWindowStart.collectAsStateWithLifecycle()
    val watchlistEntry by viewModel.watchlistEntry.collectAsStateWithLifecycle()
    val lastPlayed by viewModel.lastPlayed.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val syncError by viewModel.syncError.collectAsStateWithLifecycle()
    val catalogRefreshStatus by viewModel.catalogRefreshStatus.collectAsStateWithLifecycle()
    val canLoadNextEpisodePage by viewModel.canLoadNextEpisodePage.collectAsStateWithLifecycle()
    val canLoadPreviousEpisodePage by viewModel.canLoadPreviousEpisodePage.collectAsStateWithLifecycle()
    val selectedSeasonEpisodeCount by viewModel.selectedSeasonEpisodeCount.collectAsStateWithLifecycle()

    val selectedEpisodeForSources by viewModel.selectedEpisodeForSources.collectAsStateWithLifecycle()
    val episodeSources by viewModel.episodeSources.collectAsStateWithLifecycle()
    val isLoadingSources by viewModel.isLoadingSources.collectAsStateWithLifecycle()
    val sourceError by viewModel.sourceError.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val downloadManager = remember { DownloadManager.getInstance(context) }
    val downloads by remember(anime?.id) {
        if (anime != null) downloadManager.observeDownloadsForAnime(anime!!.id)
        else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadsByEpisode = remember(downloads) { downloads.associateBy { it.episodeId } }

    var isSynopsisExpanded by remember { mutableStateOf(false) }
    val authManager = remember { com.example.data.firebase.AuthManager.getInstance(context) }
    var showAccountAuthDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpaceDark)
            .testTag("detail_screen")
    ) {
        if (anime == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else {
            val item = anime!!

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                // Hero Banner & Poster Header
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                    ) {
                        val banner = item.bannerUrl ?: item.posterUrl
                        if (!banner.isNullOrBlank()) {
                            AsyncImage(
                                model = banner,
                                contentDescription = item.preferredTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(listOf(SurfaceHighlight, SurfaceDark))
                                    )
                            )
                        }

                        // Gradient fading downward
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Black.copy(alpha = 0.4f),
                                            SpaceDark.copy(alpha = 0.8f),
                                            SpaceDark
                                        )
                                    )
                                )
                        )

                        // Top Back Button
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier
                                .statusBarsPadding()
                                .padding(start = 8.dp, top = 8.dp)
                                .align(Alignment.TopStart)
                                .testTag("detail_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }

                        // Poster thumbnail & Main Title Info
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomStart)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Card(
                                modifier = Modifier
                                    .width(100.dp)
                                    .aspectRatio(0.70f)
                                    .clip(RoundedCornerShape(10.dp)),
                                shape = RoundedCornerShape(10.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                            ) {
                                AsyncImage(
                                    model = item.posterUrl,
                                    contentDescription = item.preferredTitle,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column {
                                PlayableBadge(isPlayable = item.isPlayable)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = item.preferredTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!item.titleRomaji.isNullOrBlank() && item.titleRomaji != item.preferredTitle) {
                                    Text(
                                        text = item.titleRomaji,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    item.score?.let { score ->
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = AccentGold,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = String.format("%.1f", score),
                                            color = TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                    }
                                    Text(
                                        text = "${item.format ?: "ANIME"} • ${item.year ?: ""}",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // Action Bar (Play First / Continue, Watchlist, Favorite)
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.isPlayable && episodes.isNotEmpty()) {
                            val firstOrResume = episodes.firstOrNull { it.id == lastPlayed?.episodeId }
                                ?: episodes.first()
                            Button(
                                onClick = { onPlayEpisode(item.id, firstOrResume.id, null) },
                                colors = ButtonDefaults.buttonColors(containerColor = PlayableGreen),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("play_primary_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isMovie) {
                                        if (lastPlayed != null) "Resume Movie" else "Play Movie"
                                    } else {
                                        if (lastPlayed != null) "Resume Ep ${lastPlayed?.episodeNumber}" else "Play Ep 1"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        // Watchlist Button
                        val isInWatchlist = watchlistEntry != null
                        OutlinedButton(
                            onClick = { viewModel.toggleWatchlist() },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (isInWatchlist) AccentCyan else TextSecondary
                            ),
                            modifier = Modifier
                                .height(44.dp)
                                .testTag("watchlist_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (isInWatchlist) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isInWatchlist) "Saved" else "Watchlist",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Favorite Heart
                        val isFav = watchlistEntry?.isFavorite == true
                        IconButton(
                            onClick = { viewModel.toggleFavorite() },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceVariantDark)
                                .testTag("favorite_button")
                        ) {
                            Icon(
                                imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Favorite",
                                tint = if (isFav) AccentCoral else TextSecondary
                            )
                        }
                    }
                }

                // Metadata Pills (Studio, Genres, Duration)
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        if (item.genres.isNotBlank()) {
                            Text(
                                text = item.genres,
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        if (!item.studios.isNullOrBlank()) {
                            Text(
                                text = "Studio: ${item.studios}",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Synopsis
                if (!item.synopsis.isNullOrBlank()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "Synopsis",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.synopsis.replace("<br>", "\n").replace("<i>", "").replace("</i>", ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                maxLines = if (isSynopsisExpanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { isSynopsisExpanded = !isSynopsisExpanded }
                            )
                            Text(
                                text = if (isSynopsisExpanded) "Show less" else "Read more",
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable { isSynopsisExpanded = !isSynopsisExpanded }
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }
                }

                // Seasons & Episodes Section
                if (item.isPlayable) {
                    // Dedicated Continue Watching Card when lastPlayed is available
                    if (lastPlayed != null) {
                        item {
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentIndigo),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .clickable { onPlayEpisode(item.id, lastPlayed!!.episodeId, null) }
                                    .testTag("detail_resume_card")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isMovie) "Continue Movie" else "Continue Watching",
                                            color = AccentCyan,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = if (isMovie) {
                                                lastPlayed!!.episodeTitle?.takeIf { it.isNotBlank() } ?: item.preferredTitle
                                            } else {
                                                "Season ${lastPlayed!!.seasonNumber} • Episode ${lastPlayed!!.episodeNumber}${if (!lastPlayed!!.episodeTitle.isNullOrBlank()) ": ${lastPlayed!!.episodeTitle}" else ""}"
                                            },
                                            color = TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val progress = if (lastPlayed!!.durationMs > 0) {
                                            (lastPlayed!!.positionMs.toFloat() / lastPlayed!!.durationMs.toFloat()).coerceIn(0f, 1f)
                                        } else 0f
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(SurfaceHighlight)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(progress)
                                                    .height(4.dp)
                                                    .background(PlayableGreen)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Button(
                                        onClick = { onPlayEpisode(item.id, lastPlayed!!.episodeId, null) },
                                        colors = ButtonDefaults.buttonColors(containerColor = PlayableGreen),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Resume", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            text = if (isMovie) "Movie Playback" else "Episodes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                        )
                    }

                    // Season Chips (only visible for series when seasons exist)
                    if (!isMovie && seasons.isNotEmpty()) {
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                items(seasons, key = { it.id }) { s ->
                                    FilterChip(
                                        selected = s.seasonNumber == selectedSeason,
                                        onClick = { viewModel.selectSeason(s.seasonNumber) },
                                        label = { Text("Season ${s.seasonNumber}") },
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

                    if (!isMovie && !catalogRefreshStatus.errorMessage.isNullOrBlank()) {
                        item {
                            Text(
                                text = catalogRefreshStatus.errorMessage.orEmpty(),
                                color = TextMuted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Episodes List
                    if (episodes.isEmpty()) {
                        item {
                            if (isLoading) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        CircularProgressIndicator(color = AccentCyan, modifier = Modifier.size(28.dp))
                                        Text(
                                            text = "Syncing episodes from catalog...",
                                            color = TextSecondary,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            } else if (syncError != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text("Catalog sync failed", color = TextPrimary, fontWeight = FontWeight.Bold)
                                            Text(syncError ?: "Unable to load episodes.", color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                                            Button(
                                                onClick = { viewModel.refreshDetails() },
                                                colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Retry", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                }
                            } else {
                                val emptyTitle = when {
                                    selectedSeasonEpisodeCount > 0 -> "No episodes in this page"
                                    catalogRefreshStatus.state == com.example.data.repository.EpisodeCatalogState.NOT_APPLICABLE -> "Standalone movie — no episode catalog required"
                                    catalogRefreshStatus.state == com.example.data.repository.EpisodeCatalogState.CONFIRMED_EMPTY -> "No episodes in catalog"
                                    catalogRefreshStatus.state == com.example.data.repository.EpisodeCatalogState.COMPLETE -> "No episodes in this season"
                                    catalogRefreshStatus.state == com.example.data.repository.EpisodeCatalogState.FAILED -> "Episode catalog could not be refreshed"
                                    catalogRefreshStatus.state == com.example.data.repository.EpisodeCatalogState.PARTIAL -> "Episode catalog is partial"
                                    else -> "Episode catalog not yet confirmed"
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = null,
                                                tint = AccentCoral,
                                                modifier = Modifier.size(32.dp)
                                            )
                                            Text(
                                                text = if (item.backendAnimeId == null) "Catalog Episodes Not Available" else emptyTitle,
                                                color = TextPrimary,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center
                                            )
                                            Text(
                                                text = if (catalogRefreshStatus.state == com.example.data.repository.EpisodeCatalogState.NOT_APPLICABLE) {
                                                    "This standalone movie has no episodic season catalog. Playback is offered only when the backend provides its real movie episode/source record; no synthetic episode is created."
                                                } else if (item.backendAnimeId == null) {
                                                    "Playable episodes for \"${item.preferredTitle}\" are not currently available in the streaming provider catalog.\n(Live Action was strictly excluded to prevent incorrect playback)."
                                                } else if (selectedSeasonEpisodeCount > 0) {
                                                    "No records are in the current page. ${if (catalogRefreshStatus.state == com.example.data.repository.EpisodeCatalogState.COMPLETE) "The selected season catalog is complete." else "More episodes may exist; catalog completeness is unknown."}"
                                                } else if (catalogRefreshStatus.state == com.example.data.repository.EpisodeCatalogState.CONFIRMED_EMPTY) {
                                                    "The current response explicitly confirmed an empty episode catalog."
                                                } else {
                                                    "Episodes for Season $selectedSeason are not available in the current local catalog. ${catalogRefreshStatus.errorMessage ?: "The catalog may be partial or unconfirmed."}"
                                                },
                                                color = TextMuted,
                                                fontSize = 12.sp,
                                                textAlign = TextAlign.Center,
                                                lineHeight = 17.sp
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            if (catalogRefreshStatus.state != com.example.data.repository.EpisodeCatalogState.NOT_APPLICABLE) Button(
                                                onClick = { viewModel.refreshDetails() },
                                                colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Retry Catalog Sync", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        items(episodes, key = { it.id }) { ep ->
                            val downloadEntry = downloadsByEpisode[ep.id]
                            EpisodeRow(
                                episode = ep,
                                isMovie = isMovie,
                                isPlayed = lastPlayed?.episodeId == ep.id,
                                downloadEntry = downloadEntry,
                                onPlayClick = {
                                    viewModel.focusEpisodeWindow(ep)
                                    onPlayEpisode(item.id, ep.id, null)
                                },
                                onSourcesClick = {
                                    viewModel.focusEpisodeWindow(ep)
                                    viewModel.openSourceChooser(ep)
                                },
                                sourceStateLabel = episodeSourceStates[ep.id]?.let { state ->
                                    when (state) {
                                        DetailViewModel.EpisodeSourceState.NOT_LOADED -> null
                                        DetailViewModel.EpisodeSourceState.LOADING -> "Sources · loading"
                                        DetailViewModel.EpisodeSourceState.AVAILABLE -> "Sources · available"
                                        DetailViewModel.EpisodeSourceState.EMPTY -> "Sources · none"
                                        DetailViewModel.EpisodeSourceState.PARTIAL -> "Sources · partial"
                                        DetailViewModel.EpisodeSourceState.FAILED -> "Sources · retry"
                                        DetailViewModel.EpisodeSourceState.TIMEOUT -> "Sources · timed out"
                                        DetailViewModel.EpisodeSourceState.UNKNOWN -> "Sources · unchecked"
                                        DetailViewModel.EpisodeSourceState.STALE -> "Sources · stale"
                                    }
                                },
                                onDownloadClick = {
                                    val eligibility = authManager.checkDownloadEligibility()
                                    when (eligibility) {
                                        is com.example.data.firebase.DownloadEligibility.RequiresLogin -> {
                                            authManager.setPendingDownload(
                                                com.example.data.firebase.PendingDownload(
                                                    animeId = item.id,
                                                    episodeId = ep.id,
                                                    seasonNumber = ep.seasonNumber,
                                                    episodeNumber = ep.episodeNumber,
                                                    episodeTitle = ep.title,
                                                    animeTitle = item.preferredTitle,
                                                    posterUrl = item.posterUrl,
                                                    mediaType = item.mediaCategory ?: "anime"
                                                )
                                            )
                                            showAccountAuthDialog = true
                                        }
                                        is com.example.data.firebase.DownloadEligibility.Suspended -> {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Downloads disabled: ${eligibility.reason}",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                        is com.example.data.firebase.DownloadEligibility.Eligible -> {
                                            downloadManager.startOrResumeDownload(
                                                animeId = item.id,
                                                episodeId = ep.id,
                                                seasonNumber = ep.seasonNumber,
                                                episodeNumber = ep.episodeNumber,
                                                episodeTitle = ep.title,
                                                animeTitle = item.preferredTitle,
                                                posterUrl = item.posterUrl,
                                                mediaType = item.mediaCategory ?: "anime"
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                    if (!isMovie) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    enabled = canLoadPreviousEpisodePage,
                                    onClick = { viewModel.loadPreviousEpisodeWindow() }
                                ) {
                                    Text("Previous episodes", color = if (canLoadPreviousEpisodePage) AccentCyan else TextMuted)
                                }
                                val firstVisibleRecord = if (episodes.isEmpty()) 0 else episodeWindowStart + 1
                                val lastVisibleRecord = episodeWindowStart + episodes.size
                                val totalLabel = if (catalogRefreshStatus.state == com.example.data.repository.EpisodeCatalogState.COMPLETE ||
                                    catalogRefreshStatus.state == com.example.data.repository.EpisodeCatalogState.CONFIRMED_EMPTY) {
                                    "of $selectedSeasonEpisodeCount"
                                } else {
                                    "of $selectedSeasonEpisodeCount known"
                                }
                                Text(
                                    text = if (episodes.isEmpty()) "No records · $selectedSeasonEpisodeCount known"
                                    else "Showing $firstVisibleRecord–$lastVisibleRecord $totalLabel",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                                TextButton(
                                    enabled = canLoadNextEpisodePage,
                                    onClick = { viewModel.loadNextEpisodeWindow() }
                                ) {
                                    Text("Next episodes", color = if (canLoadNextEpisodePage) AccentCyan else TextMuted)
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SurfaceVariantDark,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Catalog Metadata Only",
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "This title is currently listed for metadata and discovery only. Playable video sources are not yet authorized or linked in the ProphecyVerse catalog.",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Episode Sources Bottom Sheet
        if (selectedEpisodeForSources != null) {
            val ep = selectedEpisodeForSources!!
            val sheetState = rememberModalBottomSheetState()

            ModalBottomSheet(
                onDismissRequest = { viewModel.closeSourceChooser() },
                sheetState = sheetState,
                containerColor = SurfaceDark
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (isMovie) "Movie Sources" else "Episode ${ep.episodeNumber} Sources",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = if (isMovie) "Select a source candidate returned for this Movie" else "Select a source candidate returned for this Episode",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                        IconButton(onClick = { viewModel.closeSourceChooser() }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (isLoadingSources && episodeSources.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = AccentCyan)
                        }
                    } else if (episodeSources.isEmpty() && sourceError != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = sourceError!!,
                                color = AccentCoral,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                            TextButton(onClick = { viewModel.openSourceChooser(ep, forceRefresh = true) }) {
                                Text("Retry source lookup", color = AccentCyan)
                            }
                        }
                    } else {
                        if (isLoadingSources) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(color = AccentCyan, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("Refreshing source candidates…", color = TextMuted, fontSize = 12.sp)
                            }
                        }
                        sourceError?.let { message ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = message,
                                    color = if (episodeSources.isEmpty()) AccentCoral else TextMuted,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                                TextButton(onClick = { viewModel.openSourceChooser(ep, forceRefresh = true) }) {
                                    Text("Retry source lookup", color = AccentCyan)
                                }
                            }
                        }
                        if (episodeSources.isNotEmpty()) {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(episodeSources, key = { it.stableIdentity }) { src ->
                                    SourceCandidateCard(
                                        source = src,
                                        onClick = {
                                            viewModel.closeSourceChooser()
                                            onPlayEpisode(anime!!.id, ep.id, src.id)
                                        }
                                    )
                                }
                            }
                        } else if (!isLoadingSources) {
                            Text("No source candidates were returned; the API did not confirm that the list is empty.", color = TextMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (showAccountAuthDialog) {
            AccountDialog(onDismissRequest = { showAccountAuthDialog = false })
        }
    }
}

@Composable
fun EpisodeRow(
    episode: EpisodeEntry,
    isMovie: Boolean = false,
    isPlayed: Boolean,
    downloadEntry: DownloadEntry? = null,
    onPlayClick: () -> Unit,
    onSourcesClick: () -> Unit,
    sourceStateLabel: String? = null,
    onDownloadClick: () -> Unit = {}
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlayed) SurfaceHighlight else SurfaceVariantDark
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onPlayClick)
            .testTag(if (isMovie) "movie_row" else "episode_row_${episode.episodeNumber}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isPlayed) AccentCyan.copy(alpha = 0.2f) else SpaceDark,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isMovie) {
                            Icon(
                                imageVector = Icons.Default.Movie,
                                contentDescription = "Movie",
                                tint = if (isPlayed) AccentCyan else TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Text(
                                text = "${episode.episodeNumber}",
                                fontWeight = FontWeight.Bold,
                                color = if (isPlayed) AccentCyan else TextPrimary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = if (isMovie) (episode.title?.takeIf { it.isNotBlank() } ?: "Full Movie") else (episode.title ?: "Episode ${episode.episodeNumber}"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!episode.overview.isNullOrBlank()) {
                        Text(
                            text = episode.overview,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Download button / status
                when {
                    downloadEntry?.isCompleted == true -> {
                        Icon(
                            imageVector = Icons.Default.DownloadDone,
                            contentDescription = "Downloaded",
                            tint = PlayableGreen,
                            modifier = Modifier
                                .size(28.dp)
                                .padding(4.dp)
                        )
                    }
                    downloadEntry?.isDownloading == true -> {
                        CircularProgressIndicator(
                            color = AccentCyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(24.dp)
                                .padding(2.dp)
                        )
                    }
                    else -> {
                        IconButton(
                            onClick = onDownloadClick,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download",
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = sourceStateLabel ?: "Sources",
                    fontSize = 11.sp,
                    color = AccentIndigo,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onSourcesClick)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onPlayClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = PlayableGreen
                    )
                }
            }
        }
    }
}

@Composable
fun SourceCandidateCard(
    source: ProphecySourceDto,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = source.hasUsableSourceId, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = source.displayProviderName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    source.quality?.let { q ->
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = AccentIndigo.copy(alpha = 0.25f)
                        ) {
                            Text(
                                text = q,
                                color = AccentIndigo,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val codecStr = source.codec?.takeIf { it.isNotBlank() } ?: ""
                val langStr = source.displayAudioLanguage
                val sizeStr = source.sizeBytes?.let { "${it / 1_000_000} MB" }
                Text(
                    text = listOf(codecStr, langStr, sizeStr, source.downloadable?.let { if (it) "Downloadable" else "Stream only" })
                        .filterNotNull().filter { it.isNotBlank() }.joinToString(" • "),
                    color = TextMuted,
                    fontSize = 11.sp
                )
                if (!source.hasUsableSourceId) {
                    Text("Cannot play: backend source ID is missing.", color = AccentCoral, fontSize = 11.sp)
                }
            }

            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = if (source.hasUsableSourceId) "Play with this source" else "Source cannot be played because it has no ID",
                tint = if (source.hasUsableSourceId) PlayableGreen else TextMuted,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
