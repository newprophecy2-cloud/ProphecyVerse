package com.example.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.example.player.PlayerManager
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
import com.example.ui.viewmodels.PlayerViewModel
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    isInPipMode: Boolean = false,
    onEnterPip: () -> Unit = {},
    onPlayerStateChanged: (isActive: Boolean, isPlaying: Boolean) -> Unit = { _, _ -> },
    onBackClick: () -> Unit
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

    val playerInstance by viewModel.playerManager.exoPlayerInstance.collectAsStateWithLifecycle()

    val availableSources by viewModel.availableSources.collectAsStateWithLifecycle()
    val activeSource by viewModel.activeSource.collectAsStateWithLifecycle()

    val audioTracks by viewModel.playerManager.availableAudioTracks.collectAsStateWithLifecycle()
    val subTracks by viewModel.playerManager.availableSubtitles.collectAsStateWithLifecycle()
    val qualities by viewModel.playerManager.availableQualities.collectAsStateWithLifecycle()

    var areControlsVisible by remember { mutableStateOf(true) }
    var isControlsLocked by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var showTrackSettingsDialog by remember { mutableStateOf(false) }
    var activeSettingsTab by remember { mutableStateOf(0) } // 0: Sources, 1: Audio, 2: Subtitles, 3: Quality, 4: Speed
    var userIsSeeking by remember { mutableStateOf(false) }
    var seekSliderPos by remember { mutableStateOf(0f) }

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
                    // Do not pause if transitioning into or playing in PiP
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("player_screen")
            .then(
                if (!isInPipMode) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        areControlsVisible = !areControlsVisible
                    }
                } else {
                    Modifier
                }
            )
    ) {
        // Media3 ExoPlayer Video Surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false // Use custom Compose controls
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

        // Loading or Resolution indicator
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

        // Error display with retry button
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
                        onClick = {
                            currentEpisode?.let { ep ->
                                viewModel.startPlaybackForEpisode(ep)
                            }
                        },
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
                                onClick = {
                                    viewModel.switchSource(nextSource.id)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceHighlight)
                            ) {
                                Text("Try Alternate Source (${nextSource.quality ?: nextSource.label ?: "Next"})")
                            }
                        }
                    }
                }
            }
        }

        // Custom Overlay Controls
        AnimatedVisibility(
            visible = !isInPipMode && areControlsVisible && !isResolving,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top & Bottom gradient scrims
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.90f))
                            )
                        )
                )

                if (isControlsLocked) {
                    // Lock icon only when locked
                    IconButton(
                        onClick = { isControlsLocked = false },
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
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBackClick) {
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
                                text = anime?.preferredTitle ?: "ProphecyVerse",
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
                            if (audioTracks.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        activeSettingsTab = 1
                                        showTrackSettingsDialog = true
                                    },
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
                                onClick = {
                                    activeSettingsTab = if (availableSources.size > 1) 0 else if (audioTracks.size > 1) 1 else if (qualities.isNotEmpty()) 3 else 0
                                    showTrackSettingsDialog = true
                                },
                                modifier = Modifier.testTag("player_settings_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Tracks & Quality",
                                    tint = Color.White
                                )
                            }
                            // PiP Mode button
                            IconButton(
                                onClick = { onEnterPip() },
                                modifier = Modifier.testTag("player_pip_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PictureInPictureAlt,
                                    contentDescription = "Picture in picture",
                                    tint = AccentCyan
                                )
                            }
                            IconButton(onClick = { isControlsLocked = true }) {
                                Icon(
                                    imageVector = Icons.Default.LockOpen,
                                    contentDescription = "Lock controls",
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    // Center Playback Controls
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rewind 10s
                        IconButton(
                            onClick = { viewModel.playerManager.seekBy(-10000) },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastRewind,
                                contentDescription = "Rewind 10s",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // Play/Pause button
                        IconButton(
                            onClick = { viewModel.playerManager.togglePlayPause() },
                            modifier = Modifier
                                .size(64.dp)
                                .background(AccentIndigo, CircleShape)
                                .testTag("player_play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        // Forward 10s
                        IconButton(
                            onClick = { viewModel.playerManager.seekBy(10000) },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastForward,
                                contentDescription = "Forward 10s",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // Next episode
                        IconButton(
                            onClick = { viewModel.playNextEpisode() },
                            modifier = Modifier
                                .size(44.dp)
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

                    // Bottom Seek & Navigation Bar
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // Time & Slider
                        val dur = duration.coerceAtLeast(0L)
                        val pos = if (userIsSeeking) seekSliderPos.toLong() else currentPosition.coerceAtLeast(0L)
                        val maxPos = if (dur > 0) dur.toFloat() else 1f

                        Slider(
                            value = pos.toFloat().coerceIn(0f, maxPos),
                            onValueChange = {
                                if (dur > 0) {
                                    userIsSeeking = true
                                    seekSliderPos = it
                                }
                            },
                            onValueChangeFinished = {
                                if (dur > 0) {
                                    viewModel.playerManager.seekTo(seekSliderPos.toLong())
                                    userIsSeeking = false
                                }
                            },
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
                                fontSize = 12.sp
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Speed button
                                Text(
                                    text = "${playbackSpeed}x",
                                    color = AccentCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            val nextSpeed = when (playbackSpeed) {
                                                1.0f -> 1.25f
                                                1.25f -> 1.5f
                                                1.5f -> 2.0f
                                                2.0f -> 0.75f
                                                else -> 1.0f
                                            }
                                            viewModel.playerManager.setSpeed(nextSpeed)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                // Fullscreen toggle
                                IconButton(
                                    onClick = { isFullscreen = !isFullscreen },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                        contentDescription = "Toggle Fullscreen",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // In-Player Settings Overlay (Audio, Subtitles, Quality, Speed)
        AnimatedVisibility(
            visible = !isInPipMode && showTrackSettingsDialog,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showTrackSettingsDialog = false
                    },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .padding(20.dp)
                        .widthIn(max = 480.dp)
                        .fillMaxWidth(0.92f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* Consume click inside dialog card */ }
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
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = AccentCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Playback & Stream Options",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                            IconButton(
                                onClick = { showTrackSettingsDialog = false },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = TextMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Tab selectors
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
                                        .clickable { activeSettingsTab = index }
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

                        // Tab Content
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        ) {
                            when (activeSettingsTab) {
                                0 -> { // Sources
                                    if (availableSources.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Default direct source active",
                                                color = TextMuted,
                                                fontSize = 13.sp
                                            )
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
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
                                                        viewModel.switchSource(src.id)
                                                        showTrackSettingsDialog = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                1 -> { // Audio
                                    if (audioTracks.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Default stream audio active",
                                                color = TextMuted,
                                                fontSize = 13.sp
                                            )
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            items(audioTracks, key = { it.id }) { audio ->
                                                TrackSelectionRow(
                                                    label = audio.label,
                                                    isSelected = audio.isSelected,
                                                    onClick = {
                                                        viewModel.playerManager.selectTrack(audio)
                                                        showTrackSettingsDialog = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                2 -> { // Subtitles
                                    if (subTracks.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "No embedded subtitles found",
                                                color = TextMuted,
                                                fontSize = 13.sp
                                            )
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            items(subTracks, key = { it.id }) { sub ->
                                                TrackSelectionRow(
                                                    label = sub.label,
                                                    isSelected = sub.isSelected,
                                                    onClick = {
                                                        viewModel.playerManager.selectTrack(sub)
                                                        showTrackSettingsDialog = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                3 -> { // Quality
                                    if (qualities.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Auto / Stream Default",
                                                color = TextMuted,
                                                fontSize = 13.sp
                                            )
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            items(qualities, key = { it.id }) { q ->
                                                TrackSelectionRow(
                                                    label = q.label,
                                                    isSelected = q.isSelected,
                                                    onClick = {
                                                        viewModel.playerManager.selectTrack(q)
                                                        showTrackSettingsDialog = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                4 -> { // Speed
                                    val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        items(speeds, key = { it.toString() }) { speed ->
                                            val isSelected = (playbackSpeed == speed)
                                            TrackSelectionRow(
                                                label = if (speed == 1.0f) "1.0x (Normal)" else "${speed}x",
                                                isSelected = isSelected,
                                                onClick = {
                                                    viewModel.playerManager.setSpeed(speed)
                                                    showTrackSettingsDialog = false
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
