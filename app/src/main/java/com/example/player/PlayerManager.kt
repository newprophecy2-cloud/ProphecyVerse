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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
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

    private val _isPlaybackEnded = MutableStateFlow(false)
    val isPlaybackEnded: StateFlow<Boolean> = _isPlaybackEnded.asStateFlow()

    private var retryCount = 0
    private var onRetryRequested: (() -> Unit)? = null
    private var onPlaybackFailedCallback: (() -> Unit)? = null
    var onPlaybackEndedCallback: (() -> Unit)? = null
    var onPositionUpdate: ((positionMs: Long, durationMs: Long) -> Unit)? = null

    data class TrackOption(
        val id: String,
        val label: String,
        val isSelected: Boolean,
        val trackGroup: TrackGroup? = null,
        val trackIndex: Int = 0,
        val sampleMimeType: String? = null,
        val isEac3: Boolean = false,
        val decoderType: String? = null
    )

    fun initializePlayer(
        directData: DirectPlayData,
        startPositionMs: Long = 0L,
        onPlaybackFailed: (() -> Unit)? = null,
        onRetryNeeded: () -> Unit
    ): ExoPlayer {
        release()
        onRetryRequested = onRetryNeeded
        onPlaybackFailedCallback = onPlaybackFailed
        _errorMessage.value = null
        _activeAudioDecoder.value = null
        _isPlaybackEnded.value = false
        _isBuffering.value = true
        _duration.value = 0L
        _currentPosition.value = startPositionMs

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
                    .setAudioCapabilities(
                        AudioCapabilities(
                            intArrayOf(
                                android.media.AudioFormat.ENCODING_PCM_16BIT,
                                android.media.AudioFormat.ENCODING_PCM_FLOAT
                            ),
                            8
                        )
                    )
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
                // 1. MediaCodecAudioRenderer for all non-EAC3 audio formats:
                // We wrap mediaCodecSelector so that for EAC3 ("audio/eac3", "audio/eac3-joc"),
                // it returns emptyList(). This guarantees MediaCodecAudioRenderer will never
                // handle EAC3, leaving EAC3 exclusively to the FFmpeg software decoder.
                // For all other audio formats (AAC, MP3, Opus, FLAC, Vorbis, etc.),
                // mediaCodecSelector is called as usual, keeping existing ExoPlayer playback unchanged.
                val selectiveMediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    if (isEac3MimeType(mimeType)) {
                        emptyList()
                    } else {
                        mediaCodecSelector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                    }
                }

                out.add(
                    MediaCodecAudioRenderer(
                        context,
                        selectiveMediaCodecSelector,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        audioSink
                    )
                )

                // 2. FFmpeg Audio Decoder for EAC3:
                // We register FfmpegAudioRenderer. When the selected audio track is EAC3 (or if EAC3 is the
                // only available audio track), MediaCodecAudioRenderer cannot handle it, so ExoPlayer routes
                // it to this FFmpeg decoder.
                try {
                    val ffmpegAudioRenderer = FfmpegAudioRenderer(
                        eventHandler,
                        eventListener,
                        audioSink
                    )
                    out.add(ffmpegAudioRenderer)
                    Log.i(TAG, "FFmpeg software audio decoder registered in renderers pipeline.")
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to instantiate FfmpegAudioRenderer", e)
                }
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setEnableDecoderFallback(true)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 3000,
                /* maxBufferMs = */ 30000,
                /* bufferForPlaybackMs = */ 1000,
                /* bufferForPlaybackAfterRebufferMs = */ 2000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        // Unmute device media stream if muted
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.let { am ->
                val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                if (max > 0) {
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, max, 0)
                }
            }
        } catch (_: Exception) {}

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
            .apply {
                playWhenReady = true
                volume = 1.0f
                val preferredLangs = com.example.data.preferences.AudioPreferences.getInstance(context).getPreferredAudioLanguageCodes()
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguages(*preferredLangs.toTypedArray())
                    .build()
                setMediaItem(mediaItem, startPositionMs)
                prepare()
                play()
            }

        _exoPlayerInstance.value = player
        exoPlayer = player

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                Log.i(TAG, "Audio decoder initialized: $decoderName (init took ${initializationDurationMs}ms)")
                _activeAudioDecoder.value = decoderName
            }
        })

        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
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
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _isBuffering.value = true
                    }
                    Player.STATE_READY -> {
                        _isBuffering.value = false
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
                val dur = player.duration
                if (dur > 0 && dur != C.TIME_UNSET) {
                    _duration.value = dur
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                val dur = player.duration
                if (dur > 0 && dur != C.TIME_UNSET) {
                    _duration.value = dur
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                extractTracks(tracks)
                val dur = player.duration
                if (dur > 0 && dur != C.TIME_UNSET) {
                    _duration.value = dur
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _isBuffering.value = false
                Log.e(TAG, "ExoPlayer error [${error.errorCode}]: ${error.message}", error)

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
                    val msg = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Source unavailable (HTTP error)"
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "Decoder unsupported on device"
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Media container not supported by device"
                        else -> "Playback error encountered. Please select another source."
                    }
                    _errorMessage.value = msg
                }
            }
        })

        startPositionTicker()
        return player
    }

    private fun extractTracks(tracks: Tracks) {
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

                when (type) {
                    C.TRACK_TYPE_AUDIO -> {
                        val lang = getDisplayLanguage(format.language)
                        val mime = format.sampleMimeType ?: ""
                        val isEac3 = isEac3MimeType(mime)
                        val codec = when {
                            isEac3 -> "EAC3"
                            mime.contains("ac3", ignoreCase = true) -> "AC3"
                            mime.contains("mp4a", ignoreCase = true) || mime.contains("aac", ignoreCase = true) -> "AAC"
                            mime.contains("opus", ignoreCase = true) -> "OPUS"
                            mime.contains("flac", ignoreCase = true) -> "FLAC"
                            mime.contains("vorbis", ignoreCase = true) -> "VORBIS"
                            else -> mime.substringAfterLast('/').uppercase()
                        }
                        val decoderBadge = if (isEac3) " [FFmpeg]" else ""
                        val label = (if (codec.isNotBlank()) "$lang ($codec)" else lang) + decoderBadge
                        val uniqueId = "audio_${groupIndex}_${i}"
                        if (isSelected) anyAudioSelected = true
                        audioList.add(
                            TrackOption(
                                id = uniqueId,
                                label = label,
                                isSelected = isSelected,
                                trackGroup = trackGroup,
                                trackIndex = i,
                                sampleMimeType = mime,
                                isEac3 = isEac3,
                                decoderType = if (isEac3) "FFmpeg" else "MediaCodec"
                            )
                        )
                    }
                    C.TRACK_TYPE_TEXT -> {
                        val lang = getDisplayLanguage(format.language)
                        val uniqueId = "sub_${groupIndex}_${i}"
                        if (isSelected) anySubSelected = true
                        subList.add(TrackOption(uniqueId, lang, isSelected, trackGroup, i))
                    }
                    C.TRACK_TYPE_VIDEO -> {
                        if (format.height > 0) {
                            val label = "${format.height}p" + (if (format.frameRate > 0) " (${format.frameRate.toInt()}fps)" else "")
                            val uniqueId = "video_${groupIndex}_${i}"
                            if (isSelected) anyVideoSelected = true
                            qualityList.add(TrackOption(uniqueId, label, isSelected, trackGroup, i))
                        }
                    }
                }
            }
        }

        // Add Auto/Default options at the front with guaranteed unique IDs
        audioList.add(0, TrackOption("auto_audio", "Default Audio", isSelected = !anyAudioSelected))
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
                    val override = TrackSelectionOverride(option.trackGroup, listOf(option.trackIndex))
                    val builder = player.trackSelectionParameters.buildUpon()
                    if (option.id.startsWith("sub_")) {
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    }
                    builder.setOverrideForType(override)
                    player.trackSelectionParameters = builder.build()

                    if (option.id.startsWith("audio_")) {
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
                        onPositionUpdate?.invoke(pos, _duration.value)
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
        tickerJob?.cancel()
        tickerJob = null
        exoPlayer?.let { player ->
            onPositionUpdate?.invoke(player.currentPosition, player.duration)
            player.stop()
            player.release()
        }
        exoPlayer = null
        _exoPlayerInstance.value = null
        _isPlaying.value = false
        _isBuffering.value = false
        _isPlaybackEnded.value = false
    }

    companion object {
        const val TAG = "PlayerManager"

        /**
         * Infers MIME type from direct response data (url_type, content_type, filename, or URI path).
         * This prevents ExoPlayer from failing when Cloudflare R2, S3, or CDN token URLs lack
         * a file extension in their path.
         */
        fun detectMimeType(directData: DirectPlayData): String? {
            val urlType = directData.urlType.lowercase()
            val contentType = directData.contentType?.lowercase() ?: ""
            val playbackUrl = directData.playbackUrl.lowercase()

            return when {
                urlType == "hls" ||
                contentType.contains("mpegurl") ||
                contentType.contains("m3u8") ||
                playbackUrl.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8

                urlType == "dash" ||
                contentType.contains("dash") ||
                contentType.contains("mpd") ||
                playbackUrl.contains(".mpd") -> MimeTypes.APPLICATION_MPD

                contentType.contains("matroska") ||
                playbackUrl.contains(".mkv") ||
                playbackUrl.contains("mkv%22") ||
                playbackUrl.contains("filename%3d%22") && playbackUrl.contains(".mkv") -> MimeTypes.VIDEO_MATROSKA

                contentType.contains("mp4") ||
                playbackUrl.contains(".mp4") ||
                playbackUrl.contains("mp4%22") ||
                playbackUrl.contains("filename%3d%22") && playbackUrl.contains(".mp4") -> MimeTypes.VIDEO_MP4

                contentType.contains("webm") ||
                playbackUrl.contains(".webm") -> MimeTypes.VIDEO_WEBM

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
