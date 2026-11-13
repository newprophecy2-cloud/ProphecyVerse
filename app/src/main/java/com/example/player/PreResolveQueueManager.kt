package com.example.player

import android.util.Log
import com.example.data.local.EpisodeEntry
import com.example.data.remote.DirectPlayData
import com.example.data.remote.ProphecySourceDto
import com.example.data.repository.AnimeRepository
import com.example.data.repository.EpisodeSourceCacheIdentity
import com.example.data.repository.SourceExpiryTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** Serialized, bounded-batch pre-resolve worker. Signed URLs stay memory-only. */
class PreResolveQueueManager(private val repository: AnimeRepository, private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "PreResolveQueueManager"
        const val CACHE_TTL_MS = 25 * 60 * 1000L
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 3_000L
        fun identity(backendAnimeId: String, episode: EpisodeEntry): String =
            EpisodeSourceCacheIdentity.key(backendAnimeId, episode.seasonNumber, episode.id)
        fun completionStatus(verifiedPlayable: Boolean): ResolutionStatus =
            if (verifiedPlayable) ResolutionStatus.VERIFIED_PLAYABLE else ResolutionStatus.FAILED
    }

    enum class ResolutionStatus { NOT_STARTED, QUEUED, RESOLVING, RESOLVED, VERIFIED_PLAYABLE, FAILED, RETRY_PENDING, EXPIRED }

    data class PreparedEpisode(
        val backendAnimeId: String,
        val episodeId: String,
        val seasonNumber: Int,
        val source: ProphecySourceDto,
        val directData: DirectPlayData,
        val resolvedAtMs: Long
    ) {
        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
            nowMs - resolvedAtMs > CACHE_TTL_MS || SourceExpiryTime.isExpired(directData.expiresAt, nowMs)
    }

    private val preparedCache = ConcurrentHashMap<String, PreparedEpisode>()
    private val _queueStatus = MutableStateFlow<Map<String, ResolutionStatus>>(emptyMap())
    val queueStatus: StateFlow<Map<String, ResolutionStatus>> = _queueStatus.asStateFlow()
    private var workerJob: Job? = null
    @Volatile private var queueGeneration: Long = 0L
    private val queueDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Queue the next 17 canonical episodes after the current episode in the same season. */
    fun slideWindow(backendAnimeId: String, currentEpisode: EpisodeEntry, allEpisodes: List<EpisodeEntry>) {
        val upcoming = PreResolvePlan.upcoming(currentEpisode, allEpisodes)
        val generation = ++queueGeneration
        if (upcoming.isEmpty()) {
            workerJob?.cancel()
            workerJob = null
            updateStatuses { statuses -> statuses.mapValues { (_, state) ->
                if (state == ResolutionStatus.RESOLVING || state == ResolutionStatus.QUEUED) ResolutionStatus.RETRY_PENDING else state
            } }
            return
        }
        workerJob?.cancel()
        val targetKeys = upcoming.associateBy { identity(backendAnimeId, it) }
        updateStatuses { prior ->
            val next = prior.mapValues { (_, state) ->
                if (state == ResolutionStatus.RESOLVING || state == ResolutionStatus.QUEUED) ResolutionStatus.RETRY_PENDING else state
            }.toMutableMap()
            targetKeys.forEach { (key, _) ->
                val prepared = preparedCache[key]
                next[key] = when {
                    prepared != null && !prepared.isExpired() -> ResolutionStatus.VERIFIED_PLAYABLE
                    prepared != null || prior[key] == ResolutionStatus.VERIFIED_PLAYABLE -> ResolutionStatus.EXPIRED
                    else -> ResolutionStatus.QUEUED
                }
            }
            next
        }
        workerJob = scope.launch(queueDispatcher) {
            try {
                // Only the rolling next-17 window is scheduled. On episode advance, slideWindow
                // cancels obsolete work and plans the next 17 from the canonical catalog order.
                val prioritizedUpcoming = upcoming.take(PreResolvePlan.FAST_WINDOW_SIZE) +
                    upcoming.drop(PreResolvePlan.FAST_WINDOW_SIZE)
                for (episode in prioritizedUpcoming) {
                        if (!isActive) return@launch
                        val key = identity(backendAnimeId, episode)
                        val cached = preparedCache[key]
                        if (cached != null && !cached.isExpired()) {
                            updateStatusFor(key, ResolutionStatus.VERIFIED_PLAYABLE)
                            continue
                        }
                        if (cached != null) {
                            preparedCache.remove(key)
                            updateStatusFor(key, ResolutionStatus.EXPIRED)
                        }
                        var resolved = false
                        for (attempt in 1..MAX_ATTEMPTS) {
                            if (!isActive) return@launch
                            updateStatusFor(key, if (attempt == 1) ResolutionStatus.RESOLVING else ResolutionStatus.RETRY_PENDING)
                            if (attempt > 1) delay(RETRY_DELAY_MS)
                            if (!isActive) return@launch
                            resolved = preResolveEpisode(backendAnimeId, episode)
                            if (resolved) break
                        }
                        updateStatusFor(key, completionStatus(resolved))
                }
            } catch (cancelled: CancellationException) {
                if (generation == queueGeneration) {
                    updateStatuses { statuses -> statuses.mapValues { (_, state) ->
                        if (state == ResolutionStatus.RESOLVING || state == ResolutionStatus.QUEUED) ResolutionStatus.RETRY_PENDING else state
                    } }
                }
                throw cancelled
            }
        }
    }

    private suspend fun preResolveEpisode(backendAnimeId: String, episode: EpisodeEntry): Boolean {
        val key = identity(backendAnimeId, episode)
        return try {
            withTimeout(12_000L) {
                val sources = repository.fetchEpisodeSourcesForCanonical(backendAnimeId, episode).getOrNull().orEmpty()
                for (source in sources) {
                    if (!source.hasUsableSourceId) continue
                    val direct = repository.resolveDirectPlayback(source.id).getOrNull() ?: continue
                    if (direct.sourceId != source.id) continue
                    updateStatusFor(key, ResolutionStatus.RESOLVED)
                    val url = direct.playbackUrl
                    if (direct.sourceId != source.id || url.isBlank() ||
                        !(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("file://")) ||
                        SourceExpiryTime.isExpired(direct.expiresAt)) continue
                    preparedCache[key] = PreparedEpisode(backendAnimeId, episode.id, episode.seasonNumber,
                        source, direct, System.currentTimeMillis())
                    Log.i(TAG, "Verified playback resolution for S${episode.seasonNumber} episode ${episode.id}")
                    return@withTimeout true
                }
                false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Pre-resolve failed for S${episode.seasonNumber} episode ${episode.id}: ${error.message}")
            false
        }
    }

    fun getPreparedSource(backendAnimeId: String, episode: EpisodeEntry): PreparedEpisode? {
        val key = identity(backendAnimeId, episode)
        val entry = preparedCache[key] ?: return null
        return if (!entry.isExpired()) entry else {
            preparedCache.remove(key)
            updateStatusFor(key, ResolutionStatus.EXPIRED)
            null
        }
    }

    fun discardPreparedSource(backendAnimeId: String, episode: EpisodeEntry) {
        val key = identity(backendAnimeId, episode)
        preparedCache.remove(key)
        updateStatusFor(key, ResolutionStatus.RETRY_PENDING)
    }

    private fun updateStatusFor(key: String, status: ResolutionStatus) {
        updateStatuses { it + (key to status) }
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
