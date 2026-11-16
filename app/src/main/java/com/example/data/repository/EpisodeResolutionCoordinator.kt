package com.example.data.repository

import android.util.Log
import com.example.data.local.EpisodeEntry
import com.example.data.remote.DirectPlayData
import com.example.data.remote.ProphecySourceDto
import com.example.data.streaming.MonotonicMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Process-wide single-flight registry shared by repository instances with priority & promotion. */
@OptIn(ExperimentalCoroutinesApi::class)
object EpisodeResolutionCoordinator {
    enum class Priority {
        FOREGROUND,
        BACKGROUND
    }

    private val foregroundDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val backgroundDispatcher = Dispatchers.IO.limitedParallelism(2)
    private val scope = CoroutineScope(SupervisorJob() + foregroundDispatcher)

    private class InFlightFlight<T>(
        initiallyBackgroundOnly: Boolean
    ) {
        val totalWaiters = AtomicInteger(0)
        val foregroundWaiters = AtomicInteger(0)
        val promotedToForeground = AtomicBoolean(!initiallyBackgroundOnly)
        val promotionSignal = CompletableDeferred<Unit>()
        @Volatile var retired: Boolean = false
        @Volatile var deferred: Deferred<T>? = null

        fun promote() {
            promotedToForeground.set(true)
            promotionSignal.complete(Unit)
        }
    }

    private val inFlight = ConcurrentHashMap<String, InFlightFlight<Any?>>()
    private val activeForegroundCount = AtomicInteger(0)
    private val _foregroundActiveFlow = MutableStateFlow(false)
    val foregroundActiveFlow: StateFlow<Boolean> = _foregroundActiveFlow.asStateFlow()

    fun beginForegroundResolution() {
        activeForegroundCount.incrementAndGet()
        _foregroundActiveFlow.value = true
    }

    fun endForegroundResolution() {
        val remaining = activeForegroundCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        if (remaining <= 0) {
            _foregroundActiveFlow.value = false
        }
    }

    fun isForegroundResolutionActive(): Boolean = activeForegroundCount.get() > 0

    suspend fun awaitForegroundClear() {
        if (isForegroundResolutionActive()) {
            _foregroundActiveFlow.first { !it }
        }
    }

    private suspend fun awaitBackgroundResumeOrPromotion(flight: InFlightFlight<*>) {
        if (flight.promotedToForeground.get() || !isForegroundResolutionActive()) return
        coroutineScope {
            val fgClearJob = async(foregroundDispatcher) {
                _foregroundActiveFlow.first { !it }
            }
            select<Unit> {
                flight.promotionSignal.onAwait {
                    fgClearJob.cancel()
                }
                fgClearJob.onAwait { }
            }
        }
    }

    suspend fun <T> resolve(key: String, block: suspend () -> T): T =
        resolve(key = key, priority = Priority.FOREGROUND, block = block)

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> resolve(
        key: String,
        priority: Priority = Priority.FOREGROUND,
        block: suspend () -> T
    ): T {
        val flight: InFlightFlight<T>
        val isCreator: Boolean
        synchronized(inFlight) {
            val existing = inFlight[key] as? InFlightFlight<T>
            if (existing != null && !existing.retired && existing.deferred?.isCancelled != true) {
                flight = existing
                flight.totalWaiters.incrementAndGet()
                if (priority == Priority.FOREGROUND) {
                    flight.foregroundWaiters.incrementAndGet()
                    flight.promote()
                }
                isCreator = false
            } else {
                flight = InFlightFlight<T>(initiallyBackgroundOnly = priority == Priority.BACKGROUND)
                flight.totalWaiters.incrementAndGet()
                if (priority == Priority.FOREGROUND) {
                    flight.foregroundWaiters.incrementAndGet()
                    flight.promote()
                }
                flight.deferred = scope.async(foregroundDispatcher) {
                    if (!flight.promotedToForeground.get()) {
                        awaitBackgroundResumeOrPromotion(flight)
                    }
                    val execDispatcher = if (flight.promotedToForeground.get()) {
                        foregroundDispatcher
                    } else {
                        backgroundDispatcher
                    }
                    withContext(execDispatcher) {
                        block()
                    }
                }.also { created ->
                    created.invokeOnCompletion {
                        synchronized(inFlight) {
                            flight.retired = true
                            inFlight.remove(key, flight as InFlightFlight<Any?>)
                        }
                    }
                }
                inFlight[key] = flight as InFlightFlight<Any?>
                isCreator = true
            }
        }

        if (!isCreator) {
            com.example.data.metrics.DatabaseMetricsTracker.getInstance().recordDuplicateRequestPrevented()
        }

        val deferred = flight.deferred ?: error("In-flight resolution deferred was not initialized")
        if (priority == Priority.FOREGROUND) {
            beginForegroundResolution()
        }
        return try {
            deferred.await()
        } finally {
            var toCancel: Deferred<T>? = null
            synchronized(inFlight) {
                if (priority == Priority.FOREGROUND) {
                    flight.foregroundWaiters.updateAndGet { (it - 1).coerceAtLeast(0) }
                }
                val remaining = flight.totalWaiters.updateAndGet { (it - 1).coerceAtLeast(0) }
                if (remaining <= 0 && flight.foregroundWaiters.get() <= 0) {
                    flight.retired = true
                    inFlight.remove(key, flight as InFlightFlight<Any?>)
                    toCancel = deferred
                }
            }
            toCancel?.cancel()
            if (priority == Priority.FOREGROUND) {
                endForegroundResolution()
            }
        }
    }
}

