package com.example.player

import com.example.data.metrics.PlaybackDiagnosticEventType
import com.example.data.metrics.PlaybackDiagnosticsLogger

enum class PlaybackHealthState {
    IDLE,
    INITIAL_BUFFERING,
    REBUFFERING,
    ACTIVE_TRANSFER,
    SLOW_PROGRESS,
    NO_PROGRESS,
    HTTP_ERROR,
    EXPIRED_URL,
    DECODER_FAILURE,
    UNSUPPORTED_FORMAT,
    CONFIRMED_FAILURE
}

enum class PlaybackFailureReason {
    HTTP_EXPIRED_OR_UNAUTHORIZED,
    TRANSIENT_NETWORK_ERROR,
    STALL_NO_PROGRESS,
    DECODER_OR_UNSUPPORTED,
    OTHER
}

/**
 * Stateful per-player-attempt health monitor that separates initial buffering, rebuffering,
 * active/slow network transfer, and genuine zero-progress stalls or terminal failures.
 *
 * Slow-but-progressing downloads NEVER trigger source rediscovery or D1 URL invalidation.
 */
class PlaybackHealthMonitor(
    private val scopeKey: String = "player",
    private val sourceId: String? = null,
    private val noProgressStallThresholdSec: Int = DEFAULT_STALL_NO_PROGRESS_SECONDS,
    private val recentTransferWindowMs: Long = DEFAULT_RECENT_TRANSFER_WINDOW_MS
) {
    data class TickEvaluation(
        val state: PlaybackHealthState,
        val isInitialBuffering: Boolean,
        val isProgressing: Boolean,
        val noProgressSeconds: Int,
        val shouldTriggerStallRecovery: Boolean
    )

    var state: PlaybackHealthState = PlaybackHealthState.IDLE
        private set
    var hasBecomeReady: Boolean = false
        private set
    var noProgressSeconds: Int = 0
        private set
    var bufferingSecondsTotal: Int = 0
        private set

    private var lastObservedBytes: Long = 0L
    private var lastObservedBufferedPositionMs: Long = Long.MIN_VALUE
    private var bufferingStartedLogged: Boolean = false
    private var stallRecoveryTriggered: Boolean = false
    private var lastProgressLogSec: Int = 0

    fun markReady() {
        hasBecomeReady = true
        noProgressSeconds = 0
        bufferingSecondsTotal = 0
        bufferingStartedLogged = false
        stallRecoveryTriggered = false
        lastProgressLogSec = 0
        state = PlaybackHealthState.IDLE
    }

    fun evaluateTick(
        isPlaying: Boolean,
        isBuffering: Boolean,
        bytesTransferred: Long,
        bufferedPositionMs: Long,
        lastTransferAtMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): TickEvaluation {
        if (isPlaying) {
            hasBecomeReady = true
            noProgressSeconds = 0
            bufferingSecondsTotal = 0
            bufferingStartedLogged = false
            stallRecoveryTriggered = false
            lastProgressLogSec = 0
            lastObservedBytes = bytesTransferred.coerceAtLeast(lastObservedBytes)
            lastObservedBufferedPositionMs = maxOf(lastObservedBufferedPositionMs, bufferedPositionMs)
            state = PlaybackHealthState.IDLE
            return TickEvaluation(
                state = state,
                isInitialBuffering = false,
                isProgressing = true,
                noProgressSeconds = 0,
                shouldTriggerStallRecovery = false
            )
        }

        if (!isBuffering) {
            noProgressSeconds = 0
            bufferingSecondsTotal = 0
            bufferingStartedLogged = false
            lastProgressLogSec = 0
            state = PlaybackHealthState.IDLE
            return TickEvaluation(
                state = state,
                isInitialBuffering = false,
                isProgressing = false,
                noProgressSeconds = 0,
                shouldTriggerStallRecovery = false
            )
        }

        if (!bufferingStartedLogged) {
            bufferingStartedLogged = true
            PlaybackDiagnosticsLogger.log(
                type = PlaybackDiagnosticEventType.PLAYBACK_BUFFERING_STARTED,
                scopeKey = scopeKey,
                sourceId = sourceId,
                detail = "kind=${if (hasBecomeReady) "REBUFFERING" else "INITIAL_BUFFERING"}"
            )
        }

        bufferingSecondsTotal++
        val bytesAdvanced = bytesTransferred > lastObservedBytes
        val bufferedAdvanced = lastObservedBufferedPositionMs != Long.MIN_VALUE &&
            bufferedPositionMs > lastObservedBufferedPositionMs
        val hasRecentTransfer = lastTransferAtMs > 0L && (nowMs - lastTransferAtMs) < recentTransferWindowMs
        val progressing = bytesAdvanced || bufferedAdvanced || hasRecentTransfer

        lastObservedBytes = maxOf(lastObservedBytes, bytesTransferred)
        if (bufferedPositionMs >= 0L) {
            lastObservedBufferedPositionMs = maxOf(lastObservedBufferedPositionMs, bufferedPositionMs)
        }

        if (progressing) {
            noProgressSeconds = 0
            stallRecoveryTriggered = false
            state = if (bytesAdvanced) PlaybackHealthState.ACTIVE_TRANSFER else PlaybackHealthState.SLOW_PROGRESS
            if (bufferingSecondsTotal - lastProgressLogSec >= 5) {
                lastProgressLogSec = bufferingSecondsTotal
                PlaybackDiagnosticsLogger.log(
                    type = PlaybackDiagnosticEventType.PLAYBACK_BUFFERING_PROGRESSING,
                    scopeKey = scopeKey,
                    sourceId = sourceId,
                    detail = "state=${state.name} kind=${if (hasBecomeReady) "REBUFFERING" else "INITIAL_BUFFERING"} bytes=$bytesTransferred buffered_ms=$bufferedPositionMs"
                )
            }
            return TickEvaluation(
                state = state,
                isInitialBuffering = !hasBecomeReady,
                isProgressing = true,
                noProgressSeconds = 0,
                shouldTriggerStallRecovery = false
            )
        }

        noProgressSeconds++
        val shouldTrigger = if (!stallRecoveryTriggered && noProgressSeconds >= noProgressStallThresholdSec) {
            stallRecoveryTriggered = true
            state = PlaybackHealthState.NO_PROGRESS
            PlaybackDiagnosticsLogger.log(
                type = PlaybackDiagnosticEventType.PLAYBACK_STALL_CONFIRMED,
                scopeKey = scopeKey,
                sourceId = sourceId,
                detail = "no_progress_sec=$noProgressSeconds bytes=$bytesTransferred buffered_ms=$bufferedPositionMs",
                isWarning = true
            )
            true
        } else {
            state = if (hasBecomeReady) PlaybackHealthState.REBUFFERING else PlaybackHealthState.INITIAL_BUFFERING
            false
        }

        return TickEvaluation(
            state = state,
            isInitialBuffering = !hasBecomeReady,
            isProgressing = false,
            noProgressSeconds = noProgressSeconds,
            shouldTriggerStallRecovery = shouldTrigger
        )
    }

    fun classifyError(
        failureCategory: PlayerManager.PlaybackFailureCategory,
        httpStatusCode: Int?
    ): Pair<PlaybackHealthState, PlaybackFailureReason> {
        val health = when {
            httpStatusCode in setOf(401, 403, 404, 410) -> PlaybackHealthState.EXPIRED_URL
            failureCategory == PlayerManager.PlaybackFailureCategory.HTTP -> PlaybackHealthState.HTTP_ERROR
            failureCategory == PlayerManager.PlaybackFailureCategory.DECODER ||
                failureCategory == PlayerManager.PlaybackFailureCategory.AUDIO_DECODER -> PlaybackHealthState.DECODER_FAILURE
            failureCategory == PlayerManager.PlaybackFailureCategory.CONTAINER ||
                failureCategory == PlayerManager.PlaybackFailureCategory.CONTENT_TYPE ||
                failureCategory == PlayerManager.PlaybackFailureCategory.MANIFEST -> PlaybackHealthState.UNSUPPORTED_FORMAT
            else -> PlaybackHealthState.CONFIRMED_FAILURE
        }
        state = health
        val reason = when (health) {
            PlaybackHealthState.EXPIRED_URL -> PlaybackFailureReason.HTTP_EXPIRED_OR_UNAUTHORIZED
            PlaybackHealthState.HTTP_ERROR -> when (httpStatusCode) {
                408, 429, in 500..599 -> PlaybackFailureReason.TRANSIENT_NETWORK_ERROR
                else -> PlaybackFailureReason.HTTP_EXPIRED_OR_UNAUTHORIZED
            }
            PlaybackHealthState.DECODER_FAILURE,
            PlaybackHealthState.UNSUPPORTED_FORMAT -> PlaybackFailureReason.DECODER_OR_UNSUPPORTED
            PlaybackHealthState.CONFIRMED_FAILURE -> when (failureCategory) {
                PlayerManager.PlaybackFailureCategory.NETWORK,
                PlayerManager.PlaybackFailureCategory.NETWORK_TIMEOUT -> PlaybackFailureReason.TRANSIENT_NETWORK_ERROR
                else -> PlaybackFailureReason.OTHER
            }
            else -> PlaybackFailureReason.OTHER
        }
        return health to reason
    }

    companion object {
        const val DEFAULT_STALL_NO_PROGRESS_SECONDS = 12
        const val DEFAULT_RECENT_TRANSFER_WINDOW_MS = 5_000L
    }
}
