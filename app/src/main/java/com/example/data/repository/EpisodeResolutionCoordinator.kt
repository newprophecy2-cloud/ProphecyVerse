package com.example.data.repository

import android.util.Log
import com.example.data.local.EpisodeEntry
import com.example.data.remote.DirectPlayData
import com.example.data.remote.ProphecySourceDto
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
    val candidates: List<ProphecySourceDto>
)

suspend fun AnimeRepository.resolveVerifiedPlaybackForEpisode(
    backendAnimeId: String,
    episode: EpisodeEntry,
    preferredSourceId: String? = null
): Result<VerifiedEpisodePlayback> {
    if (backendAnimeId.isBlank() || episode.id.isBlank()) {
        return Result.failure(IllegalArgumentException("Episode identity is incomplete"))
    }
    val key = buildString {
        append(backendAnimeId).append('|')
            .append(episode.seasonNumber).append('|')
            .append(episode.id).append('|')
            .append(preferredSourceId.orEmpty())
    }
    return EpisodeResolutionCoordinator.resolve("verified:$key") {
        val startedAt = System.currentTimeMillis()
        try {
            withTimeout(18_000L) {
                val sourceResult = fetchEpisodeSourcesForCanonical(backendAnimeId, episode)
                val sources = sourceResult.getOrElse { return@withTimeout Result.failure(it) }
                    .filter { it.hasUsableSourceId }
                    .distinctBy { it.stableIdentity }
                val ordered = if (preferredSourceId.isNullOrBlank()) {
                    sources.sortedWith(compareByDescending<ProphecySourceDto> { it.downloadable == true })
                } else {
                    sources.sortedWith(
                        compareBy<ProphecySourceDto> { if (it.id == preferredSourceId) 0 else 1 }
                            .thenByDescending { it.downloadable == true }
                    )
                }
                var lastError: Throwable? = null
                for (source in ordered) {
                    val direct = resolveDirectPlayback(source.id).getOrNull()
                    if (direct == null) {
                        lastError = IllegalStateException("Source resolution was unavailable")
                        continue
                    }
                    if (direct.sourceId != source.id || direct.playbackUrl.isBlank()) {
                        lastError = IllegalStateException("Resolved source identity did not match")
                        continue
                    }
                    Log.i("AnimeRepository", "episode_resolution_total_ms=${System.currentTimeMillis() - startedAt} candidates=${ordered.size}")
                    return@withTimeout Result.success(VerifiedEpisodePlayback(source, direct, ordered))
                }
                Result.failure(lastError ?: IllegalStateException("No verified playable source available"))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i("AnimeRepository", "episode_resolution_total_ms=${System.currentTimeMillis() - startedAt} outcome=failed category=${e::class.simpleName}")
            Result.failure(e)
        }
    }
}
