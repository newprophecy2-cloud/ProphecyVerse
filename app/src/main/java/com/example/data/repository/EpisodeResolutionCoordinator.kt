package com.example.data.repository

import android.util.Log
import com.example.data.local.EpisodeEntry
import com.example.data.remote.DirectPlayData
import com.example.data.remote.ProphecySourceDto
import com.example.data.streaming.MonotonicMetrics
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** Process-wide single-flight registry shared by repository instances. */
object EpisodeResolutionCoordinator {
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<Any?>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> resolve(key: String, block: suspend () -> T): T {
        val created = scope.async(start = CoroutineStart.LAZY) { block() }
        val existing = inFlight.putIfAbsent(key, created as Deferred<Any?>)
        val selected = (existing ?: created as Deferred<Any?>) as Deferred<T>
        if (existing == null) {
            created.invokeOnCompletion { inFlight.remove(key, created as Deferred<Any?>) }
        } else {
            created.cancel()
        }
        return selected.await()
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
    forceResolveFresh: Boolean = false
): Result<VerifiedEpisodePlayback> {
    val targetAnimeId = episode.animeId.takeIf { it.isNotBlank() } ?: animeOrBackendId
    if (targetAnimeId.isBlank() || episode.id.isBlank()) {
        return Result.failure(IllegalArgumentException("Episode identity is incomplete"))
    }
    val key = buildString {
        append(targetAnimeId).append('|')
            .append(episode.seasonNumber).append('|')
            .append(episode.episodeNumber).append('|')
            .append(episode.id).append('|')
            .append(preferredSourceId.orEmpty()).append('|')
            .append(forceResolveFresh)
    }
    return EpisodeResolutionCoordinator.resolve("verified:$key") {
        val startedAt = MonotonicMetrics.nowMs()
        try {
            withTimeout(22_000L) {
                val resolved = resolvePlayableStream(
                    animeId = targetAnimeId,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    catalogEpisodeId = episode.id,
                    preferredSourceId = preferredSourceId,
                    forceResolveFresh = forceResolveFresh
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(
                "EpisodeResolution",
                "episode_resolution_total_ms=${MonotonicMetrics.nowMs() - startedAt} outcome=failed category=${e::class.simpleName}"
            )
            Result.failure(e)
        }
    }
}
