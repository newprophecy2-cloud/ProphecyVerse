package com.example.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.data.local.AnimeEntry
import com.example.data.local.EpisodeEntry
import com.example.player.PlayerManager
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
import com.example.ui.viewmodels.PlayerViewModel
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    isInPipMode: Boolean = false,
    onEnterPip: () -> Unit = {},
    onPlayerStateChanged: (isActive: Boolean, isPlaying: Boolean) -> Unit = { _, _ -> },
    onBackClick: () -> Unit,
    onNavigateToDetail: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val anime by viewModel.animeEntry.collectAsStateWithLifecycle()
    val currentEpisode by viewModel.currentEpisode.collectAsStateWithLifecycle()
    val isResolving by viewModel.isResolving.collectAsStateWithLifecycle()
    val resolutionError by viewModel.resolutionError.collectAsStateWithLifecycle()

    val isPlaying by viewModel.playerManager.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by viewModel.playerManager.isBuffering.collectAsStateWithLifecycle()
    val currentPosition by viewModel.playerManager.currentPosition.collectAsStateWithLifecycle()
    val duration by viewModel.playerManager.duration.collectAsStateWithLifecycle()
    val playerError by viewModel.playerManager.errorMessage.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playerManager.playbackSpeed.collectAsStateWithLifecycle()
    val isPlaybackEnded by viewModel.playerManager.isPlaybackEnded.collectAsStateWithLifecycle()

    val playerInstance by viewModel.playerManager.exoPlayerInstance.collectAsStateWithLifecycle()

    val availableSources by viewModel.availableSources.collectAsStateWithLifecycle()
    val activeSource by viewModel.activeSource.collectAsStateWithLifecycle()

    val audioTracks by viewModel.playerManager.availableAudioTracks.collectAsStateWithLifecycle()
    val subTracks by viewModel.playerManager.availableSubtitles.collectAsStateWithLifecycle()
    val qualities by viewModel.playerManager.availableQualities.collectAsStateWithLifecycle()

    // Season & Episode & Suggestions
    val seasons by viewModel.seasons.collectAsStateWithLifecycle()
    val selectedSeason by viewModel.selectedSeason.collectAsStateWithLifecycle()
    val seasonEpisodes by viewModel.seasonEpisodes.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val nextEpisode by viewModel.nextEpisode.collectAsStateWithLifecycle()

    var areControlsVisible by remember { mutableStateOf(true) }
    var isControlsLocked by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var showTrackSettingsDialog by remember { mutableStateOf(false) }
    var activeSettingsTab by remember { mutableStateOf(0) } // 0: Sources, 1: Audio, 2: Subtitles, 3: Quality, 4: Speed
    var userIsSeeking by remember { mutableStateOf(false) }
    var seekSliderPos by remember { mutableStateOf(0f) }

    // Intercept back button when in fullscreen
    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
    }

    // Report playback status for PiP auto-enter
    LaunchedEffect(isPlaying) {
        onPlayerStateChanged(true, isPlaying)
    }

    DisposableEffect(Unit) {
        onDispose {
            onPlayerStateChanged(false, false)
        }
    }

    // Dismiss dialog and hide controls in PiP mode
    LaunchedEffect(isInPipMode) {
        if (isInPipMode) {
            showTrackSettingsDialog = false
            areControlsVisible = false
        }
    }

    // Auto-hide controls timer
    LaunchedEffect(areControlsVisible, isPlaying, userIsSeeking) {
        if (areControlsVisible && isPlaying && !userIsSeeking && !isControlsLocked) {
            delay(4000)
            areControlsVisible = false
        }
    }

    // Fullscreen screen orientation handling (respect PiP window)
    DisposableEffect(isFullscreen, isInPipMode) {
        val activity = context as? Activity
        val originalOrientation = activity?.requestedOrientation
        if (isFullscreen && !isInPipMode) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            if (originalOrientation != null) {
                activity.requestedOrientation = originalOrientation
            }
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            val activity = context as? Activity
            val inPip = activity?.isInPictureInPictureMode == true
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    if (!inPip) {
                        viewModel.playerManager.pause()
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    if (!inPip) {
                        viewModel.playerManager.pause()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.playerManager.release()
        }
    }

    // Main layout container
    if (isFullscreen) {
        // Fullscreen Landscape Video Player Box
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("player_screen_fullscreen")
                .then(
                    if (!isInPipMode) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            areControlsVisible = !areControlsVisible
                        }
                    } else Modifier
                )
        ) {
            VideoSurface(playerInstance = playerInstance)

            LoadingAndBufferingOverlay(
                isResolving = isResolving,
                isBuffering = isBuffering,
                isInPipMode = isInPipMode
            )

            ErrorOverlay(
                resolutionError = resolutionError,
                playerError = playerError,
                isResolving = isResolving,
                isInPipMode = isInPipMode,
                availableSources = availableSources,
                activeSource = activeSource,
                onRetry = { currentEpisode?.let { ep -> viewModel.startPlaybackForEpisode(ep) } },
                onSwitchSource = { viewModel.switchSource(it) }
            )

            // Manual Next Episode Overlay when playback completes (NO auto play)
            if (isPlaybackEnded && !isInPipMode) {
                PlaybackEndedOverlay(
                    nextEpisode = nextEpisode,
                    onPlayNext = { viewModel.playNextEpisode() },
                    onReplay = {
                        viewModel.playerManager.seekTo(0L)
                        viewModel.playerManager.play()
                    }
                )
            }

            // Overlay Controls
            PlayerControlsOverlay(
                isVisible = !isInPipMode && areControlsVisible && !isResolving && !isPlaybackEnded,
                isControlsLocked = isControlsLocked,
                isFullscreen = true,
                animeTitle = anime?.preferredTitle ?: "ProphecyVerse",
                currentEpisode = currentEpisode,
                isPlaying = isPlaying,
                isResolving = isResolving,
                isBuffering = isBuffering,
                duration = duration,
                currentPosition = currentPosition,
                playbackSpeed = playbackSpeed,
                audioTracksCount = audioTracks.size,
                userIsSeeking = userIsSeeking,
                seekSliderPos = seekSliderPos,
                onBack = { isFullscreen = false },
                onToggleLock = { isControlsLocked = !isControlsLocked },
                onTogglePlayPause = { viewModel.playerManager.togglePlayPause() },
                onRewind = { viewModel.playerManager.seekBy(-10000) },
                onForward = { viewModel.playerManager.seekBy(10000) },
                onNextEpisode = { viewModel.playNextEpisode() },
                onSeekChange = {
                    userIsSeeking = true
                    seekSliderPos = it
                },
                onSeekFinished = {
                    viewModel.playerManager.seekTo(seekSliderPos.toLong())
                    userIsSeeking = false
                },
                onSpeedChange = { nextSpd -> viewModel.playerManager.setSpeed(nextSpd) },
                onToggleFullscreen = { isFullscreen = false },
                onOpenAudioSettings = {
                    activeSettingsTab = 1
                    showTrackSettingsDialog = true
                },
                onOpenGeneralSettings = {
                    activeSettingsTab = if (availableSources.size > 1) 0 else if (audioTracks.size > 1) 1 else 3
                    showTrackSettingsDialog = true
                },
                onEnterPip = onEnterPip
            )

            if (!isInPipMode && showTrackSettingsDialog) {
                TrackSettingsDialog(
                    activeSettingsTab = activeSettingsTab,
                    onTabChange = { activeSettingsTab = it },
                    onDismiss = { showTrackSettingsDialog = false },
                    availableSources = availableSources,
                    activeSource = activeSource,
                    audioTracks = audioTracks,
                    subTracks = subTracks,
                    qualities = qualities,
                    playbackSpeed = playbackSpeed,
                    onSwitchSource = { viewModel.switchSource(it) },
                    onSelectAudioTrack = { viewModel.playerManager.selectTrack(it) },
                    onSelectSubTrack = { viewModel.playerManager.selectTrack(it) },
                    onSelectQualityTrack = { viewModel.playerManager.selectTrack(it) },
                    onSetSpeed = { viewModel.playerManager.setSpeed(it) }
                )
            }
        }
    } else {
        // Portrait Mode: Top 16:9 Video Player + Scrollable Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SpaceDark)
                .statusBarsPadding()
                .navigationBarsPadding()
                .testTag("player_screen_portrait")
        ) {
            // Video Player Box (Top)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
                    .then(
                        if (!isInPipMode) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                areControlsVisible = !areControlsVisible
                            }
                        } else Modifier
                    )
            ) {
                VideoSurface(playerInstance = playerInstance)

                LoadingAndBufferingOverlay(
                    isResolving = isResolving,
                    isBuffering = isBuffering,
                    isInPipMode = isInPipMode
                )

                ErrorOverlay(
                    resolutionError = resolutionError,
                    playerError = playerError,
                    isResolving = isResolving,
                    isInPipMode = isInPipMode,
                    availableSources = availableSources,
                    activeSource = activeSource,
                    onRetry = { currentEpisode?.let { ep -> viewModel.startPlaybackForEpisode(ep) } },
                    onSwitchSource = { viewModel.switchSource(it) }
                )

                // Manual Next Episode Overlay when playback completes (NO auto play)
                if (isPlaybackEnded && !isInPipMode) {
                    PlaybackEndedOverlay(
                        nextEpisode = nextEpisode,
                        onPlayNext = { viewModel.playNextEpisode() },
                        onReplay = {
                            viewModel.playerManager.seekTo(0L)
                            viewModel.playerManager.play()
                        }
                    )
                }

                // Controls Overlay
                PlayerControlsOverlay(
                    isVisible = !isInPipMode && areControlsVisible && !isResolving && !isPlaybackEnded,
                    isControlsLocked = isControlsLocked,
                    isFullscreen = false,
                    animeTitle = anime?.preferredTitle ?: "ProphecyVerse",
                    currentEpisode = currentEpisode,
                    isPlaying = isPlaying,
                    isResolving = isResolving,
                    isBuffering = isBuffering,
                    duration = duration,
                    currentPosition = currentPosition,
                    playbackSpeed = playbackSpeed,
                    audioTracksCount = audioTracks.size,
                    userIsSeeking = userIsSeeking,
                    seekSliderPos = seekSliderPos,
                    onBack = onBackClick,
                    onToggleLock = { isControlsLocked = !isControlsLocked },
                    onTogglePlayPause = { viewModel.playerManager.togglePlayPause() },
                    onRewind = { viewModel.playerManager.seekBy(-10000) },
                    onForward = { viewModel.playerManager.seekBy(10000) },
                    onNextEpisode = { viewModel.playNextEpisode() },
                    onSeekChange = {
                        userIsSeeking = true
                        seekSliderPos = it
                    },
                    onSeekFinished = {
                        viewModel.playerManager.seekTo(seekSliderPos.toLong())
                        userIsSeeking = false
                    },
                    onSpeedChange = { nextSpd -> viewModel.playerManager.setSpeed(nextSpd) },
                    onToggleFullscreen = { isFullscreen = true },
                    onOpenAudioSettings = {
                        activeSettingsTab = 1
                        showTrackSettingsDialog = true
                    },
                    onOpenGeneralSettings = {
                        activeSettingsTab = if (availableSources.size > 1) 0 else if (audioTracks.size > 1) 1 else 3
                        showTrackSettingsDialog = true
                    },
                    onEnterPip = onEnterPip
                )
            }

            // Scrollable Content Below Video Player:
            // 1. Title & Current Episode Info + Manual Next Episode Card
            // 2. Season Selector (Feature 02)
            // 3. Episode Selector (Feature 02)
            // 4. Suggest for You (Feature 02)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Title and episode details header
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = anime?.preferredTitle ?: "ProphecyVerse",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        currentEpisode?.let { ep ->
                            Text(
                                text = "Season ${ep.seasonNumber} • Episode ${ep.episodeNumber}${if (!ep.title.isNullOrBlank()) ": ${ep.title}" else ""}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AccentCyan,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Feature 03: Manual Next Episode action card (clickable anytime)
                if (nextEpisode != null) {
                    item {
                        val nextEp = nextEpisode!!
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
                            border = BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.playNextEpisode() }
                                .testTag("manual_next_episode_card")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = AccentIndigo.copy(alpha = 0.3f),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.SkipNext,
                                                contentDescription = null,
                                                tint = AccentCyan,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Up Next (Manual Click)",
                                            color = TextMuted,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "Ep ${nextEp.episodeNumber} • ${nextEp.title ?: "Episode ${nextEp.episodeNumber}"}",
                                            color = TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Button(
                                    onClick = { viewModel.playNextEpisode() },
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
                                    Text("Play Next", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Feature 02: Season Selector
                if (seasons.size > 1) {
                    item {
                        Column {
                            Text(
                                text = "Select Season",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(seasons, key = { it.id }) { s ->
                                    val isSelected = (s.seasonNumber == selectedSeason)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.selectSeason(s.seasonNumber) },
                                        label = { Text("Season ${s.seasonNumber}") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            containerColor = SurfaceVariantDark,
                                            labelColor = TextSecondary,
                                            selectedContainerColor = AccentIndigo,
                                            selectedLabelColor = Color.White
                                        ),
                                        border = null,
                                        modifier = Modifier.testTag("season_chip_${s.seasonNumber}")
                                    )
                                }
                            }
                        }
                    }
                }

                // Feature 02: Episode Selector for Selected Season
                item {
                    Text(
                        text = "Episodes (Season $selectedSeason)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                if (seasonEpisodes.isEmpty()) {
                    item {
                        Text(
                            text = "No episodes loaded for this season",
                            color = TextMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                } else {
                    items(seasonEpisodes, key = { it.id }) { ep ->
                        val isCurrent = (currentEpisode?.id == ep.id)
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent) SurfaceHighlight else SurfaceVariantDark
                            ),
                            border = if (isCurrent) BorderStroke(1.5.dp, AccentCyan) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!isCurrent) {
                                        viewModel.startPlaybackForEpisode(ep)
                                    }
                                }
                                .testTag("player_ep_row_${ep.episodeNumber}")
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
                                        color = if (isCurrent) AccentCyan.copy(alpha = 0.25f) else SpaceDark,
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${ep.episodeNumber}",
                                                fontWeight = FontWeight.Bold,
                                                color = if (isCurrent) AccentCyan else TextPrimary,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column {
                                        Text(
                                            text = ep.title ?: "Episode ${ep.episodeNumber}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                            color = if (isCurrent) AccentCyan else TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isCurrent) {
                                            Text(
                                                text = "Now Playing",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = PlayableGreen,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                        } else if (!ep.overview.isNullOrBlank()) {
                                            Text(
                                                text = ep.overview,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }

                                Icon(
                                    imageVector = if (isCurrent) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isCurrent) "Current" else "Play",
                                    tint = if (isCurrent) AccentCyan else PlayableGreen,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                // Feature 02: Suggest for You
                if (suggestions.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Suggest for You",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(suggestions, key = { it.id }) { sugg ->
                                PlayerSuggestionCard(
                                    anime = sugg,
                                    onClick = { onNavigateToDetail(sugg.id) }
                                )
                            }
                        }
                    }
                }
            }

            if (!isInPipMode && showTrackSettingsDialog) {
                TrackSettingsDialog(
                    activeSettingsTab = activeSettingsTab,
                    onTabChange = { activeSettingsTab = it },
                    onDismiss = { showTrackSettingsDialog = false },
                    availableSources = availableSources,
                    activeSource = activeSource,
                    audioTracks = audioTracks,
                    subTracks = subTracks,
                    qualities = qualities,
                    playbackSpeed = playbackSpeed,
                    onSwitchSource = { viewModel.switchSource(it) },
                    onSelectAudioTrack = { viewModel.playerManager.selectTrack(it) },
                    onSelectSubTrack = { viewModel.playerManager.selectTrack(it) },
                    onSelectQualityTrack = { viewModel.playerManager.selectTrack(it) },
                    onSetSpeed = { viewModel.playerManager.setSpeed(it) }
                )
            }
        }
    }
}