data class VerifiedEpisodePlayback(
    val source: ProphecySourceDto,
    val directData: DirectPlayData,
    val candidates: List<ProphecySourceDto>,
    val cacheTier: String = "BACKEND_RESOLVE",
    val resolveDurationMs: Long = 0L
)

suspend fun AnimeRepository.resolveVerifiedPlaybackForEpisode(
    animeOrBackendId: String,
    episode: EpisodeEntry,
    preferredSourceId: String? = null,
    forceResolveFresh: Boolean = false,
    isForeground: Boolean = true,
    preferredProviderId: String? = null
): Result<VerifiedEpisodePlayback> {
    val targetAnimeId = episode.animeId.takeIf { it.isNotBlank() } ?: animeOrBackendId
    if (targetAnimeId.isBlank() || episode.id.isBlank()) {
        return Result.failure(IllegalArgumentException("Episode identity is incomplete"))
    }
    val normPreferred = SourceIdentityValidator.normalizeId(preferredSourceId)
    val normProvider = SourceIdentityValidator.normalizeId(preferredProviderId)
    val key = buildString {
        append(targetAnimeId.trim()).append('|')
            .append(episode.seasonNumber).append('|')
            .append(episode.episodeNumber).append('|')
            .append(episode.id.trim()).append('|')
            .append(normPreferred).append('|')
            .append(normProvider).append('|')
            .append(forceResolveFresh)
    }
    val priority = if (isForeground) {
        EpisodeResolutionCoordinator.Priority.FOREGROUND
    } else {
        EpisodeResolutionCoordinator.Priority.BACKGROUND
    }
    if (isForeground) {
        EpisodeResolutionCoordinator.beginForegroundResolution()
    }
    return try {
        EpisodeResolutionCoordinator.resolve("verified:$key", priority = priority) {
            val startedAt = MonotonicMetrics.nowMs()
            try {
                withTimeout(45_000L) {
                    val resolved = resolvePlayableStream(
                        animeId = targetAnimeId,
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber,
                        catalogEpisodeId = episode.id,
                        preferredSourceId = normPreferred.takeIf { it.isNotEmpty() },
                        forceResolveFresh = forceResolveFresh,
                        preferredProviderId = normProvider.takeIf { it.isNotEmpty() },
                        allowBypassBackoff = isForeground
                    ).getOrElse { return@withTimeout Result.failure(it) }

                    MonotonicMetrics.recordDuration(
                        "episode_resolution_total_ms",
                        startedAt,
                        "tier=${resolved.cacheTier} candidates=${resolved.candidates.size}"
                    )
                    Result.success(
                        VerifiedEpisodePlayback(
                            source = resolved.source,
                            directData = resolved.directData,
                            candidates = resolved.candidates,
                            cacheTier = resolved.cacheTier,
                            resolveDurationMs = resolved.resolveDurationMs
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(
                    "EpisodeResolution",
                    "episode_resolution_total_ms=${MonotonicMetrics.nowMs() - startedAt} outcome=failed category=${e::class.simpleName}"
                )
                Result.failure(e)
            }
        }
    } finally {
        if (isForeground) {
            EpisodeResolutionCoordinator.endForegroundResolution()
        }
    }
}
