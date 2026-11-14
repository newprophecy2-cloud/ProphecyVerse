package com.example.player

import android.util.Log
import com.example.data.local.EpisodeEntry
import com.example.data.remote.DirectPlayData
import com.example.data.remote.ProphecySourceDto
import com.example.data.repository.AnimeRepository
import com.example.data.repository.resolveVerifiedPlaybackForEpisode
import com.example.data.repository.EpisodeSourceCacheIdentity
import com.example.data.repository.SourceExpiryTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    @Volatile private var lastWindowSignature: String? = null
    private val queueDispatcher = Dispatchers.IO.limitedParallelism(3)

    /**
     * Queue the next 11 canonical episodes after the current episode in the same season
     * (Ep 1 -> Eps 1..12, Ep 2 -> Ep 13, Ep 3 -> Ep 14).
     * Movies are excluded. Checks Room/Firestore caches before calling Backend Resolve.
     */
    fun slideWindow(
        animeId: String,
        currentEpisode: EpisodeEntry,
        allEpisodes: List<EpisodeEntry>,
        isMovie: Boolean = false
    ) {
        if (isMovie || currentEpisode.seasonNumber == 0 || currentEpisode.id.startsWith("movie_")) {
            clear()
            return
        }
        val upcoming = PreResolvePlan.upcoming(currentEpisode, allEpisodes)
        val previous = PreResolvePlan.previous(currentEpisode, allEpisodes)
        val signature = buildString {
            append(animeId).append('|').append(identity(animeId, currentEpisode)).append('|')
            append(upcoming.joinToString(",") { identity(animeId, it) }).append('|')
            append(previous.joinToString(",") { identity(animeId, it) })
        }
        if (signature == lastWindowSignature && workerJob?.isActive == true) return
        lastWindowSignature = signature
        val generation = ++queueGeneration
        if (upcoming.isEmpty() && previous.isEmpty()) {
            workerJob?.cancel()
            workerJob = null
            updateStatuses { statuses -> statuses.mapValues { (_, state) ->
                if (state == ResolutionStatus.RESOLVING || state == ResolutionStatus.QUEUED) ResolutionStatus.RETRY_PENDING else state
            } }
            return
        }
        workerJob?.cancel()
        val priorityEpisodes = (upcoming + previous).distinctBy { identity(animeId, it) }
        val targetKeys = priorityEpisodes.associateBy { identity(animeId, it) }
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
                // Bounded sequential window (concurrency = 3): fast upcoming -> remaining upcoming -> previous
                val tiers = listOf(
                    upcoming.take(PreResolvePlan.FAST_WINDOW_SIZE),
                    upcoming.drop(PreResolvePlan.FAST_WINDOW_SIZE),
                    previous
                )
                for (tier in tiers) {
                    if (!isActive) return@launch
                    tier.distinctBy { identity(animeId, it) }
                        .map { episode ->
                            async {
                                val key = identity(animeId, episode)
                                val cached = preparedCache[key]
                                if (cached != null && !cached.isExpired()) {
                                    updateStatusFor(key, ResolutionStatus.VERIFIED_PLAYABLE)
                                    return@async
                                }
                                if (cached != null) {
                                    preparedCache.remove(key)
                                    updateStatusFor(key, ResolutionStatus.EXPIRED)
                                }
                                var resolved = false
                                for (attempt in 1..MAX_ATTEMPTS) {
                                    if (!isActive) return@async
                                    updateStatusFor(key, if (attempt == 1) ResolutionStatus.RESOLVING else ResolutionStatus.RETRY_PENDING)
                                    if (attempt > 1) delay(RETRY_DELAY_MS)
                                    if (!isActive) return@async
                                    resolved = preResolveEpisode(animeId, episode)
                                    if (resolved) break
                                }
                                updateStatusFor(key, completionStatus(resolved))
                            }
                        }.awaitAll()
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

    private suspend fun preResolveEpisode(animeId: String, episode: EpisodeEntry): Boolean {
        val key = identity(animeId, episode)
        return try {
            withTimeout(15_000L) {
                // Delegates through cache-first Room -> Firestore -> Backend discovery/resolve with distributed lease
                val verified = repository.resolveVerifiedPlaybackForEpisode(animeId, episode).getOrNull()
                    ?: return@withTimeout false
                updateStatusFor(key, ResolutionStatus.RESOLVED)
                val direct = verified.directData
                val url = direct.playbackUrl
                if (direct.sourceId != verified.source.id || url.isBlank() ||
                    !(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("file://")) ||
                    SourceExpiryTime.isExpired(direct.expiresAt)) return@withTimeout false
                preparedCache[key] = PreparedEpisode(
                    backendAnimeId = animeId,
                    episodeId = episode.id,
                    seasonNumber = episode.seasonNumber,
                    source = verified.source,
                    directData = direct,
                    resolvedAtMs = System.currentTimeMillis()
                )
                Log.i(TAG, "Prepared (${verified.cacheTier}) S${episode.seasonNumber} E${episode.episodeNumber}")
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Pre-resolve failed for S${episode.seasonNumber} E${episode.episodeNumber}: ${error.message}")
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
        _queueStatus.update(transform)
    }

    fun clear() {
        lastWindowSignature = null
        workerJob?.cancel()
        workerJob = null
        preparedCache.clear()
        _queueStatus.value = emptyMap()
    }
}
