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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
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

@OptIn(UnstableApi::class)
class PlayerManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var exoPlayer: ExoPlayer? = null
    private var tickerJob: Job? = null
    private var playbackGeneration: Long = 0L
    private var activeSession: PlaybackSessionIdentity? = null

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

    private val _videoResolution = MutableStateFlow<String?>(null)
    val videoResolution: StateFlow<String?> = _videoResolution.asStateFlow()

    private val _isPlaybackEnded = MutableStateFlow(false)
    val isPlaybackEnded: StateFlow<Boolean> = _isPlaybackEnded.asStateFlow()

    private var retryCount = 0
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
        val lastErrorMessage: String? = null
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
        val generation = playbackGeneration
        activeSession = sessionIdentity?.copy(generation = generation)
        onRetryRequested = onRetryNeeded
        onPlaybackFailedCallback = onPlaybackFailed
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
        Log.i(TAG, "playback_start provider=$providerDiagnostic backend_content_type=${directData.contentType ?: "unknown"} url_type=${directData.urlType}")

        // Build safe HTTP DataSource with response-authorized headers
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(30000)

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
                        httpDataSourceFactory.setUserAgent(value)
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
            httpDataSourceFactory.setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )
        }

        if (headerMap.isNotEmpty()) {
            httpDataSourceFactory.setDefaultRequestProperties(headerMap)
        }

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
                // 1. MediaCodecAudioRenderer for all formats supported by platform MediaCodec.
                // Standard mediaCodecSelector queries all available platform codecs (AAC, MP3, Opus, FLAC, Vorbis, and EAC3 if supported by the device).
                out.add(
                    MediaCodecAudioRenderer(
                        context,
                        mediaCodecSelector,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        audioSink
                    )
                )

                // 2. FFmpeg Audio Decoder fallback (for EAC3, AC3, Opus, FLAC, Vorbis, etc.):
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
        val inferredMimeType = detectMimeType(directData)

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(directData.playbackUrl)

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
                        _errorMessage.value = null
                        val dur = player.duration
                        if (dur > 0 && dur != C.TIME_UNSET) {
                            _duration.value = dur
                        }
                        retryCount = 0
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
                    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) {
                    val prev = lastWorkingTrackSelectionParams
                    if (prev != null) {
                        Log.w(TAG, "Selected track decoder initialization failed. Reverting to previous working track.")
                        lastWorkingTrackSelectionParams = null
                        val currentPos = player.currentPosition
                        player.trackSelectionParameters = prev
                        _errorMessage.value = "Selected audio track cannot be decoded on this device. Reverted to previous working track."
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
                        error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE

                if (isResolvableHttp && retryCount < 1) {
                    retryCount++
                    _errorMessage.value = "Playback link expired or connection dropped. Refreshing..."
                    onRetryRequested?.invoke()
                } else if (onPlaybackFailedCallback != null) {
                    // Automatically fallback to alternative candidate source if available
                    _errorMessage.value = "Source failed to play. Trying alternative source..."
                    onPlaybackFailedCallback?.invoke()
                } else {
                    val msg = when (failureCategory) {
                        PlaybackFailureCategory.HTTP -> "The stream request was rejected by the server (HTTP error)."
                        PlaybackFailureCategory.NETWORK_TIMEOUT -> "The stream request timed out. Check the connection and retry."
                        PlaybackFailureCategory.NETWORK -> "The stream could not be reached. Check the connection and retry."
                        PlaybackFailureCategory.CONTENT_TYPE -> "The server returned a content type Media3 could not use."
                        PlaybackFailureCategory.CONTAINER -> "The media container is not supported by this device."
                        PlaybackFailureCategory.AUDIO_DECODER -> "The audio codec could not be decoded on this device."
                        PlaybackFailureCategory.DECODER -> "The media codec could not be decoded on this device."
                        PlaybackFailureCategory.MANIFEST -> "The HLS/DASH manifest could not be parsed."
                        PlaybackFailureCategory.OTHER -> "Playback failed. Please select another source."
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
        val audioList = mutableListOf<TrackOption>()
        val subList = mutableListOf<TrackOption>()
        val qualityList = mutableListOf<TrackOption>()

        var anyAudioSelected = false
        var anySubSelected = false
        var anyVideoSelected = false

        for ((groupIndex, groupInfo) in tracks.groups.withIndex()) {
                val trackGroup = groupInfo.mediaTrackGroup
                val type = trackGroup.type

            for (i in 0 until trackGroup.length) {
                val format = trackGroup.getFormat(i)
                val isSelected = groupInfo.isTrackSelected(i)
                val isSupported = groupInfo.isTrackSupported(i)
                if (type == C.TRACK_TYPE_AUDIO || type == C.TRACK_TYPE_VIDEO) {
                    Log.i(
                        TAG,
                        "media3_track provider=${providerDiagnostic} type=${if (type == C.TRACK_TYPE_VIDEO) "video" else "audio"} " +
                            "sample_mime=${format.sampleMimeType ?: "unknown"} codec=${format.codecs ?: "unknown"}"
                    )
                }

                when (type) {
                    C.TRACK_TYPE_AUDIO -> {
                        val lang = getDisplayLanguage(format.language)
                        val mime = format.sampleMimeType ?: ""
                        val isEac3 = isEac3MimeType(mime)
                        val isAc3 = mime.contains("ac3", ignoreCase = true)
                        val codec = when {
                            isEac3 -> "EAC3"
                            isAc3 -> "AC3"
                            mime.contains("mp4a", ignoreCase = true) || mime.contains("aac", ignoreCase = true) -> "AAC"
                            mime.contains("opus", ignoreCase = true) -> "OPUS"
                            mime.contains("flac", ignoreCase = true) -> "FLAC"
                            mime.contains("vorbis", ignoreCase = true) -> "VORBIS"
                            else -> mime.substringAfterLast('/').uppercase()
                        }
                        val channelsStr = if (format.channelCount > 0) " (${format.channelCount}ch)" else ""
                        val decoderType = when {
                            !isSupported -> "Unsupported"
                            isEac3 || isAc3 -> "FFmpeg"
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
                            val decoderLabel = if (decoderType == "FFmpeg") "FFmpeg Audio Renderer (Software)" else "MediaCodec Hardware Audio Renderer"
                            _activeAudioDecoder.value = decoderLabel
                            _playbackDiagnostics.value = _playbackDiagnostics.value.copy(activeAudioDecoder = decoderLabel)
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
                        val lang = getDisplayLanguage(format.language)
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
        if (!hasSupportedAudio && audioList.isNotEmpty()) {
            _errorMessage.value = "Audio format is not supported by device decoders."
        }

        // Add Auto/Default options at the front with guaranteed unique IDs
        audioList.add(0, TrackOption("auto_audio", "Default Audio", isSelected = !anyAudioSelected, isSupported = hasSupportedAudio))
        subList.add(0, TrackOption("off_sub", "Subtitles Off", isSelected = !anySubSelected))
        qualityList.add(0, TrackOption("auto_video", "Auto Quality", isSelected = !anyVideoSelected))

        _availableAudioTracks.value = audioList
        _availableSubtitles.value = subList
        _availableQualities.value = qualityList
    }

    private fun getDisplayLanguage(langCode: String?): String {
        return when (langCode?.lowercase()) {
            "jpn", "ja", "japanese" -> "Japanese"
            "eng", "en", "english" -> "English"
            "hin", "hi", "hindi" -> "Hindi"
            "tam", "ta", "tamil" -> "Tamil"
            "tel", "te", "telugu" -> "Telugu"
            "spa", "es", "spanish" -> "Spanish"
            null, "" -> "Audio"
            else -> langCode.uppercase()
        }
    }

    fun selectTrack(option: TrackOption) {
        val player = exoPlayer ?: return
        when (option.id) {
            "auto_audio" -> {
                val preferredLangs = com.example.data.preferences.AudioPreferences.getInstance(context).getPreferredAudioLanguageCodes()
                lastWorkingTrackSelectionParams = player.trackSelectionParameters
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setPreferredAudioLanguages(*preferredLangs.toTypedArray())
                    .build()
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
                        _errorMessage.value = "Audio track '${option.label}' cannot be decoded on this device. Keeping current track."
                        return
                    }
                    val override = TrackSelectionOverride(option.trackGroup, listOf(option.trackIndex))
                    val previousParams = player.trackSelectionParameters
                    if (option.id.startsWith("audio_")) {
                        lastWorkingTrackSelectionParams = previousParams
                    }
                    val builder = player.trackSelectionParameters.buildUpon()
                    if (option.id.startsWith("sub_")) {
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    }
                    builder.setOverrideForType(override)
                    try {
                        player.trackSelectionParameters = builder.build()
                        if (option.id.startsWith("audio_")) {
                            _activeAudioDecoder.value = if (option.isEac3 || option.decoderType == "FFmpeg") {
                                "FFmpeg Audio Renderer (Software)"
                            } else {
                                "MediaCodec Hardware Audio Renderer"
                            }
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
                        activeSession?.let { onPositionUpdate?.invoke(it, pos, _duration.value) }
                    } else if (player.playbackState == Player.STATE_BUFFERING) {
                        bufferingSeconds++
                        // If buffering persists for 15s without playing, auto fallback to next source
                        if (bufferingSeconds >= 15) {
                            bufferingSeconds = 0
                            Log.w(TAG, "Playback stalled in buffering state for 15s. Triggering fallback.")
                            _errorMessage.value = "Playback link stalled. Trying alternative source..."
                            onPlaybackFailedCallback?.invoke()
                        }
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
        exoPlayer?.let { player ->
            activeSession?.let { onPositionUpdate?.invoke(it, player.currentPosition, player.duration) }
            player.stop()
            player.release()
        }
        exoPlayer = null
        activeSession = null
        _exoPlayerInstance.value = null
        _isPlaying.value = false
        _isBuffering.value = false
        _isPlaybackEnded.value = false
        _videoResolution.value = null
    }

    companion object {
        const val TAG = "PlayerManager"

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
