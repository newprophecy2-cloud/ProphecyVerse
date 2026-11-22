package com.example.playback.core

import kotlinx.coroutines.flow.StateFlow

enum class PlaybackEngineState {
    IDLE,
    INITIALIZING,
    RESOLVING_SOURCE,
    SOURCE_READY,
    OPENING_MEDIA,
    BUFFERING,
    PLAYING,
    PAUSED,
    SEEKING,
    ENDED,
    RETRYING,
    ERROR,
    RELEASED
}

enum class PlaybackFailureCategory {
    INVALID_EPISODE,
    EPISODE_UNVERIFIED,
    SOURCE_NOT_FOUND,
    SOURCE_DISABLED,
    SOURCE_RESOLUTION_FAILED,
    INVALID_MEDIA_URL,
    NETWORK_FAILURE,
    HTTP_ACCESS_FAILURE,
    PLAYER_INITIALIZATION_FAILED,
    MEDIA_OPEN_FAILED,
    DECODER_FAILURE,
    UNSUPPORTED_MEDIA,
    PLAYBACK_INTERRUPTED,
    UNKNOWN_PLAYBACK_ERROR
}

data class PlaybackSubtitleSpec(
    val uri: String,
    val language: String? = null,
    val label: String? = null
)

data class PlaybackRequest(
    val mediaUri: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val title: String? = null,
    val subtitleTracks: List<PlaybackSubtitleSpec> = emptyList(),
    val preferredAudioLanguage: String? = null,
    val startPositionMs: Long = 0L
)

data class EngineTrackInfo(
    val id: Int,
    val name: String,
    val language: String? = null,
    val selected: Boolean = false
)

data class PlaybackEngineError(
    val category: PlaybackFailureCategory,
    val message: String,
    val retryable: Boolean
)

interface PlaybackEngine {
    val state: StateFlow<PlaybackEngineState>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val audioTracks: StateFlow<List<EngineTrackInfo>>
    val subtitleTracks: StateFlow<List<EngineTrackInfo>>
    val error: StateFlow<PlaybackEngineError?>

    fun initialize()
    fun prepare(request: PlaybackRequest)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(rate: Float)
    fun selectAudioTrack(trackId: Int)
    fun selectSubtitleTrack(trackId: Int)
    fun setAudioDelayMs(delayMs: Long)
    fun setSubtitleDelayMs(delayMs: Long)
    fun stop()
    fun release()
}