@Composable
private fun VideoSurface(playerInstance: androidx.media3.exoplayer.ExoPlayer?) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                keepScreenOn = true
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                player = playerInstance
            }
        },
        update = { playerView ->
            if (playerView.player != playerInstance) {
                playerView.player = playerInstance
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun LoadingAndBufferingOverlay(
    isResolving: Boolean,
    isBuffering: Boolean,
    isInPipMode: Boolean
) {
    if ((isResolving || isBuffering) && !isInPipMode) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (isResolving) 0.8f else 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = AccentCyan, strokeWidth = 3.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (isResolving) "Resolving direct playback..." else "Buffering...",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ErrorOverlay(
    resolutionError: String?,
    playerError: String?,
    isResolving: Boolean,
    isInPipMode: Boolean,
    availableSources: List<com.example.data.remote.ProphecySourceDto>,
    activeSource: com.example.data.remote.ProphecySourceDto?,
    onRetry: () -> Unit,
    onSwitchSource: (String) -> Unit
) {
    val activeError = resolutionError ?: playerError
    if (activeError != null && !isResolving && !isInPipMode) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = ErrorRed.copy(alpha = 0.2f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = ErrorRed,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = activeError,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Retry Playback")
                }

                if (availableSources.size > 1) {
                    val nextSource = availableSources.firstOrNull { it.id != activeSource?.id }
                    if (nextSource != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { onSwitchSource(nextSource.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceHighlight)
                        ) {
                            Text("Try Alternate Source (${nextSource.quality ?: nextSource.label ?: "Next"})")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackEndedOverlay(
    nextEpisode: EpisodeEntry?,
    onPlayNext: () -> Unit,
    onReplay: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, AccentIndigo),
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 400.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Episode Finished",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (nextEpisode != null) {
                        "Next: Episode ${nextEpisode.episodeNumber}${if (!nextEpisode.title.isNullOrBlank()) " - ${nextEpisode.title}" else ""}"
                    } else {
                        "You've reached the end of this season."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(18.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onReplay,
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceHighlight)
                    ) {
                        Icon(imageVector = Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Replay")
                    }

                    if (nextEpisode != null) {
                        Button(
                            onClick = onPlayNext,
                            colors = ButtonDefaults.buttonColors(containerColor = PlayableGreen),
                            modifier = Modifier.testTag("playback_ended_play_next_button")
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Play Next Ep", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerControlsOverlay(
    isVisible: Boolean,
    isControlsLocked: Boolean,
    isFullscreen: Boolean,
    animeTitle: String,
    currentEpisode: EpisodeEntry?,
    isPlaying: Boolean,
    isResolving: Boolean,
    isBuffering: Boolean,
    duration: Long,
    currentPosition: Long,
    playbackSpeed: Float,
    audioTracksCount: Int,
    userIsSeeking: Boolean,
    seekSliderPos: Float,
    onBack: () -> Unit,
    onToggleLock: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onNextEpisode: () -> Unit,
    onSeekChange: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenAudioSettings: () -> Unit,
    onOpenGeneralSettings: () -> Unit,
    onEnterPip: () -> Unit
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Gradient Scrims
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.90f))))
            )

            if (isControlsLocked) {
                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Unlock controls",
                        tint = AccentCyan
                    )
                }
            } else {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = animeTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        currentEpisode?.let { ep ->
                            Text(
                                text = "S${ep.seasonNumber} : E${ep.episodeNumber} • ${ep.title ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AccentCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (audioTracksCount > 0) {
                            IconButton(
                                onClick = onOpenAudioSettings,
                                modifier = Modifier.testTag("player_audio_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Audiotrack,
                                    contentDescription = "Audio Tracks",
                                    tint = AccentCyan
                                )
                            }
                        }
                        IconButton(
                            onClick = onOpenGeneralSettings,
                            modifier = Modifier.testTag("player_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Tracks & Quality",
                                tint = Color.White
                            )
                        }
                        IconButton(
                            onClick = onEnterPip,
                            modifier = Modifier.testTag("player_pip_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPictureAlt,
                                contentDescription = "Picture in picture",
                                tint = AccentCyan
                            )
                        }
                        IconButton(onClick = onToggleLock) {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = "Lock controls",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Center Controls
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onRewind,
                        modifier = Modifier
                            .size(46.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastRewind,
                            contentDescription = "Rewind 10s",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(60.dp)
                            .background(AccentIndigo, CircleShape)
                            .testTag("player_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(
                        onClick = onForward,
                        modifier = Modifier
                            .size(46.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "Forward 10s",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = onNextEpisode,
                        modifier = Modifier
                            .size(42.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Episode",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Bottom Seek Bar & Fullscreen
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    val dur = duration.coerceAtLeast(0L)
                    val pos = if (userIsSeeking) seekSliderPos.toLong() else currentPosition.coerceAtLeast(0L)
                    val maxPos = if (dur > 0) dur.toFloat() else 1f

                    Slider(
                        value = pos.toFloat().coerceIn(0f, maxPos),
                        onValueChange = onSeekChange,
                        onValueChangeFinished = onSeekFinished,
                        enabled = dur > 0,
                        valueRange = 0f..maxPos,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentCyan,
                            activeTrackColor = AccentIndigo,
                            inactiveTrackColor = SurfaceHighlight
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val timeText = when {
                            dur > 0 -> "${formatMillis(pos)} / ${formatMillis(dur)}"
                            isResolving || isBuffering -> "${formatMillis(pos)} / Loading..."
                            isPlaying -> "${formatMillis(pos)} / Live"
                            else -> "${formatMillis(pos)} / --:--"
                        }
                        Text(
                            text = timeText,
                            color = TextSecondary,
                            fontSize = 11.sp
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${playbackSpeed}x",
                                color = AccentCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        val nextSpeed = when (playbackSpeed) {
                                            1.0f -> 1.25f
                                            1.25f -> 1.5f
                                            1.5f -> 2.0f
                                            0.75f -> 1.0f
                                            else -> 1.0f
                                        }
                                        onSpeedChange(nextSpeed)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = onToggleFullscreen,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = "Toggle Fullscreen",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerSuggestionCard(
    anime: AnimeEntry,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
        modifier = Modifier
            .width(110.dp)
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.70f)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            ) {
                AsyncImage(
                    model = anime.posterUrl,
                    contentDescription = anime.preferredTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                anime.score?.let { sc ->
                    Surface(
                        shape = RoundedCornerShape(bottomStart = 6.dp),
                        color = Color.Black.copy(alpha = 0.75f),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = AccentGold,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = String.format("%.1f", sc),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Text(
                text = anime.preferredTitle,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun TrackSettingsDialog(
    activeSettingsTab: Int,
    onTabChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    availableSources: List<com.example.data.remote.ProphecySourceDto>,
    activeSource: com.example.data.remote.ProphecySourceDto?,
    audioTracks: List<PlayerManager.TrackOption>,
    subTracks: List<PlayerManager.TrackOption>,
    qualities: List<PlayerManager.TrackOption>,
    playbackSpeed: Float,
    onSwitchSource: (String) -> Unit,
    onSelectAudioTrack: (PlayerManager.TrackOption) -> Unit,
    onSelectSubTrack: (PlayerManager.TrackOption) -> Unit,
    onSelectQualityTrack: (PlayerManager.TrackOption) -> Unit,
    onSetSpeed: (Float) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                onDismiss()
            },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(20.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth(0.92f)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .testTag("track_settings_dialog"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, Color(0xFF2A344D)),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Tune, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Playback & Stream Options",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextMuted, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceVariantDark, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val tabs = listOf("Sources", "Audio", "Subtitles", "Quality", "Speed")
                    tabs.forEachIndexed { index, tabTitle ->
                        val isSelected = activeSettingsTab == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) AccentIndigo else Color.Transparent)
                                .clickable { onTabChange(index) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tabTitle,
                                color = if (isSelected) Color.White else TextSecondary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    when (activeSettingsTab) {
                        0 -> {
                            if (availableSources.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(text = "Default direct source active", color = TextMuted, fontSize = 13.sp)
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(availableSources, key = { it.id }) { src ->
                                        val isSelected = (src.id == activeSource?.id)
                                        val label = buildString {
                                            append(src.label ?: "Direct Source")
                                            if (!src.quality.isNullOrBlank()) append(" • ").append(src.quality)
                                            if (!src.codec.isNullOrBlank()) append(" • ").append(src.codec)
                                        }
                                        TrackSelectionRow(
                                            label = label,
                                            isSelected = isSelected,
                                            onClick = {
                                                onSwitchSource(src.id)
                                                onDismiss()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        1 -> {
                            if (audioTracks.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(text = "Default stream audio active", color = TextMuted, fontSize = 13.sp)
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(audioTracks, key = { it.id }) { audio ->
                                        TrackSelectionRow(
                                            label = audio.label,
                                            isSelected = audio.isSelected,
                                            onClick = {
                                                onSelectAudioTrack(audio)
                                                onDismiss()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        2 -> {
                            if (subTracks.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(text = "No embedded subtitles found", color = TextMuted, fontSize = 13.sp)
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(subTracks, key = { it.id }) { sub ->
                                        TrackSelectionRow(
                                            label = sub.label,
                                            isSelected = sub.isSelected,
                                            onClick = {
                                                onSelectSubTrack(sub)
                                                onDismiss()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        3 -> {
                            if (qualities.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(text = "Auto / Stream Default", color = TextMuted, fontSize = 13.sp)
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(qualities, key = { it.id }) { q ->
                                        TrackSelectionRow(
                                            label = q.label,
                                            isSelected = q.isSelected,
                                            onClick = {
                                                onSelectQualityTrack(q)
                                                onDismiss()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        4 -> {
                            val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(speeds, key = { it.toString() }) { speed ->
                                    val isSelected = (playbackSpeed == speed)
                                    TrackSelectionRow(
                                        label = if (speed == 1.0f) "1.0x (Normal)" else "${speed}x",
                                        isSelected = isSelected,
                                        onClick = {
                                            onSetSpeed(speed)
                                            onDismiss()
                                        }
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

@Composable
fun TrackSelectionRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) SurfaceHighlight else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (isSelected) AccentCyan else TextPrimary,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = AccentCyan,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun formatMillis(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
