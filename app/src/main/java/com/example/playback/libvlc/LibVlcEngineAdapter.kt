package com.example.playback.libvlc

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.playback.core.EngineTrackInfo
import com.example.playback.core.PlaybackEngine
import com.example.playback.core.PlaybackEngineError
import com.example.playback.core.PlaybackEngineState
import com.example.playback.core.PlaybackFailureCategory
import com.example.playback.core.PlaybackRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Single-owner VideoLAN LibVLC Android engine adapter (libvlc-all:3.6.5).
 * Translates LibVLC native events into MovieBox [PlaybackEngineState] and
 * preserves required HTTP request headers without logging sensitive tokens/URLs.
 */
class LibVlcEngineAdapter(
    context: Context
) : PlaybackEngine {

    private val appContext = context.applicationContext
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var attachedLayout: VLCVideoLayout? = null
    private var pendingStartPositionMs: Long = 0L

    private val _state = MutableStateFlow(PlaybackEngineState.IDLE)
    override val state: StateFlow<PlaybackEngineState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<EngineTrackInfo>>(emptyList())
    override val audioTracks: StateFlow<List<EngineTrackInfo>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<EngineTrackInfo>>(emptyList())
    override val subtitleTracks: StateFlow<List<EngineTrackInfo>> = _subtitleTracks.asStateFlow()

    private val _error = MutableStateFlow<PlaybackEngineError?>(null)
    override val error: StateFlow<PlaybackEngineError?> = _error.asStateFlow()

    private val eventListener = MediaPlayer.EventListener { event ->
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                _error.value = null
                _state.value = PlaybackEngineState.OPENING_MEDIA
            }
            MediaPlayer.Event.Buffering -> {
                if (event.buffering < 100f && _state.value != PlaybackEngineState.PAUSED) {
                    _state.value = PlaybackEngineState.BUFFERING
                } else if (event.buffering >= 100f && mediaPlayer?.isPlaying == true) {
                    _state.value = PlaybackEngineState.PLAYING
                }
            }
            MediaPlayer.Event.Playing -> {
                _state.value = PlaybackEngineState.PLAYING
                val seekTarget = pendingStartPositionMs
                if (seekTarget > 0L) {
                    pendingStartPositionMs = 0L
                    mediaPlayer?.time = seekTarget
                }
                refreshTracks()
            }
            MediaPlayer.Event.Paused -> {
                _state.value = PlaybackEngineState.PAUSED
            }
            MediaPlayer.Event.Stopped -> {
                if (_state.value != PlaybackEngineState.ERROR && _state.value != PlaybackEngineState.RELEASED) {
                    _state.value = PlaybackEngineState.IDLE
                }
            }
            MediaPlayer.Event.EndReached -> {
                _state.value = PlaybackEngineState.ENDED
            }
            MediaPlayer.Event.TimeChanged -> {
                _positionMs.value = event.timeChanged.coerceAtLeast(0L)
            }
            MediaPlayer.Event.LengthChanged -> {
                _durationMs.value = event.lengthChanged.coerceAtLeast(0L)
            }
            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted,
            MediaPlayer.Event.ESSelected -> {
                refreshTracks()
            }
            MediaPlayer.Event.EncounteredError -> {
                _state.value = PlaybackEngineState.ERROR
                _error.value = PlaybackEngineError(
                    category = PlaybackFailureCategory.MEDIA_OPEN_FAILED,
                    message = "LibVLC could not open or decode this media stream.",
                    retryable = true
                )
            }
        }
    }

    @Synchronized
    override fun initialize() {
        if (libVlc != null && mediaPlayer != null) return
        _state.value = PlaybackEngineState.INITIALIZING
        try {
            val options = arrayListOf(
                "--network-caching=1500",
                "--http-reconnect",
                "--aout=android_audiotrack"
            )
            val vlc = LibVLC(appContext, options)
            val player = MediaPlayer(vlc)
            player.setEventListener(eventListener)
            libVlc = vlc
            mediaPlayer = player
            attachedLayout?.let { layout ->
                player.attachViews(layout, null, true, false)
            }
            _state.value = PlaybackEngineState.IDLE
        } catch (t: Throwable) {
            Log.e(TAG, "LibVLC initialization failed: ${t.javaClass.simpleName}")
            _state.value = PlaybackEngineState.ERROR
            _error.value = PlaybackEngineError(
                category = PlaybackFailureCategory.PLAYER_INITIALIZATION_FAILED,
                message = "The video player engine could not be initialized on this device.",
                retryable = false
            )
        }
    }

    @Synchronized
    fun attachVideoLayout(layout: VLCVideoLayout) {
        if (attachedLayout === layout) return
        attachedLayout = layout
        val player = mediaPlayer ?: return
        runCatching {
            player.detachViews()
            player.attachViews(layout, null, true, false)
        }
    }

    @Synchronized
    fun detachVideoLayout() {
        attachedLayout = null
        runCatching { mediaPlayer?.detachViews() }
    }

    override fun prepare(request: PlaybackRequest) {
        val rawUri = request.mediaUri.trim()
        val parsed = runCatching { Uri.parse(rawUri) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        if (rawUri.isEmpty() || parsed == null || scheme !in SUPPORTED_SCHEMES) {
            _state.value = PlaybackEngineState.ERROR
            _error.value = PlaybackEngineError(
                category = PlaybackFailureCategory.INVALID_MEDIA_URL,
                message = "The returned media URL is malformed or uses an unsupported protocol.",
                retryable = false
            )
            return
        }

        initialize()
        val vlc = libVlc ?: return
        val player = mediaPlayer ?: return

        _error.value = null
        _positionMs.value = 0L
        _durationMs.value = 0L
        pendingStartPositionMs = request.startPositionMs.coerceAtLeast(0L)
        _state.value = PlaybackEngineState.SOURCE_READY

        val media = Media(vlc, parsed)
        try {
            media.setHWDecoderEnabled(true, false)
            request.requestHeaders.entries.forEach { (k, v) ->
                val cleanVal = v.trim()
                if (cleanVal.isEmpty()) return@forEach
                when (k.lowercase()) {
                    "user-agent" -> media.addOption(":http-user-agent=$cleanVal")
                    "referer", "referrer" -> media.addOption(":http-referrer=$cleanVal")
                }
            }
            request.subtitleTracks.forEach { sub ->
                val subUri = runCatching { Uri.parse(sub.uri) }.getOrNull()
                if (subUri != null) {
                    media.addSlave(
                        org.videolan.libvlc.interfaces.IMedia.Slave(
                            org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle,
                            4,
                            sub.uri
                        )
                    )
                }
            }
            player.media = media
            player.play()
        } finally {
            media.release()
        }
    }

    override fun play() {
        mediaPlayer?.play()
    }

    override fun pause() {
        mediaPlayer?.pause()
    }

    override fun seekTo(positionMs: Long) {
        val player = mediaPlayer ?: return
        _state.value = PlaybackEngineState.SEEKING
        player.time = positionMs.coerceAtLeast(0L)
    }

    override fun setSpeed(rate: Float) {
        mediaPlayer?.rate = rate.coerceIn(0.25f, 3.0f)
    }

    override fun selectAudioTrack(trackId: Int) {
        mediaPlayer?.setAudioTrack(trackId)
        refreshTracks()
    }

    override fun selectSubtitleTrack(trackId: Int) {
        mediaPlayer?.setSpuTrack(trackId)
        refreshTracks()
    }

    override fun setAudioDelayMs(delayMs: Long) {
        mediaPlayer?.setAudioDelay(delayMs * 1000L)
    }

    override fun setSubtitleDelayMs(delayMs: Long) {
        mediaPlayer?.setSpuDelay(delayMs * 1000L)
    }

    override fun stop() {
        runCatching { mediaPlayer?.stop() }
        _state.value = PlaybackEngineState.IDLE
    }

    @Synchronized
    override fun release() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.setEventListener(null)
            mediaPlayer?.detachViews()
            mediaPlayer?.release()
        }
        runCatching { libVlc?.release() }
        attachedLayout = null
        mediaPlayer = null
        libVlc = null
        _state.value = PlaybackEngineState.RELEASED
    }

    private fun refreshTracks() {
        val player = mediaPlayer ?: return
        val currentAudio = player.audioTrack
        val currentSpu = player.spuTrack
        _audioTracks.value = player.audioTracks?.map { t ->
            EngineTrackInfo(
                id = t.id,
                name = t.name ?: "Audio ${t.id}",
                language = null,
                selected = t.id == currentAudio
            )
        }.orEmpty()
        _subtitleTracks.value = player.spuTracks?.map { t ->
            EngineTrackInfo(
                id = t.id,
                name = t.name ?: "Subtitle ${t.id}",
                language = null,
                selected = t.id == currentSpu
            )
        }.orEmpty()
    }

    companion object {
        private const val TAG = "LibVlcEngineAdapter"
        private val SUPPORTED_SCHEMES = setOf("http", "https", "rtsp", "rtmp", "file", "content")
    }
}
