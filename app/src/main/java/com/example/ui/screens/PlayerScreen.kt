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
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import android.text.format.Formatter
import com.example.data.local.DownloadEntry
import com.example.data.download.DownloadManager
import com.example.player.PreResolveQueueManager
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
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
    val isMovie = anime?.mediaCategory?.equals("MOVIE", ignoreCase = true) == true ||
            anime?.format?.equals("MOVIE", ignoreCase = true) == true ||
            anime?.format?.equals("FILM", ignoreCase = true) == true
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

    val audioTracks by viewModel.unifiedAudioTracks.collectAsStateWithLifecycle()
    val subTracks by viewModel.playerManager.availableSubtitles.collectAsStateWithLifecycle()
    val qualities by viewModel.playerManager.availableQualities.collectAsStateWithLifecycle()

    // Season & Episode & Suggestions
    val seasons by viewModel.seasons.collectAsStateWithLifecycle()
    val selectedSeason by viewModel.selectedSeason.collectAsStateWithLifecycle()
    val seasonEpisodes by viewModel.seasonEpisodes.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val nextEpisode by viewModel.nextEpisode.collectAsStateWithLifecycle()

    // Download state below player
    val currentDownload by viewModel.currentDownload.collectAsStateWithLifecycle()
    val availableDownloadQualities by viewModel.availableDownloadQualities.collectAsStateWithLifecycle()
    val availableDownloadLanguages by viewModel.availableDownloadLanguages.collectAsStateWithLifecycle()
    val animeDownloads by viewModel.animeDownloads.collectAsStateWithLifecycle()
    val liveProgressMap by viewModel.liveProgressMap.collectAsStateWithLifecycle()
    val storageStats by viewModel.storageStats.collectAsStateWithLifecycle()
    val allEpisodes by viewModel.allEpisodes.collectAsStateWithLifecycle()
    val videoResolution by viewModel.playerManager.videoResolution.collectAsStateWithLifecycle()

    val authManager = remember { com.example.data.firebase.AuthManager.getInstance(context) }
    var showAccountAuthDialog by remember { mutableStateOf(false) }
    var showDownloadManagementSheet by remember { mutableStateOf(false) }
    var downloadSheetInitialTab by remember { mutableIntStateOf(0) }

    var areControlsVisible by remember { mutableStateOf(true) }
    var isControlsLocked by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var showTrackSettingsDialog by remember { mutableStateOf(false) }
    var activeSettingsTab by remember { mutableStateOf(0) } // 0: Sources, 1: Audio, 2: Subtitles, 3: Quality, 4: Speed
    var userIsSeeking by remember { mutableStateOf(false) }
    var seekSliderPos by remember { mutableStateOf(0f) }

    val preResolveStatus by viewModel.preResolveQueueStatus.collectAsStateWithLifecycle()
    var showTechInfoDialog by remember { mutableStateOf(false) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val activity = context as? Activity
    val window = activity?.window

    var brightnessLevel by remember { mutableStateOf<Float?>(null) }
    var showBrightnessHud by remember { mutableStateOf(false) }

    var volumeLevel by remember { mutableStateOf<Int?>(null) }
    var maxVolume by remember {
        mutableStateOf(audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15)
    }
    var showVolumeHud by remember { mutableStateOf(false) }

    var seekFeedbackText by remember { mutableStateOf<String?>(null) }
    var isSeekForward by remember { mutableStateOf(true) }
    var showSeekFeedback by remember { mutableStateOf(false) }

    // Restore window brightness on exit
    DisposableEffect(Unit) {
        val originalBrightness = window?.attributes?.screenBrightness ?: -1f
        onDispose {
            window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = originalBrightness
                w.attributes = lp
            }
        }
    }

    LaunchedEffect(showBrightnessHud) {
        if (showBrightnessHud) {
            delay(1500)
            showBrightnessHud = false
        }
    }

    LaunchedEffect(showVolumeHud) {
        if (showVolumeHud) {
            delay(1500)
            showVolumeHud = false
        }
    }

    LaunchedEffect(showSeekFeedback) {
        if (showSeekFeedback) {
            delay(1200)
            showSeekFeedback = false
        }
    }

    val onBrightnessChange: (Float) -> Unit = { delta ->
        window?.let { w ->
            val lp = w.attributes
            val cur = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
            val next = (cur + delta / 800f).coerceIn(0.01f, 1.0f)
            lp.screenBrightness = next
            w.attributes = lp
            brightnessLevel = next
            showBrightnessHud = true
        }
    }

    val onVolumeChange: (Float) -> Unit = { delta ->
        audioManager?.let { am ->
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            maxVolume = max
            if (max > 0 && delta != 0f) {
                val direction = if (delta > 0f) {
                    AudioManager.ADJUST_RAISE
                } else {
                    AudioManager.ADJUST_LOWER
                }
                // Use the same native operation as the physical volume buttons. Android
                // owns the system volume panel lifecycle and dismisses it normally.
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    direction,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            volumeLevel = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            showVolumeHud = true
        }
    }

    val onDoubleTapLeft = {
        viewModel.playerManager.seekBy(-10000)
        seekFeedbackText = "-10s"
        isSeekForward = false
        showSeekFeedback = true
    }

    val onDoubleTapRight = {
        viewModel.playerManager.seekBy(10000)
        seekFeedbackText = "+10s"
        isSeekForward = true
        showSeekFeedback = true
    }

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
                    viewModel.flushProgress()
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    if (!inPip) {
                        viewModel.playerManager.pause()
                    }
                    viewModel.flushProgress()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.flushProgress()
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
        ) {
            VideoSurface(playerInstance = playerInstance)

            if (!isInPipMode) {
                PlayerGestureLayer(
                    modifier = Modifier.fillMaxSize(),
                    isLocked = isControlsLocked,
                    onSingleTap = { areControlsVisible = !areControlsVisible },
                    onDoubleTapLeft = onDoubleTapLeft,
                    onDoubleTapRight = onDoubleTapRight,
                    onBrightnessChange = onBrightnessChange,
                    onVolumeChange = onVolumeChange,
                    onGestureEnd = {}
                )
            }

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
                    isMovie = isMovie,
                    nextEpisode = if (isMovie) null else nextEpisode,
                    onPlayNext = { viewModel.playNextEpisode() },
                    onReplay = {
                        viewModel.playerManager.seekTo(0L)
                        viewModel.playerManager.play()
                    }
                )
            }

            // HUD Indicators (Brightness, Volume, Seek Feedback)
            if (showBrightnessHud && !isInPipMode) {
                val pct = ((brightnessLevel ?: 0.5f) * 100).toInt()
                BrightnessHudIndicator(
                    percentage = pct,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (showVolumeHud && !isInPipMode) {
                val cur = volumeLevel ?: audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                val pct = if (maxVolume > 0) (cur * 100 / maxVolume) else 0
                VolumeHudIndicator(
                    percentage = pct,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (showSeekFeedback && !isInPipMode) {
                SeekFeedbackHUD(
                    text = seekFeedbackText ?: "",
                    isForward = isSeekForward,
                    modifier = Modifier
                        .align(if (isSeekForward) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(horizontal = 48.dp)
                )
            }

            // Overlay Controls
            PlayerControlsOverlay(
                isVisible = !isInPipMode && areControlsVisible && !isResolving && !isPlaybackEnded,
                isControlsLocked = isControlsLocked,
                isFullscreen = true,
                animeTitle = anime?.preferredTitle ?: "ProphecyVerse",
                currentEpisode = currentEpisode,
                isMovie = isMovie,
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
                onOpenTechInfo = { showTechInfoDialog = true },
                onEnterPip = onEnterPip,
                onOpenDownloadSheet = {
                    downloadSheetInitialTab = 0
                    showDownloadManagementSheet = true
                }
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
                    onSelectAudioTrack = { viewModel.selectAudioTrack(it) },
                    onSelectSubTrack = { viewModel.playerManager.selectTrack(it) },
                    onSelectQualityTrack = { viewModel.playerManager.selectTrack(it) },
                    onSetSpeed = { viewModel.playerManager.setSpeed(it) }
                )
            }

            if (!isInPipMode && showTechInfoDialog) {
                TechInfoDialog(
                    anime = anime,
                    currentEpisode = currentEpisode,
                    player = playerInstance,
                    activeSource = activeSource,
                    activeAudioDecoder = viewModel.playerManager.activeAudioDecoder.collectAsStateWithLifecycle().value,
                    onDismiss = { showTechInfoDialog = false }
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
            ) {
                VideoSurface(playerInstance = playerInstance)

                if (!isInPipMode) {
                    PlayerGestureLayer(
                        modifier = Modifier.fillMaxSize(),
                        isLocked = isControlsLocked,
                        onSingleTap = { areControlsVisible = !areControlsVisible },
                        onDoubleTapLeft = onDoubleTapLeft,
                        onDoubleTapRight = onDoubleTapRight,
                        onBrightnessChange = onBrightnessChange,
                        onVolumeChange = onVolumeChange,
                        onGestureEnd = {}
                    )
                }

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
                        isMovie = isMovie,
                        nextEpisode = if (isMovie) null else nextEpisode,
                        onPlayNext = { viewModel.playNextEpisode() },
                        onReplay = {
                            viewModel.playerManager.seekTo(0L)
                            viewModel.playerManager.play()
                        }
                    )
                }

                // HUD Indicators (Brightness, Volume, Seek Feedback)
                if (showBrightnessHud && !isInPipMode) {
                    val pct = ((brightnessLevel ?: 0.5f) * 100).toInt()
                    BrightnessHudIndicator(
                        percentage = pct,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                if (showVolumeHud && !isInPipMode) {
                    val cur = volumeLevel ?: audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                    val pct = if (maxVolume > 0) (cur * 100 / maxVolume) else 0
                    VolumeHudIndicator(
                        percentage = pct,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                if (showSeekFeedback && !isInPipMode) {
                    SeekFeedbackHUD(
                        text = seekFeedbackText ?: "",
                        isForward = isSeekForward,
                        modifier = Modifier
                            .align(if (isSeekForward) Alignment.CenterEnd else Alignment.CenterStart)
                            .padding(horizontal = 24.dp)
                    )
                }

                // Controls Overlay
                PlayerControlsOverlay(
                    isVisible = !isInPipMode && areControlsVisible && !isResolving && !isPlaybackEnded,
                    isControlsLocked = isControlsLocked,
                    isFullscreen = false,
                    animeTitle = anime?.preferredTitle ?: "ProphecyVerse",
                    currentEpisode = currentEpisode,
                    isMovie = isMovie,
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
                    onOpenTechInfo = { showTechInfoDialog = true },
                    onEnterPip = onEnterPip,
                    onOpenDownloadSheet = {
                        downloadSheetInitialTab = 0
                        showDownloadManagementSheet = true
                    }
                )
            }

            // Scrollable Content Below Video Player:
            // 1. Title & Current Episode Info + Compact Download Button (pop-up trigger)
            // 2. Manual Next Episode Card
            // 3. Season Selector (Feature 02)
            // 4. Episode Selector (Feature 02)
            // 5. Suggest for You (Feature 02)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Title and episode details header + Compact Download Button
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
                                text = if (isMovie) (ep.title?.takeIf { it.isNotBlank() } ?: "Full Movie") else "Season ${ep.seasonNumber} • Episode ${ep.episodeNumber}${if (!ep.title.isNullOrBlank()) ": ${ep.title}" else ""}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AccentCyan,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Resolution & Verified Status Badges
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = PlayableGreen.copy(alpha = 0.15f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = PlayableGreen, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Verified Stream", color = PlayableGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            val resDisplay = videoResolution ?: activeSource?.quality ?: "1080p Auto"
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = AccentCyan.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "Resolution: $resDisplay",
                                    color = AccentCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Compact Download Button Row (under player & title)
                        CompactDownloadActionRow(
                            download = currentDownload,
                            activeDownloadsCount = animeDownloads.count { it.downloadStatus == "DOWNLOADING" || it.downloadStatus == "PAUSED" },
                            onOpenDownloadSheet = {
                                val eligibility = authManager.checkDownloadEligibility()
                                when (eligibility) {
                                    is com.example.data.firebase.DownloadEligibility.RequiresLogin -> {
                                        currentEpisode?.let { ep ->
                                            authManager.setPendingDownload(
                                                com.example.data.firebase.PendingDownload(
                                                    animeId = anime?.id ?: "",
                                                    episodeId = ep.id,
                                                    seasonNumber = ep.seasonNumber,
                                                    episodeNumber = ep.episodeNumber,
                                                    episodeTitle = ep.title,
                                                    animeTitle = anime?.preferredTitle ?: "",
                                                    posterUrl = anime?.posterUrl,
                                                    mediaType = anime?.format ?: "anime",
                                                    preferredQuality = activeSource?.quality
                                                )
                                            )
                                        }
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
                                        downloadSheetInitialTab = 0
                                        showDownloadManagementSheet = true
                                    }
                                }
                            },
                            onOpenBatchSheet = {
                                val eligibility = authManager.checkDownloadEligibility()
                                when (eligibility) {
                                    is com.example.data.firebase.DownloadEligibility.RequiresLogin -> {
                                        currentEpisode?.let { ep ->
                                            authManager.setPendingDownload(
                                                com.example.data.firebase.PendingDownload(
                                                    animeId = anime?.id ?: "",
                                                    episodeId = ep.id,
                                                    seasonNumber = ep.seasonNumber,
                                                    episodeNumber = ep.episodeNumber,
                                                    episodeTitle = ep.title,
                                                    animeTitle = anime?.preferredTitle ?: "",
                                                    posterUrl = anime?.posterUrl,
                                                    mediaType = anime?.format ?: "anime"
                                                )
                                            )
                                        }
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
                                        downloadSheetInitialTab = 1
                                        showDownloadManagementSheet = true
                                    }
                                }
                            },
                            onOpenQueueSheet = {
                                downloadSheetInitialTab = 2
                                showDownloadManagementSheet = true
                            }
                        )
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
                if (seasons.isNotEmpty()) {
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

                // 4. Horizontal Episode List
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Episodes (Season $selectedSeason)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "${seasonEpisodes.size} Episodes",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (seasonEpisodes.isEmpty()) {
                            Text(
                                text = "No episodes loaded for this season",
                                color = TextMuted,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(seasonEpisodes, key = { it.id }) { ep ->
                                    val isCurrent = (currentEpisode?.id == ep.id)
                                    val qStatus = preResolveStatus[PreResolveQueueManager.identity(anime?.backendAnimeId ?: viewModel.animeId, ep)]
                                    HorizontalEpisodeCard(
                                        episode = ep,
                                        isCurrent = isCurrent,
                                        qStatus = qStatus,
                                        onClick = {
                                            if (!isCurrent) {
                                                viewModel.startPlaybackForEpisode(ep)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 5 & 6. Suggested Anime Section & Horizontal Poster Carousel
                if (suggestions.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Suggested Anime",
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
                    onSelectAudioTrack = { viewModel.selectAudioTrack(it) },
                    onSelectSubTrack = { viewModel.playerManager.selectTrack(it) },
                    onSelectQualityTrack = { viewModel.playerManager.selectTrack(it) },
                    onSetSpeed = { viewModel.playerManager.setSpeed(it) }
                )
            }

            if (!isInPipMode && showDownloadManagementSheet) {
                DownloadManagementBottomSheet(
                    animeTitle = anime?.preferredTitle ?: "ProphecyVerse",
                    selectedSeason = selectedSeason,
                    seasons = seasons.map { it.seasonNumber },
                    currentEpisode = currentEpisode,
                    allEpisodes = allEpisodes,
                    currentDownload = currentDownload,
                    animeDownloads = animeDownloads,
                    liveProgressMap = liveProgressMap,
                    availableQualities = availableDownloadQualities,
                    availableLanguages = availableDownloadLanguages,
                    storageStats = storageStats,
                    initialTab = downloadSheetInitialTab,
                    onDismiss = { showDownloadManagementSheet = false },
                    onStartSingleDownload = { q, l -> viewModel.startDownload(q, l) },
                    onStartBatchDownload = { eps, q, l -> viewModel.startBatchDownload(eps, q, l) },
                    onPauseDownload = { epId -> viewModel.pauseDownloadForEpisode(epId) },
                    onResumeDownload = { epId, q, l -> viewModel.resumeDownloadForEpisode(epId, q, l) },
                    onCancelDownload = { epId -> viewModel.cancelDownloadForEpisode(epId) },
                    onDeleteDownload = { epId -> viewModel.deleteDownloadForEpisode(epId) },
                    onPauseAll = { viewModel.pauseAllDownloads() },
                    onResumeAll = { viewModel.resumeAllDownloads() }
                )
            }

            if (showAccountAuthDialog) {
                AccountDialog(onDismissRequest = { showAccountAuthDialog = false })
            }
        }
    }
}

@Composable
fun HorizontalEpisodeCard(
    episode: EpisodeEntry,
    isCurrent: Boolean,
    qStatus: PreResolveQueueManager.ResolutionStatus?,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) SurfaceHighlight else SurfaceVariantDark
        ),
        border = if (isCurrent) BorderStroke(1.5.dp, AccentCyan) else null,
        modifier = Modifier
            .width(148.dp)
            .height(96.dp)
            .clickable(onClick = onClick)
            .testTag("player_ep_card_${episode.episodeNumber}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isCurrent) AccentCyan.copy(alpha = 0.25f) else SpaceDark
                ) {
                    Text(
                        text = "EP ${episode.episodeNumber}",
                        fontWeight = FontWeight.Bold,
                        color = if (isCurrent) AccentCyan else TextPrimary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (isCurrent) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Playing",
                        tint = PlayableGreen,
                        modifier = Modifier.size(16.dp)
                    )
                } else if (qStatus == PreResolveQueueManager.ResolutionStatus.VERIFIED_PLAYABLE) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = PlayableGreen.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "Ready",
                            color = PlayableGreen,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Text(
                text = episode.title ?: "Episode ${episode.episodeNumber}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) AccentCyan else TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 14.sp
            )

            if (isCurrent) {
                Text(
                    text = "Now Playing",
                    fontSize = 10.sp,
                    color = PlayableGreen,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    text = "Tap to play",
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
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
    isMovie: Boolean = false,
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
                    text = if (isMovie) "Movie Finished" else "Episode Finished",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isMovie) {
                        "Thank you for watching."
                    } else if (nextEpisode != null) {
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
    isMovie: Boolean = false,
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
    onOpenTechInfo: () -> Unit,
    onEnterPip: () -> Unit,
    onOpenDownloadSheet: () -> Unit = {}
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
                                text = if (isMovie) (ep.title?.takeIf { it.isNotBlank() } ?: "Full Movie") else "S${ep.seasonNumber} : E${ep.episodeNumber} • ${ep.title ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AccentCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onOpenTechInfo,
                            modifier = Modifier.testTag("player_tech_info_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Technical Info HUD",
                                tint = AccentGold
                            )
                        }
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
                            onClick = onOpenDownloadSheet,
                            modifier = Modifier.testTag("player_download_controls_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download Management",
                                tint = AccentCyan
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
                            isPlaying -> "${formatMillis(pos)} / Loading..."
                            else -> "${formatMillis(pos)} / Loading..."
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
                            val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
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

@Composable
private fun PlayerGestureLayer(
    modifier: Modifier = Modifier,
    isLocked: Boolean,
    onSingleTap: () -> Unit,
    onDoubleTapLeft: () -> Unit,
    onDoubleTapRight: () -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onGestureEnd: () -> Unit
) {
    var boxWidth by remember { mutableStateOf(1) }
    var isDraggingLeft by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .onSizeChanged { boxWidth = it.width.coerceAtLeast(1) }
            .pointerInput(isLocked) {
                if (!isLocked) {
                    detectTapGestures(
                        onTap = { onSingleTap() },
                        onDoubleTap = { offset ->
                            if (offset.x < boxWidth / 2f) {
                                onDoubleTapLeft()
                            } else {
                                onDoubleTapRight()
                            }
                        }
                    )
                } else {
                    detectTapGestures(onTap = { onSingleTap() })
                }
            }
            .pointerInput(isLocked) {
                if (!isLocked) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            isDraggingLeft = offset.x < boxWidth / 2f
                        },
                        onDragEnd = { onGestureEnd() },
                        onDragCancel = { onGestureEnd() },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val delta = -dragAmount
                            if (isDraggingLeft) {
                                onBrightnessChange(delta)
                            } else {
                                onVolumeChange(delta)
                            }
                        }
                    )
                }
            }
    )
}

@Composable
private fun BrightnessHudIndicator(
    percentage: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.75f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.BrightnessMedium,
                contentDescription = null,
                tint = AccentGold,
                modifier = Modifier.size(24.dp)
            )
            Column {
                Text(
                    text = "Brightness",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Text(
                    text = "$percentage%",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun VolumeHudIndicator(
    percentage: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.75f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(24.dp)
            )
            Column {
                Text(
                    text = "Media Volume",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Text(
                    text = "$percentage%",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun SeekFeedbackHUD(
    text: String,
    isForward: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (isForward) Icons.Default.FastForward else Icons.Default.FastRewind,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun TechInfoDialog(
    anime: AnimeEntry?,
    currentEpisode: EpisodeEntry?,
    player: androidx.media3.exoplayer.ExoPlayer?,
    activeSource: com.example.data.remote.ProphecySourceDto?,
    activeAudioDecoder: String?,
    onDismiss: () -> Unit
) {
    val videoFormat = player?.videoFormat
    val audioFormat = player?.audioFormat
    val bufferedSec = (player?.totalBufferedDuration ?: 0L) / 1000

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = AccentGold, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Technical Information HUD", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TechInfoRow("Title", anime?.preferredTitle ?: "Unknown")
                currentEpisode?.let { ep ->
                    TechInfoRow("Episode", "Season ${ep.seasonNumber} • Ep ${ep.episodeNumber}")
                }
                val resStr = if (videoFormat != null && videoFormat.width > 0 && videoFormat.height > 0) {
                    "${videoFormat.width}x${videoFormat.height} @ ${String.format("%.1f", videoFormat.frameRate.coerceAtLeast(0f))} fps"
                } else "Auto / Stream Default"
                TechInfoRow("Resolution", resStr)

                val vCodec = videoFormat?.sampleMimeType ?: videoFormat?.codecs ?: "H.264 / AVC"
                TechInfoRow("Video Codec", vCodec)

                val aCodec = audioFormat?.sampleMimeType ?: audioFormat?.codecs ?: "EAC3 / AAC"
                TechInfoRow("Audio Codec", aCodec)

                val isEac3 = aCodec.contains("eac3", ignoreCase = true) || aCodec.contains("eac3-joc", ignoreCase = true)
                val decText = when {
                    activeAudioDecoder != null -> activeAudioDecoder
                    isEac3 -> "FFmpeg Audio Renderer (Software)"
                    else -> "MediaCodec Hardware Audio Renderer"
                }
                TechInfoRow("Audio Decoder", decText)

                TechInfoRow("Buffer Health", "${bufferedSec}s buffered ahead")
                TechInfoRow("Source Provider", activeSource?.label ?: "ProphecyVerse Core Engine")
                TechInfoRow("Source ID", activeSource?.id ?: "direct_stream")
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo)
            ) {
                Text("Close", color = Color.White)
            }
        },
        containerColor = SurfaceDark
    )
}

@Composable
private fun TechInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(text = value, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable
fun DownloadSectionCard(
    download: DownloadEntry?,
    availableQualities: List<String>,
    availableLanguages: List<String>,
    onStartDownload: (quality: String?, audioLang: String?) -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetryDownload: () -> Unit,
    onDeleteDownload: () -> Unit
) {
    CompactDownloadActionRow(
        download = download,
        activeDownloadsCount = if (download?.downloadStatus == "DOWNLOADING") 1 else 0,
        onOpenDownloadSheet = { onStartDownload(null, null) },
        onOpenBatchSheet = {},
        onOpenQueueSheet = {}
    )
}
