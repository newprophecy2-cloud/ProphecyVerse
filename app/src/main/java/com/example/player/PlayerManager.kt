package com.example.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.example.data.remote.DirectPlayData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(UnstableApi::class)
class PlayerManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var exoPlayer: ExoPlayer? = null
    private var tickerJob: Job? = null
    private var playbackGeneration: Long = 0L
    private var activeSession: PlaybackSessionIdentity? = null
    private var networkTracker: PlaybackNetworkTracker? = null

    private val _exoPlayerInstance = MutableStateFlow<ExoPlayer?>(null)
    val exoPlayerInstance: StateFlow<ExoPlayer?> = _exoPlayerInstance.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _availableAudioTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val availableAudioTracks: StateFlow<List<TrackOption>> = _availableAudioTracks.asStateFlow()

    private val _availableSubtitles = MutableStateFlow<List<TrackOption>>(emptyList())
    val availableSubtitles: StateFlow<List<TrackOption>> = _availableSubtitles.asStateFlow()

    private val _availableQualities = MutableStateFlow<List<TrackOption>>(emptyList())
    val availableQualities: StateFlow<List<TrackOption>> = _availableQualities.asStateFlow()

    private val _activeAudioDecoder = MutableStateFlow<String?>(null)
    val activeAudioDecoder: StateFlow<String?> = _activeAudioDecoder.asStateFlow()

    private val _playbackDiagnostics = MutableStateFlow(PlaybackDiagnostics())
    val playbackDiagnostics: StateFlow<PlaybackDiagnostics> = _playbackDiagnostics.asStateFlow()

    fun clearTrackSelections() {
        _availableAudioTracks.value = emptyList()
        _availableSubtitles.value = emptyList()
        _availableQualities.value = emptyList()
        _activeAudioDecoder.value = null
        _videoResolution.value = null
        _playbackDiagnostics.value = _playbackDiagnostics.value.copy(
            videoResolution = null,
            activeAudioDecoder = null
        )
    }

    fun clearErrorState() {
        _errorMessage.value = null
        _isBuffering.value = false
        _isPlaybackEnded.value = false
    }

    private val _videoResolution = MutableStateFlow<String?>(null)
    val videoResolution: StateFlow<String?> = _videoResolution.asStateFlow()

    private val _isPlaybackEnded = MutableStateFlow(false)
    val isPlaybackEnded: StateFlow<Boolean> = _isPlaybackEnded.asStateFlow()

    private var retryCount = 0
    private var hasUnsupportedTrackError = false
    private var onRetryRequested: (() -> Unit)? = null
    private var onPlaybackFailedCallback: (() -> Unit)? = null
    private var lastWorkingTrackSelectionParams: androidx.media3.common.TrackSelectionParameters? = null
    var onPlaybackEndedCallback: (() -> Unit)? = null
    data class PlaybackSessionIdentity(
        val animeId: String,
        val seasonNumber: Int,
        val episodeId: String,
        val generation: Long
    )

    var onPositionUpdate: ((session: PlaybackSessionIdentity, positionMs: Long, durationMs: Long) -> Unit)? = null

    data class TrackOption(
        val id: String,
        val label: String,
        val isSelected: Boolean,
        val trackGroup: TrackGroup? = null,
        val trackIndex: Int = 0,
        val sampleMimeType: String? = null,
        val isEac3: Boolean = false,
        val decoderType: String? = null,
        val isSupported: Boolean = true
    )

    enum class PlaybackFailureCategory {
        HTTP, NETWORK_TIMEOUT, NETWORK, CONTENT_TYPE, CONTAINER, AUDIO_DECODER, DECODER, MANIFEST, OTHER
    }

    data class PlaybackDiagnostics(
        val providerName: String? = null,
        val sourceQuality: String? = null,
        val urlType: String? = null,
        val contentType: String? = null,
        val resolveDurationMs: Long = 0L,
        val videoResolution: String? = null,
        val activeAudioDecoder: String? = null,
        val failureCategory: PlaybackFailureCategory? = null,
        val lastErrorMessage: String? = null,
        val ttfbMs: Long? = null,
        val bytesTransferred: Long = 0L,
        val throughputKbps: Long? = null,
        val lastHttpStatusCode: Int? = null,
        val supportsByteRanges: Boolean? = null,
        val retryCount: Int = 0
    )

    fun initializePlayer(
        directData: DirectPlayData,
        startPositionMs: Long = 0L,
        onPlaybackFailed: (() -> Unit)? = null,
        onRetryNeeded: () -> Unit,
        providerName: String? = null,
        sessionIdentity: PlaybackSessionIdentity? = null,
        sourceQuality: String? = null,
        resolveDurationMs: Long = 0L
    ): ExoPlayer {
        release()
        val cleanPlaybackUrl = directData.playbackUrl.trim()
        val generation = playbackGeneration
        activeSession = sessionIdentity?.copy(generation = generation)
        onRetryRequested = onRetryNeeded
        onPlaybackFailedCallback = onPlaybackFailed
        retryCount = 0
        hasUnsupportedTrackError = false
        _errorMessage.value = null
        _activeAudioDecoder.value = null
        _isPlaybackEnded.value = false
        _isBuffering.value = true
        _duration.value = 0L
        _currentPosition.value = startPositionMs
        val providerDiagnostic = providerName?.replace(Regex("[\\r\\n\\t]"), " ")?.take(64) ?: "unknown"
        _playbackDiagnostics.value = PlaybackDiagnostics(
            providerName = providerDiagnostic,
            sourceQuality = sourceQuality,
            urlType = directData.urlType,
            contentType = directData.contentType,
            resolveDurationMs = resolveDurationMs.coerceAtLeast(0L)
        )
        val tracker = PlaybackNetworkTracker { snap ->
            if (generation == playbackGeneration) {
                _playbackDiagnostics.value = _playbackDiagnostics.value.copy(
                    ttfbMs = snap.ttfbMs,
                    bytesTransferred = snap.bytesTransferred,
                    throughputKbps = snap.throughputKbps,
                    lastHttpStatusCode = snap.lastHttpStatusCode,
                    supportsByteRanges = snap.supportsByteRanges
                )
            }
        }
        networkTracker = tracker
        Log.i(TAG, "playback_start provider=$providerDiagnostic backend_content_type=${directData.contentType ?: "unknown"} url_type=${directData.urlType}")

        // Build safe HTTP DataSource with response-authorized headers and bounded timeouts
        val rawHttpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)

        val headerMap = mutableMapOf<String, String>()
        var userAgentConfigured = false
        if (directData.headers.isNotEmpty()) {
            for (headerPair in directData.headers) {
                if (headerPair.size >= 2) {
                    val key = headerPair[0]
                    val value = headerPair[1]
                    if (key.isBlank() || value.contains('\r') || value.contains('\n') ||
                        isRedirectSensitiveHeader(key)) continue
                    if (key.equals("User-Agent", ignoreCase = true)) {
                        rawHttpFactory.setUserAgent(value)
                        userAgentConfigured = true
                    } else {
                        headerMap[key] = value
                        if (key.equals("Referer", ignoreCase = true) && !headerMap.containsKey("Origin")) {
                            try {
                                val uri = Uri.parse(value)
                                if (uri.scheme != null && uri.host != null) {
                                    headerMap["Origin"] = "${uri.scheme}://${uri.host}"
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

        // Many CDNs (Cloudflare, Akamai, storage buckets) return 403 when User-Agent is unknown or mobile bot
        if (!userAgentConfigured) {
            rawHttpFactory.setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )
        }

        if (headerMap.isNotEmpty()) {
            rawHttpFactory.setDefaultRequestProperties(headerMap)
        }

        val httpDataSourceFactory = DiagnosticHttpDataSourceFactory(rawHttpFactory, tracker)

        // Configure universal DataSource (supporting redirects, files, content, http/https)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        // Configure Extractors for direct MKV/MP4/TS/WebM streams
        val extractorsFactory = DefaultExtractorsFactory().apply {
            setConstantBitrateSeekingEnabled(true)
        }

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }

            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>
            ) {
                val ffmpegAvailable = isFfmpegLibraryAvailable()
                val selectiveSelector = createSelectiveMediaCodecSelector(
                    delegate = mediaCodecSelector,
                    preferFfmpegForExtendedCodecs = ffmpegAvailable
                )

                // 1. Native platform MediaCodecAudioRenderer preserved for AAC, HE-AAC, MP3, Opus,
                // FLAC, Vorbis, PCM, etc., while yielding FFmpeg-handled codecs (EAC3, AC3, DTS,
                // TrueHD, WMA, ALAC) when FFmpeg native library is loaded.
                out.add(
                    MediaCodecAudioRenderer(
                        context,
                        selectiveSelector,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        audioSink
                    )
                )

                // 2. FFmpeg Audio Decoder extension for EAC3/EAC3-JOC, AC3, DTS, TrueHD, etc.
                if (ffmpegAvailable) {
                    try {
                        out.add(
                            FfmpegAudioRenderer(
                                eventHandler,
                                eventListener,
                                audioSink
                            )
                        )
                        Log.i(TAG, "FFmpeg software audio decoder registered in renderers pipeline.")
                    } catch (t: Throwable) {
                        Log.w(TAG, "FFmpeg audio decoder registration failed: ${t.message}")
                    }
                } else {
                    Log.w(TAG, "FFmpeg native library unavailable; falling back to platform audio decoders.")
                }
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setEnableDecoderFallback(true)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 4000,
                /* maxBufferMs = */ 50000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15000, true)
            .build()

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        // Resolve container MIME type:
        // Cloudflare R2 / S3 URLs and CDN token URLs often end with a hash or query string
        // without a clear file extension, or return application/octet-stream.
        // We explicitly detect HLS, DASH, MKV, MP4 from url_type, content_type, and URL patterns.
        val normalizedDirectData = if (cleanPlaybackUrl == directData.playbackUrl) {
            directData
        } else {
            directData.copy(playbackUrl = cleanPlaybackUrl)
        }
        val inferredMimeType = detectMimeType(normalizedDirectData)

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(cleanPlaybackUrl)

        if (inferredMimeType != null) {
            mediaItemBuilder.setMimeType(inferredMimeType)
            Log.i(TAG, "Configured MediaItem with explicit MIME type: $inferredMimeType for URL type: ${directData.urlType}")
        }

        val mediaItem = mediaItemBuilder.build()

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        _exoPlayerInstance.value = player
        exoPlayer = player

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                Log.i(TAG, "Audio decoder initialized: $decoderName init_ms=${initializationDurationMs} provider=$providerDiagnostic")
                _activeAudioDecoder.value = decoderName
                _playbackDiagnostics.value = _playbackDiagnostics.value.copy(activeAudioDecoder = decoderName)
            }
        })

        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (generation != playbackGeneration || player !== exoPlayer) return
                val dur = player.duration
                if (dur > 0 && dur != C.TIME_UNSET) {
                    _duration.value = dur
                }
                val pos = player.currentPosition
                if (pos >= 0) {
                    _currentPosition.value = pos
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (generation != playbackGeneration || player !== exoPlayer) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _isBuffering.value = true
                    }
                    Player.STATE_READY -> {
                        _isBuffering.value = false
                        if (!hasUnsupportedTrackError) {
                            _errorMessage.value = null
                        }
                        val dur = player.duration
                        if (dur > 0 && dur != C.TIME_UNSET) {
                            _duration.value = dur
                        }
                        retryCount = 0
                        _playbackDiagnostics.value = _playbackDiagnostics.value.copy(retryCount = 0)
                        if (!player.isPlaying && player.playWhenReady) {
                            player.play()
                        }
                    }
                    Player.STATE_ENDED -> {
                        _isBuffering.value = false
                        _isPlaying.value = false
                        _isPlaybackEnded.value = true
                        onPlaybackEndedCallback?.invoke()
                    }
                    Player.STATE_IDLE -> {
                        _isBuffering.value = false
                    }
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (generation != playbackGeneration || player !== exoPlayer) return
                val dur = player.duration
                if (dur > 0 && dur != C.TIME_UNSET) {
                    _duration.value = dur
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (generation != playbackGeneration || player !== exoPlayer) return
                _isPlaying.value = isPlaying
                val dur = player.duration
                if (dur > 0 && dur != C.TIME_UNSET) {
                    _duration.value = dur
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (generation != playbackGeneration || player !== exoPlayer) return
                extractTracks(tracks, providerDiagnostic)
                val dur = player.duration
                if (dur > 0 && dur != C.TIME_UNSET) {
                    _duration.value = dur
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (generation != playbackGeneration || player !== exoPlayer) return
                if (videoSize.height > 0) {
                    val resolution = "${videoSize.height}p"
                    _videoResolution.value = resolution
                    _playbackDiagnostics.value = _playbackDiagnostics.value.copy(videoResolution = resolution)
                    Log.i(TAG, "media3_video_resolution=${videoSize.height}p provider=$providerDiagnostic content_type=${directData.contentType ?: "unknown"}")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (generation != playbackGeneration || player !== exoPlayer) return
                _isBuffering.value = false
                val failureCategory = classifyPlaybackError(error)
                val httpFailure = generateSequence(error as Throwable?) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                val requestId = httpFailure?.headerFields?.entries
                    ?.firstOrNull { it.key.equals("X-Request-ID", true) || it.key.equals("X-Correlation-ID", true) }
                    ?.value?.firstOrNull()
                _playbackDiagnostics.value = _playbackDiagnostics.value.copy(
                    failureCategory = failureCategory,
                    lastErrorMessage = error.localizedMessage ?: error.errorCodeName
                )
                Log.e(TAG, "ExoPlayer failure category=$failureCategory code=${error.errorCode} http_status=${httpFailure?.responseCode ?: "none"} request_id=${requestId ?: "none"} provider=$providerDiagnostic content_type=${directData.contentType ?: "unknown"} url_type=${directData.urlType}")

                // If track switching failed due to unsupported decoder on selected track, recover smoothly
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                ) {
                    val prev = lastWorkingTrackSelectionParams
                    if (prev != null) {
                        Log.w(TAG, "Selected track decoder initialization failed. Reverting to previous working track.")
                        lastWorkingTrackSelectionParams = null
                        val currentPos = player.currentPosition
                        player.trackSelectionParameters = prev
                        _errorMessage.value = "Selected audio track format or codec is not supported on this device. Reverted to previous track."
                        if (currentPos > 0) {
                            player.seekTo(currentPos)
                        }
                        player.prepare()
                        player.play()
                        return
                    }
                }

                val isResolvableHttp = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE

                val formatOrCodecMsg = when (failureCategory) {
                    PlaybackFailureCategory.CONTENT_TYPE -> "Unsupported stream content type."
                    PlaybackFailureCategory.CONTAINER -> "Media container format is not supported on this device."
                    PlaybackFailureCategory.AUDIO_DECODER -> "Audio format or codec is not supported on this device."
                    PlaybackFailureCategory.DECODER -> "Video or audio codec is not supported on this device."
                    else -> null
                }

                if (isResolvableHttp && retryCount < 1) {
                    retryCount++
                    _playbackDiagnostics.value = _playbackDiagnostics.value.copy(retryCount = retryCount)
                    _errorMessage.value = "Playback link expired or connection dropped. Refreshing..."
                    onRetryRequested?.invoke()
                } else if (onPlaybackFailedCallback != null) {
                    if (formatOrCodecMsg != null) {
                        hasUnsupportedTrackError = true
                        _errorMessage.value = formatOrCodecMsg
                    } else {
                        _errorMessage.value = "Source failed to play. Trying alternative source..."
                    }
                    onPlaybackFailedCallback?.invoke()
                } else {
                    val msg = formatOrCodecMsg ?: when (failureCategory) {
                        PlaybackFailureCategory.HTTP -> "The stream request was rejected by the server (HTTP error)."
                        PlaybackFailureCategory.NETWORK_TIMEOUT -> "The stream request timed out. Check the connection and retry."
                        PlaybackFailureCategory.NETWORK -> "The stream could not be reached. Check the connection and retry."
                        PlaybackFailureCategory.MANIFEST -> "The HLS/DASH manifest could not be parsed."
                        PlaybackFailureCategory.OTHER -> "Playback failed. Please select another source."
                        else -> "Playback failed. Please select another source."
                    }
                    _errorMessage.value = msg
                }
            }
        })

        // Configure playback parameters and media item after listeners are registered
        player.playWhenReady = true
        player.volume = 1.0f
        val preferredLangs = com.example.data.preferences.AudioPreferences.getInstance(context).getPreferredAudioLanguageCodes()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setPreferredAudioLanguages(*preferredLangs.toTypedArray())
            .build()
        player.setMediaItem(mediaItem, startPositionMs)
        player.prepare()
        player.play()

        startPositionTicker()
        return player
    }

    private fun extractTracks(tracks: Tracks, providerDiagnostic: String) {
        val extracted = extractTracksFromMedia3(tracks, providerDiagnostic)

        extracted.selectedAudioDecoderLabel?.let { decoderLabel ->
            _activeAudioDecoder.value = decoderLabel
            _playbackDiagnostics.value = _playbackDiagnostics.value.copy(activeAudioDecoder = decoderLabel)
        }

        if (extracted.hasUnsupportedAudioOnly) {
            hasUnsupportedTrackError = true
            _errorMessage.value = "Audio format or codec is not supported on this device."
        } else if (hasUnsupportedTrackError && extracted.hasSupportedAudio) {
            hasUnsupportedTrackError = false
            _errorMessage.value = null
        }

        _availableAudioTracks.value = extracted.audioTracks
        _availableSubtitles.value = extracted.subtitleTracks
        _availableQualities.value = extracted.videoQualities
    }

    fun selectTrack(option: TrackOption) {
        val player = exoPlayer ?: return
        when (option.id) {
            "auto_audio" -> {
                val preferredLangs = com.example.data.preferences.AudioPreferences.getInstance(context).getPreferredAudioLanguageCodes()
                lastWorkingTrackSelectionParams = player.trackSelectionParameters
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setPreferredAudioLanguages(*preferredLangs.toTypedArray())
                    .build()
                hasUnsupportedTrackError = false
                _errorMessage.value = null
                _availableAudioTracks.value = _availableAudioTracks.value.map {
                    it.copy(isSelected = it.id == "auto_audio")
                }
            }
            "off_sub" -> {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .build()
                _availableSubtitles.value = _availableSubtitles.value.map {
                    it.copy(isSelected = it.id == "off_sub")
                }
            }
            "auto_video" -> {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .build()
                _availableQualities.value = _availableQualities.value.map {
                    it.copy(isSelected = it.id == "auto_video")
                }
            }
            else -> {
                if (option.trackGroup != null) {
                    if (option.id.startsWith("audio_") && !option.isSupported) {
                        _errorMessage.value = "Audio format '${option.label}' is not supported on this device."
                        return
                    }
                    val override = TrackSelectionOverride(option.trackGroup, listOf(option.trackIndex))
                    val previousParams = player.trackSelectionParameters
                    if (option.id.startsWith("audio_")) {
                        lastWorkingTrackSelectionParams = previousParams
                    }
                    val builder = player.trackSelectionParameters.buildUpon()
                    if (option.id.startsWith("audio_")) {
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    } else if (option.id.startsWith("sub_")) {
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    }
                    builder.setOverrideForType(override)
                    try {
                        player.trackSelectionParameters = builder.build()
                        if (option.id.startsWith("audio_")) {
                            hasUnsupportedTrackError = false
                            _errorMessage.value = null
                            val decLabel = if (option.isEac3 || option.decoderType == "FFmpeg") {
                                "FFmpeg Audio Renderer (Software)"
                            } else {
                                "MediaCodec Hardware Audio Renderer"
                            }
                            _activeAudioDecoder.value = decLabel
                            _playbackDiagnostics.value = _playbackDiagnostics.value.copy(activeAudioDecoder = decLabel)
                            _availableAudioTracks.value = _availableAudioTracks.value.map {
                                it.copy(isSelected = it.id == option.id)
                            }
                        } else if (option.id.startsWith("sub_")) {
                            _availableSubtitles.value = _availableSubtitles.value.map {
                                it.copy(isSelected = it.id == option.id)
                            }
                        } else if (option.id.startsWith("video_")) {
                            _availableQualities.value = _availableQualities.value.map {
                                it.copy(isSelected = it.id == option.id)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to apply track override: ${e.message}", e)
                        player.trackSelectionParameters = previousParams
                        _errorMessage.value = "Failed to switch track: ${e.message}"
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs.coerceAtLeast(0L))
        _currentPosition.value = positionMs
    }

    fun seekBy(offsetMs: Long) {
        val player = exoPlayer ?: return
        val newPos = (player.currentPosition + offsetMs).coerceIn(0L, player.duration.coerceAtLeast(0L))
        player.seekTo(newPos)
        _currentPosition.value = newPos
    }

    fun setSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
        _playbackSpeed.value = speed
    }

    private fun startPositionTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Main) {
            var bufferingSeconds = 0
            var stallFallbackTriggered = false
            while (isActive) {
                exoPlayer?.let { player ->
                    val dur = player.duration
                    if (dur > 0 && dur != C.TIME_UNSET) {
                        _duration.value = dur
                    }
                    val pos = player.currentPosition
                    if (pos >= 0) {
                        _currentPosition.value = pos
                    }

                    if (player.isPlaying) {
                        bufferingSeconds = 0
                        stallFallbackTriggered = false
                        activeSession?.let { onPositionUpdate?.invoke(it, pos, _duration.value) }
                    } else if (player.playbackState == Player.STATE_BUFFERING) {
                        bufferingSeconds++
                        val lastTransferAtMs = networkTracker?.lastTransferAtMs() ?: 0L
                        val hasRecentTransfer = lastTransferAtMs > 0L &&
                            (System.currentTimeMillis() - lastTransferAtMs) < RECENT_TRANSFER_WINDOW_MS
                        if (!stallFallbackTriggered &&
                            shouldTriggerBufferingStallFallback(
                                bufferingSeconds = bufferingSeconds,
                                hasRecentTransfer = hasRecentTransfer
                            )
                        ) {
                            stallFallbackTriggered = true
                            bufferingSeconds = 0
                            Log.w(
                                TAG,
                                "Playback stalled in buffering state (recentTransfer=$hasRecentTransfer, " +
                                    "bytes=${networkTracker?.totalBytesTransferred() ?: 0L}). Triggering fallback."
                            )
                            if (onPlaybackFailedCallback != null) {
                                _errorMessage.value = "Playback link stalled. Trying alternative source..."
                                onPlaybackFailedCallback?.invoke()
                            } else {
                                _errorMessage.value = "Stream buffering stalled. Please try another source."
                            }
                        }
                    } else {
                        bufferingSeconds = 0
                    }
                }
                delay(1000)
            }
        }
    }

    fun play() {
        exoPlayer?.play()
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun release() {
        playbackGeneration++
        tickerJob?.cancel()
        tickerJob = null
        networkTracker = null
        onRetryRequested = null
        onPlaybackFailedCallback = null
        lastWorkingTrackSelectionParams = null
        hasUnsupportedTrackError = false
        exoPlayer?.let { player ->
            activeSession?.let { onPositionUpdate?.invoke(it, player.currentPosition, player.duration) }
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        exoPlayer = null
        activeSession = null
        _exoPlayerInstance.value = null
        _isPlaying.value = false
        _isBuffering.value = false
        _isPlaybackEnded.value = false
        _currentPosition.value = 0L
        _duration.value = 0L
        _videoResolution.value = null
        _activeAudioDecoder.value = null
        _availableAudioTracks.value = emptyList()
        _availableSubtitles.value = emptyList()
        _availableQualities.value = emptyList()
        _errorMessage.value = null
    }

    internal data class NetworkMetricsSnapshot(
        val ttfbMs: Long?,
        val bytesTransferred: Long,
        val throughputKbps: Long?,
        val lastHttpStatusCode: Int?,
        val supportsByteRanges: Boolean?
    )

    internal class PlaybackNetworkTracker(
        private val onUpdate: (NetworkMetricsSnapshot) -> Unit = {}
    ) {
        private val bytesTransferred = AtomicLong(0L)
        private val firstOpenNanos = AtomicLong(0L)
        private val firstByteNanos = AtomicLong(0L)
        private val lastTransferAt = AtomicLong(0L)
        private val ttfbMs = AtomicLong(-1L)
        private val lastStatus = AtomicInteger(-1)
        private val byteRangeSupport = AtomicInteger(-1) // -1 unknown, 0 false, 1 true
        private val hasBytes = AtomicBoolean(false)

        fun onRequestStart() {
            firstOpenNanos.compareAndSet(0L, System.nanoTime())
        }

        fun onResponseHeaders(statusCode: Int, headers: Map<String, List<String>>) {
            if (statusCode > 0) {
                lastStatus.set(statusCode)
            }
            val acceptRanges = headers.entries.firstOrNull { it.key.equals("Accept-Ranges", ignoreCase = true) }
                ?.value?.joinToString(",")?.trim()?.lowercase()
            val hasContentRange = headers.keys.any { it.equals("Content-Range", ignoreCase = true) }
            when {
                statusCode == 206 || hasContentRange || acceptRanges == "bytes" -> byteRangeSupport.set(1)
                acceptRanges == "none" -> byteRangeSupport.compareAndSet(-1, 0)
            }
            emitSnapshot()
        }

        fun onBytesRead(count: Int) {
            if (count <= 0) return
            val nowNanos = System.nanoTime()
            if (hasBytes.compareAndSet(false, true)) {
                firstByteNanos.set(nowNanos)
                val started = firstOpenNanos.get()
                if (started > 0L) {
                    ttfbMs.set(((nowNanos - started) / 1_000_000L).coerceAtLeast(0L))
                }
            }
            bytesTransferred.addAndGet(count.toLong())
            lastTransferAt.set(System.currentTimeMillis())
            emitSnapshot()
        }

        fun lastTransferAtMs(): Long = lastTransferAt.get()

        fun totalBytesTransferred(): Long = bytesTransferred.get()

        fun snapshot(): NetworkMetricsSnapshot {
            val total = bytesTransferred.get()
            val ttfb = ttfbMs.get().takeIf { it >= 0L }
            val startRead = firstByteNanos.get()
            val throughput = if (startRead > 0L && total > 0L) {
                val elapsedMs = ((System.nanoTime() - startRead) / 1_000_000L).coerceAtLeast(1L)
                (total * 8L) / elapsedMs // bits per ms == kbps
            } else null
            val status = lastStatus.get().takeIf { it > 0 }
            val rangeSupport = when (byteRangeSupport.get()) {
                1 -> true
                0 -> false
                else -> null
            }
            return NetworkMetricsSnapshot(
                ttfbMs = ttfb,
                bytesTransferred = total,
                throughputKbps = throughput,
                lastHttpStatusCode = status,
                supportsByteRanges = rangeSupport
            )
        }

        private fun emitSnapshot() {
            onUpdate(snapshot())
        }
    }

    internal class DiagnosticHttpDataSource(
        private val delegate: HttpDataSource,
        private val tracker: PlaybackNetworkTracker
    ) : HttpDataSource by delegate {
        override fun open(dataSpec: DataSpec): Long {
            tracker.onRequestStart()
            val length = delegate.open(dataSpec)
            tracker.onResponseHeaders(delegate.responseCode, delegate.responseHeaders)
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = delegate.read(buffer, offset, length)
            if (read > 0) {
                tracker.onBytesRead(read)
            }
            return read
        }
    }

    internal class DiagnosticHttpDataSourceFactory(
        private val delegateFactory: HttpDataSource.Factory,
        private val tracker: PlaybackNetworkTracker
    ) : HttpDataSource.Factory {
        override fun createDataSource(): HttpDataSource =
            DiagnosticHttpDataSource(delegateFactory.createDataSource(), tracker)

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
            delegateFactory.setDefaultRequestProperties(defaultRequestProperties)
            return this
        }
    }

    companion object {
        const val TAG = "PlayerManager"
        const val HTTP_CONNECT_TIMEOUT_MS = 12_000
        const val HTTP_READ_TIMEOUT_MS = 20_000
        const val STALL_NO_DATA_SECONDS = 12
        const val MAX_SLOW_BUFFER_SECONDS = 30
        const val RECENT_TRANSFER_WINDOW_MS = 5_000L

        internal fun shouldTriggerBufferingStallFallback(
            bufferingSeconds: Int,
            hasRecentTransfer: Boolean
        ): Boolean = if (hasRecentTransfer) {
            bufferingSeconds >= MAX_SLOW_BUFFER_SECONDS
        } else {
            bufferingSeconds >= STALL_NO_DATA_SECONDS
        }

        /** Custom credential headers are not forwarded through uncontrolled CDN redirects. */
        fun isRedirectSensitiveHeader(headerName: String): Boolean {
            val name = headerName.trim().lowercase()
            val sensitiveTerms = listOf(
                "auth", "cookie", "token", "signature", "secret", "credential", "password", "key",
                "api-key", "api_key", "apikey", "access-key", "access_key", "private-key", "private_key"
            )
            return sensitiveTerms.any(name::contains)
        }

        fun classifyPlaybackError(error: PlaybackException): PlaybackFailureCategory {
            if (generateSequence(error as Throwable?) { it.cause }.any { it is java.net.SocketTimeoutException }) {
                return PlaybackFailureCategory.NETWORK_TIMEOUT
            }
            return when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlaybackFailureCategory.HTTP
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> PlaybackFailureCategory.CONTENT_TYPE
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> PlaybackFailureCategory.NETWORK
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> PlaybackFailureCategory.DECODER
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> PlaybackFailureCategory.AUDIO_DECODER
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> PlaybackFailureCategory.CONTAINER
                else -> {
                    val causes = generateSequence(error as Throwable?) { it.cause }
                    if (causes.any { it.javaClass.simpleName.contains("Manifest", ignoreCase = true) ||
                            it.javaClass.simpleName.contains("Parser", ignoreCase = true) }) {
                        PlaybackFailureCategory.MANIFEST
                    } else {
                        PlaybackFailureCategory.OTHER
                    }
                }
            }
        }

        /**
         * Infers MIME type from direct response data (url_type, content_type, filename, or URI path).
         * This prevents ExoPlayer from failing when Cloudflare R2, S3, or CDN token URLs lack
         * a file extension in their path.
         */
        fun detectMimeType(directData: DirectPlayData): String? {
            val urlType = directData.urlType.lowercase().trim()
            val contentType = directData.contentType?.lowercase() ?: ""
            val path = Uri.parse(directData.playbackUrl).path.orEmpty().lowercase()

            return when {
                contentType.contains("mpegurl") || contentType.contains("m3u8") -> MimeTypes.APPLICATION_M3U8

                contentType.contains("dash") || contentType.contains("mpd") -> MimeTypes.APPLICATION_MPD

                contentType.contains("matroska") || contentType.contains("x-mkv") -> MimeTypes.VIDEO_MATROSKA

                contentType.contains("mp4") -> MimeTypes.VIDEO_MP4

                contentType.contains("webm") -> MimeTypes.VIDEO_WEBM

                urlType == "hls" || urlType == "m3u8" -> MimeTypes.APPLICATION_M3U8
                urlType == "dash" || urlType == "mpd" -> MimeTypes.APPLICATION_MPD
                urlType == "mkv" || urlType == "matroska" -> MimeTypes.VIDEO_MATROSKA
                urlType == "mp4" -> MimeTypes.VIDEO_MP4
                urlType == "webm" -> MimeTypes.VIDEO_WEBM

                path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
                path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
                path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
                path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM

                else -> null
            }
        }

        fun isFfmpegLibraryAvailable(): Boolean = runCatching {
            FfmpegLibrary.isAvailable()
        }.getOrDefault(false)

        /**
         * Identifies audio codecs that should be decoded via Media3 FFmpeg extension
         * (EAC3, EAC3-JOC, AC3, DTS, DTS-HD, TrueHD, ALAC, WMA, MP2), while leaving
         * AAC, MP3, Opus, FLAC, Vorbis, and PCM to Android's native MediaCodecAudioRenderer.
         */
        fun isFfmpegPreferredAudioMimeType(mimeType: String?): Boolean {
            if (mimeType.isNullOrBlank()) return false
            val norm = mimeType.trim().lowercase(java.util.Locale.ROOT)
            return isEac3MimeType(norm) ||
                norm == MimeTypes.AUDIO_AC3 ||
                norm == MimeTypes.AUDIO_DTS ||
                norm == MimeTypes.AUDIO_DTS_HD ||
                norm == MimeTypes.AUDIO_DTS_X ||
                norm == MimeTypes.AUDIO_TRUEHD ||
                norm == MimeTypes.AUDIO_MLAW ||
                norm == MimeTypes.AUDIO_ALAW ||
                norm == MimeTypes.AUDIO_MPEG_L2 ||
                norm == "audio/mp2" ||
                norm == MimeTypes.AUDIO_ALAC ||
                norm == "audio/ac3" ||
                norm == "audio/vnd.dts" ||
                norm == "audio/vnd.dts.hd" ||
                norm == "audio/true-hd" ||
                norm == "audio/x-ms-wma" ||
                norm == "audio/alac"
        }

        internal fun createSelectiveMediaCodecSelector(
            delegate: MediaCodecSelector = MediaCodecSelector.DEFAULT,
            preferFfmpegForExtendedCodecs: Boolean = isFfmpegLibraryAvailable()
        ): MediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            if (preferFfmpegForExtendedCodecs && isFfmpegPreferredAudioMimeType(mimeType)) {
                emptyList()
            } else {
                delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            }
        }

        internal data class ExtractedTracksResult(
            val audioTracks: List<TrackOption>,
            val subtitleTracks: List<TrackOption>,
            val videoQualities: List<TrackOption>,
            val selectedAudioDecoderLabel: String?,
            val hasSupportedAudio: Boolean,
            val hasUnsupportedAudioOnly: Boolean
        )

        internal fun extractTracksFromMedia3(
            tracks: Tracks,
            providerDiagnostic: String = "stream"
        ): ExtractedTracksResult {
            val audioList = mutableListOf<TrackOption>()
            val subList = mutableListOf<TrackOption>()
            val qualityList = mutableListOf<TrackOption>()

            var anyAudioSelected = false
            var anySubSelected = false
            var anyVideoSelected = false
            var selectedDecoderLabel: String? = null

            for ((groupIndex, groupInfo) in tracks.groups.withIndex()) {
                val trackGroup = groupInfo.mediaTrackGroup
                val type = trackGroup.type

                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getFormat(i)
                    val isSelected = groupInfo.isTrackSelected(i)
                    val isSupported = groupInfo.isTrackSupported(i)
                    if (type == C.TRACK_TYPE_AUDIO || type == C.TRACK_TYPE_VIDEO) {
                        runCatching {
                            Log.i(
                                TAG,
                                "media3_track provider=${providerDiagnostic} type=${if (type == C.TRACK_TYPE_VIDEO) "video" else "audio"} " +
                                    "sample_mime=${format.sampleMimeType ?: "unknown"} codec=${format.codecs ?: "unknown"} supported=$isSupported"
                            )
                        }
                    }

                    when (type) {
                        C.TRACK_TYPE_AUDIO -> {
                            val lang = getDisplayLanguage(format.language, format.label)
                            val mime = format.sampleMimeType ?: ""
                            val isEac3 = isEac3MimeType(mime)
                            val isFfmpegCodec = isFfmpegPreferredAudioMimeType(mime)
                            val codec = codecDisplayName(mime)
                            val channelsStr = if (format.channelCount > 0) " (${format.channelCount}ch)" else ""
                            val decoderType = when {
                                !isSupported -> "Unsupported"
                                isFfmpegCodec -> "FFmpeg"
                                else -> "MediaCodec"
                            }
                            val decoderBadge = when {
                                !isSupported -> " [Unsupported]"
                                decoderType == "FFmpeg" -> " [FFmpeg]"
                                else -> ""
                            }
                            val label = (if (codec.isNotBlank()) "$lang ($codec$channelsStr)" else lang) + decoderBadge
                            val uniqueId = "audio_${groupIndex}_${i}"
                            if (isSelected && isSupported) {
                                anyAudioSelected = true
                                selectedDecoderLabel = if (decoderType == "FFmpeg") {
                                    "FFmpeg Audio Renderer (Software)"
                                } else {
                                    "MediaCodec Hardware Audio Renderer"
                                }
                            }
                            audioList.add(
                                TrackOption(
                                    id = uniqueId,
                                    label = label,
                                    isSelected = isSelected,
                                    trackGroup = trackGroup,
                                    trackIndex = i,
                                    sampleMimeType = mime,
                                    isEac3 = isEac3,
                                    decoderType = decoderType,
                                    isSupported = isSupported
                                )
                            )
                        }
                        C.TRACK_TYPE_TEXT -> {
                            val lang = getDisplayLanguage(format.language, format.label)
                            val uniqueId = "sub_${groupIndex}_${i}"
                            if (isSelected) anySubSelected = true
                            subList.add(TrackOption(uniqueId, lang, isSelected, trackGroup, i, isSupported = isSupported))
                        }
                        C.TRACK_TYPE_VIDEO -> {
                            if (format.height > 0) {
                                val label = "${format.height}p" + (if (format.frameRate > 0) " (${format.frameRate.toInt()}fps)" else "")
                                val uniqueId = "video_${groupIndex}_${i}"
                                if (isSelected) anyVideoSelected = true
                                qualityList.add(TrackOption(uniqueId, label, isSelected, trackGroup, i, isSupported = isSupported))
                            }
                        }
                    }
                }
            }

            val hasSupportedAudio = audioList.any { it.isSupported }
            val hasUnsupportedAudioOnly = audioList.isNotEmpty() && !hasSupportedAudio

            audioList.add(0, TrackOption("auto_audio", "Default Audio", isSelected = !anyAudioSelected, isSupported = hasSupportedAudio))
            subList.add(0, TrackOption("off_sub", "Subtitles Off", isSelected = !anySubSelected))
            qualityList.add(0, TrackOption("auto_video", "Auto Quality", isSelected = !anyVideoSelected))

            return ExtractedTracksResult(
                audioTracks = audioList,
                subtitleTracks = subList,
                videoQualities = qualityList,
                selectedAudioDecoderLabel = selectedDecoderLabel,
                hasSupportedAudio = hasSupportedAudio,
                hasUnsupportedAudioOnly = hasUnsupportedAudioOnly
            )
        }

        internal fun codecDisplayName(mime: String): String {
            val lower = mime.lowercase(java.util.Locale.ROOT)
            return when {
                isEac3MimeType(lower) -> "EAC3"
                lower.contains("ac3") -> "AC3"
                lower.contains("dts.hd") -> "DTS-HD"
                lower.contains("dts") -> "DTS"
                lower.contains("true-hd") || lower.contains("truehd") -> "TrueHD"
                lower.contains("mp4a") || lower.contains("aac") -> "AAC"
                lower.contains("opus") -> "OPUS"
                lower.contains("flac") -> "FLAC"
                lower.contains("vorbis") -> "VORBIS"
                lower == MimeTypes.AUDIO_MPEG || lower == "audio/mp3" -> "MP3"
                lower == MimeTypes.AUDIO_MPEG_L2 || lower == "audio/mp2" -> "MP2"
                lower.contains("alac") -> "ALAC"
                lower.contains("wma") -> "WMA"
                lower.contains("raw") -> "PCM"
                else -> lower.substringAfterLast('/').uppercase(java.util.Locale.ROOT)
            }
        }

        fun getDisplayLanguage(langCode: String?, formatLabel: String? = null): String {
            val raw = langCode?.trim()?.lowercase(java.util.Locale.ROOT)
            if (!raw.isNullOrEmpty() && raw != "und" && raw != "unknown") {
                val mapped = when (raw.substringBefore('-').substringBefore('_')) {
                    "jpn", "ja", "japanese" -> "Japanese"
                    "eng", "en", "english" -> "English"
                    "hin", "hi", "hindi" -> "Hindi"
                    "tam", "ta", "tamil" -> "Tamil"
                    "tel", "te", "telugu" -> "Telugu"
                    "spa", "es", "spanish" -> "Spanish"
                    "fre", "fra", "fr", "french" -> "French"
                    "ger", "deu", "de", "german" -> "German"
                    "kor", "ko", "korean" -> "Korean"
                    "chi", "zho", "zh", "chinese" -> "Chinese"
                    "por", "pt", "portuguese" -> "Portuguese"
                    "ita", "it", "italian" -> "Italian"
                    "rus", "ru", "russian" -> "Russian"
                    "ara", "ar", "arabic" -> "Arabic"
                    "ind", "id", "indonesian" -> "Indonesian"
                    "tha", "th", "thai" -> "Thai"
                    "vie", "vi", "vietnamese" -> "Vietnamese"
                    else -> null
                }
                if (mapped != null) return mapped
                val localeName = runCatching {
                    java.util.Locale.forLanguageTag(raw).getDisplayLanguage(java.util.Locale.ENGLISH)
                        .takeIf { it.isNotBlank() && !it.equals(raw, ignoreCase = true) }
                        ?.replaceFirstChar { c -> c.titlecase(java.util.Locale.ENGLISH) }
                }.getOrNull()
                if (!localeName.isNullOrBlank()) return localeName
                return langCode.trim().uppercase(java.util.Locale.ROOT)
            }
            val cleanLabel = formatLabel?.trim()?.takeIf { it.isNotEmpty() }
            return cleanLabel ?: "Audio"
        }

        /**
         * Checks whether a sample MIME type represents an EAC3 (Dolby Digital Plus) audio stream.
         * Supported MIME types include:
         * - "audio/eac3"
         * - "audio/eac3-joc"
         * - MimeTypes.AUDIO_E_AC3
         * - MimeTypes.AUDIO_E_AC3_JOC
         */
        fun isEac3MimeType(mimeType: String?): Boolean {
            if (mimeType == null) return false
            return mimeType.equals(MimeTypes.AUDIO_E_AC3, ignoreCase = true) ||
                   mimeType.equals(MimeTypes.AUDIO_E_AC3_JOC, ignoreCase = true) ||
                   mimeType.equals("audio/eac3", ignoreCase = true) ||
                   mimeType.equals("audio/eac3-joc", ignoreCase = true)
        }
    }
}
