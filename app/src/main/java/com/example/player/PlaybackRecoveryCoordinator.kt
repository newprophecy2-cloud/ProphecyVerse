package com.example.player

import com.example.data.metrics.PlaybackDiagnosticEventType
import com.example.data.metrics.PlaybackDiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Identity key for deduplicating playback recovery callbacks within a single episode/source generation.
 */
data class PlaybackRecoveryKey(
    val animeId: String,
    val episodeId: String,
    val sourceId: String,
    val playbackGeneration: Long
)

/**
 * Logical session retry budget that tracks retries across transient READY states within the same
 * episode/source session and only resets when a genuinely new episode or explicit user source
 * session begins.
 */
class LogicalPlaybackSessionRetryTracker(
    val maxSessionRetries: Int = DEFAULT_MAX_SESSION_RETRIES
) {
    private var activeSessionId: String? = null
    private val retryCountsBySession = LinkedHashMap<String, Int>(8, 0.75f, true)

    @Synchronized
    fun beginNewSession(sessionKey: String): Int {
        activeSessionId = sessionKey
        retryCountsBySession[sessionKey] = 0
        while (retryCountsBySession.size > 16) {
            val eldest = retryCountsBySession.keys.firstOrNull() ?: break
            if (eldest != sessionKey) retryCountsBySession.remove(eldest) else break
        }
        return 0
    }

    @Synchronized
    fun onReadyStateReached() {
        // Intentional no-op: reaching STATE_READY never resets the session retry budget.
    }

    @Synchronized
    fun currentRetryCount(sessionKey: String? = activeSessionId): Int {
        val key = sessionKey ?: return 0
        return retryCountsBySession[key] ?: 0
    }

    @Synchronized
    fun canRetry(sessionKey: String? = activeSessionId): Boolean {
        val key = sessionKey ?: return false
        return (retryCountsBySession[key] ?: 0) < maxSessionRetries
    }

    @Synchronized
    fun consumeRetry(sessionKey: String? = activeSessionId): Int? {
        val key = sessionKey ?: return null
        val current = retryCountsBySession[key] ?: 0
        if (current >= maxSessionRetries) return null
        val next = current + 1
        retryCountsBySession[key] = next
        return next
    }

    @Synchronized
    fun resetAll() {
        activeSessionId = null
        retryCountsBySession.clear()
    }

    companion object {
        const val DEFAULT_MAX_SESSION_RETRIES = 2
    }
}

/**
 * Single centralized recovery coordinator shared by all PlayerManager failure and retry callbacks
 * (`onPlaybackFailed`, `onRetryNeeded`, and `PlaybackHealthMonitor` zero-progress stall signals).
 */
