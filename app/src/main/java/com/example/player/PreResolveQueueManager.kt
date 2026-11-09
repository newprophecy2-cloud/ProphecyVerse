package com.example.player

import android.util.Log
import com.example.data.local.EpisodeEntry
import com.example.data.remote.DirectPlayData
import com.example.data.remote.ProphecySourceDto
import com.example.data.repository.AnimeRepository
import com.example.data.repository.SourceExpiryTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded sliding pre-resolve queue.
 * Pre-fetches sources and pre-resolves direct playback data for up to two sequential episodes
 * following the active episode.
 *
 * Runs exclusively on a single background worker (Dispatchers.IO.limitedParallelism(1))
 * to prevent bandwidth congestion or CPU contention with the active ExoPlayer instance.
 */
class PreResolveQueueManager(
    private val repository: AnimeRepository,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "PreResolveQueueManager"
        const val WINDOW_SIZE = 2
        const val CACHE_TTL_MS = 25 * 60 * 1000L // 25-minute TTL
    }

    enum class ResolutionStatus {
        IDLE,
        QUEUED,
        RESOLVING,
        READY,
        FAILED
    }

    data class PreparedEpisode(
        val backendAnimeId: String,
        val episodeId: String,
        val source: ProphecySourceDto,
        val directData: DirectPlayData,
        val resolvedAtMs: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - resolvedAtMs > CACHE_TTL_MS
    }

    // In-memory cache for resolved stream data
    private val preparedCache = ConcurrentHashMap<String, PreparedEpisode>()

    private fun cacheKey(backendAnimeId: String, episodeId: String): String = "$backendAnimeId::$episodeId"

    // Observable status map for UI badges / indicators
    private val _queueStatus = MutableStateFlow<Map<String, ResolutionStatus>>(emptyMap())
    val queueStatus: StateFlow<Map<String, ResolutionStatus>> = _queueStatus.asStateFlow()

    // Background job for queue execution
    private var workerJob: Job? = null
    private val mutex = Mutex()

    // Dedicated single-thread worker dispatcher to guarantee zero interference with active streaming
    private val queueDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * Slides the resolution window based on the currently active episode.
     * Selects up to two upcoming episodes in sequential order across seasons.
     */
    fun slideWindow(
        backendAnimeId: String,
        currentEpisodeId: String,
        allEpisodes: List<EpisodeEntry>
    ) {
        workerJob?.cancel()

        workerJob = scope.launch(queueDispatcher) {
            val currentIndex = allEpisodes.indexOfFirst { it.id == currentEpisodeId }
            if (currentIndex == -1) return@launch

            val targetEpisodes = allEpisodes
                .drop(currentIndex + 1)
                .take(WINDOW_SIZE)

            if (targetEpisodes.isEmpty()) return@launch

            // Initialize queued statuses
            updateStatuses { current ->
                val updated = current.toMutableMap()
                targetEpisodes.forEach { ep ->
                    val existingPrepared = preparedCache[cacheKey(backendAnimeId, ep.id)]
                    if (existingPrepared != null && !existingPrepared.isExpired()) {
                        updated[ep.id] = ResolutionStatus.READY
                    } else if (updated[ep.id] != ResolutionStatus.READY) {
                        updated[ep.id] = ResolutionStatus.QUEUED
                    }
                }
                updated
            }

            // Clean up cache for episodes that slipped out of window and are not the current episode
            val targetIds = targetEpisodes.map { cacheKey(backendAnimeId, it.id) }.toSet()
            preparedCache.keys.filter { it !in targetIds }.forEach {
                preparedCache.remove(it)
            }

            // Resolve only the immediate next two episodes so pre-resolution cannot compete
            // with active playback or consume unnecessary network/CPU resources.
            for ((index, ep) in targetEpisodes.withIndex()) {
                if (!isActive) break

                val cached = preparedCache[cacheKey(backendAnimeId, ep.id)]
                if (cached != null && !cached.isExpired()) {
                    updateStatusFor(ep.id, ResolutionStatus.READY)
                    continue
                }

                if (index >= 2) {
                    kotlinx.coroutines.delay(120L)
                }

                if (!isActive) break

                updateStatusFor(ep.id, ResolutionStatus.RESOLVING)

                val success = preResolveEpisode(backendAnimeId, ep.id, ep.seasonNumber, ep.episodeNumber)
                if (success) {
                    updateStatusFor(ep.id, ResolutionStatus.READY)
                } else {
                    updateStatusFor(ep.id, ResolutionStatus.FAILED)
                }
            }
        }
    }

    private suspend fun preResolveEpisode(
        backendAnimeId: String,
        episodeId: String,
        seasonNumber: Int,
        episodeNumber: Int
    ): Boolean {
        return try {
            kotlinx.coroutines.withTimeout(12000L) {
                // 1. Fetch sources
                val sourcesRes = repository.fetchEpisodeSources(
                    backendAnimeId,
                    episodeId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber
                )
                val sources = sourcesRes.getOrNull()
                if (sources.isNullOrEmpty()) {
                    Log.d(TAG, "No sources found during pre-resolve for ep $episodeId")
                    return@withTimeout false
                }

                // 2. Resolve direct playback for top candidate
                for (source in sources) {
                    if (!source.hasUsableSourceId) continue
                    val directRes = repository.resolveDirectPlayback(source.id)
                    val directData = directRes.getOrNull()
                    if (directData != null && !directData.playbackUrl.isNullOrBlank()) {
                        if (!directData.playbackUrl.startsWith("https://") &&
                            !directData.playbackUrl.startsWith("http://") &&
                            !directData.playbackUrl.startsWith("file://")) {
                            continue
                        }
                        if (SourceExpiryTime.isExpired(directData.expiresAt)) {
                            continue
                        }
                        if (directData.sourceId != source.id) continue
                        preparedCache[cacheKey(backendAnimeId, episodeId)] = PreparedEpisode(
                            backendAnimeId = backendAnimeId,
                            episodeId = episodeId,
                            source = source,
                            directData = directData,
                            resolvedAtMs = System.currentTimeMillis()
                        )
                        Log.i(TAG, "Successfully pre-resolved stream for episode $episodeId (source: ${source.id})")
                        return@withTimeout true
                    }
                }
                false
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Error during pre-resolution for episode $episodeId: ${e.message}")
            false
        }
    }

    /**
     * Retrieves pre-resolved DirectPlayData and source if available and still fresh (< 25 min TTL).
     */
    fun getPreparedSource(backendAnimeId: String, episodeId: String): PreparedEpisode? {
        val prepared = preparedCache[cacheKey(backendAnimeId, episodeId)] ?: return null
        return if (!prepared.isExpired()) prepared else {
            preparedCache.remove(cacheKey(backendAnimeId, episodeId))
            null
        }
    }

    private fun updateStatusFor(episodeId: String, status: ResolutionStatus) {
        updateStatuses { map ->
            map.toMutableMap().apply { put(episodeId, status) }
        }
    }

    private fun updateStatuses(transform: (Map<String, ResolutionStatus>) -> Map<String, ResolutionStatus>) {
        _queueStatus.value = transform(_queueStatus.value)
    }

    fun clear() {
        workerJob?.cancel()
        workerJob = null
        preparedCache.clear()
        _queueStatus.value = emptyMap()
    }
}