class PlaybackRecoveryCoordinator(
    private val scope: CoroutineScope,
    val maxSessionRecoveries: Int = DEFAULT_MAX_SESSION_RECOVERIES
) {
    private val lock = Any()
    private var activeGeneration: Long = 0L
    private var activeRecoveryKey: PlaybackRecoveryKey? = null
    private var activeRecoveryJob: Job? = null
    private val sessionRecoveryCounts = HashMap<Long, Int>()
    private val completedRecoveryKeys = HashSet<PlaybackRecoveryKey>()

    fun beginGeneration(generation: Long) {
        val jobToCancel: Job?
        synchronized(lock) {
            activeGeneration = generation
            jobToCancel = activeRecoveryJob
            activeRecoveryJob = null
            activeRecoveryKey = null
            sessionRecoveryCounts.keys.retainAll { it == generation }
            completedRecoveryKeys.removeAll { it.playbackGeneration != generation }
        }
        jobToCancel?.cancel()
    }

    fun cancelAll() {
        val jobToCancel: Job?
        synchronized(lock) {
            activeGeneration++
            jobToCancel = activeRecoveryJob
            activeRecoveryJob = null
            activeRecoveryKey = null
            sessionRecoveryCounts.clear()
            completedRecoveryKeys.clear()
        }
        jobToCancel?.cancel()
    }

    fun sessionRecoveryCount(generation: Long): Int = synchronized(lock) {
        sessionRecoveryCounts[generation] ?: 0
    }

    /**
     * Launches a single-flight recovery operation for [key] if [key.playbackGeneration] matches the
     * active generation, no duplicate recovery is already active for [key], and the bounded
     * session recovery budget has not been exhausted.
     */
    fun triggerRecovery(
        key: PlaybackRecoveryKey,
        reason: PlaybackFailureReason,
        allowRepeatForSameSource: Boolean = false,
        onBudgetExhausted: (() -> Unit)? = null,
        block: suspend (attemptNumber: Int) -> Unit
    ): Boolean {
        val scopeLabel = "${key.animeId}:${key.episodeId}:gen=${key.playbackGeneration}"
        val attemptNumber: Int
        synchronized(lock) {
            if (key.playbackGeneration != activeGeneration) {
                PlaybackDiagnosticsLogger.log(
                    type = PlaybackDiagnosticEventType.PLAYBACK_RECOVERY_SKIPPED_DUPLICATE,
                    scopeKey = scopeLabel,
                    sourceId = key.sourceId,
                    detail = "reason=STALE_GENERATION active=$activeGeneration"
                )
                return false
            }
            if (activeRecoveryJob?.isActive == true && activeRecoveryKey == key) {
                PlaybackDiagnosticsLogger.log(
                    type = PlaybackDiagnosticEventType.PLAYBACK_RECOVERY_SKIPPED_DUPLICATE,
                    scopeKey = scopeLabel,
                    sourceId = key.sourceId,
                    detail = "reason=IN_FLIGHT_SAME_KEY trigger=${reason.name}"
                )
                return false
            }
            if (!allowRepeatForSameSource && key in completedRecoveryKeys) {
                PlaybackDiagnosticsLogger.log(
                    type = PlaybackDiagnosticEventType.PLAYBACK_RECOVERY_SKIPPED_DUPLICATE,
                    scopeKey = scopeLabel,
                    sourceId = key.sourceId,
                    detail = "reason=ALREADY_RECOVERED_KEY trigger=${reason.name}"
                )
                return false
            }
            val currentAttempts = sessionRecoveryCounts[key.playbackGeneration] ?: 0
            if (currentAttempts >= maxSessionRecoveries) {
                PlaybackDiagnosticsLogger.log(
                    type = PlaybackDiagnosticEventType.PLAYBACK_SESSION_FAILED,
                    scopeKey = scopeLabel,
                    sourceId = key.sourceId,
                    detail = "reason=RECOVERY_BUDGET_EXHAUSTED attempts=$currentAttempts trigger=${reason.name}",
                    isWarning = true
                )
                onBudgetExhausted?.invoke()
                return false
            }
            attemptNumber = currentAttempts + 1
            sessionRecoveryCounts[key.playbackGeneration] = attemptNumber
            completedRecoveryKeys.add(key)
            activeRecoveryKey = key
        }

        PlaybackDiagnosticsLogger.log(
            type = PlaybackDiagnosticEventType.PLAYBACK_RECOVERY_STARTED,
            scopeKey = scopeLabel,
            sourceId = key.sourceId,
            detail = "attempt=$attemptNumber/$maxSessionRecoveries trigger=${reason.name}"
        )

        val launched = scope.launch {
            try {
                block(attemptNumber)
            } finally {
                synchronized(lock) {
                    if (activeRecoveryKey == key) {
                        activeRecoveryKey = null
                        activeRecoveryJob = null
                    }
                }
            }
        }
        synchronized(lock) {
            if (key.playbackGeneration == activeGeneration && activeRecoveryKey == key) {
                activeRecoveryJob = launched
            } else {
                launched.cancel()
            }
        }
        return true
    }

    companion object {
        const val DEFAULT_MAX_SESSION_RECOVERIES = 4
    }
}
