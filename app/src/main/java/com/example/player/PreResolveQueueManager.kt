package com.example.player

import android.util.Log
import com.example.data.local.EpisodeEntry
import com.example.data.metrics.DatabaseMetricsTracker
import com.example.data.remote.DirectPlayData
import com.example.data.remote.ProphecySourceDto
import com.example.data.repository.AnimeRepository
import com.example.data.repository.EpisodeResolutionCoordinator
import com.example.data.repository.EpisodeSourceCacheIdentity
import com.example.data.repository.SourceExpiryTime
import com.example.data.repository.SourceIdentityValidator
import com.example.data.repository.resolveVerifiedPlaybackForEpisode
import com.example.data.streaming.ContentIdentityHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap

/** Serialized, bounded-concurrency Next-10 rolling pre-resolve worker. Signed URLs stay memory-only. */
@OptIn(ExperimentalCoroutinesApi::class)
class PreResolveQueueManager(
    private val repository: AnimeRepository,
    private val scope: CoroutineScope,
    private val metricsTracker: DatabaseMetricsTracker = DatabaseMetricsTracker.getInstance()
) {
    companion object {
        private const val TAG = "PreResolveQueueManager"
        const val CACHE_TTL_MS = 25 * 60 * 1000L
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 3_000L
        const val MAX_BACKGROUND_CONCURRENCY = 2
        private const val MAX_PREPARED_CACHE_ENTRIES = 16
        fun identity(backendAnimeId: String, episode: EpisodeEntry): String =
            EpisodeSourceCacheIdentity.key(backendAnimeId, episode.seasonNumber, episode.id)
        fun completionStatus(verifiedPlayable: Boolean): ResolutionStatus =
            if (verifiedPlayable) ResolutionStatus.VERIFIED_PLAYABLE else ResolutionStatus.FAILED
    }

    enum class ResolutionStatus { NOT_STARTED, QUEUED, RESOLVING, RESOLVED, VERIFIED_PLAYABLE, FAILED, RETRY_PENDING, EXPIRED }

    private data class ScheduledTask(
        val key: String,
        val episode: EpisodeEntry,
        val tierRank: Int,
        val sequenceOrder: Int,
        val attempt: Int = 1
    ) : Comparable<ScheduledTask> {
        override fun compareTo(other: ScheduledTask): Int {
            if (tierRank != other.tierRank) return tierRank.compareTo(other.tierRank)
            if (sequenceOrder != other.sequenceOrder) return sequenceOrder.compareTo(other.sequenceOrder)
            return attempt.compareTo(other.attempt)
        }
    }

    data class PreparedEpisode(
        val backendAnimeId: String,
        val episodeId: String,
        val seasonNumber: Int,
        val source: ProphecySourceDto,
        val directData: DirectPlayData,
        val resolvedAtMs: Long,
        val candidates: List<ProphecySourceDto> = listOf(source),
        val providerId: String = ContentIdentityHelper.deterministicProviderId(source.label),
        val episodeNumber: Int = 0
    ) {
        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
            nowMs - resolvedAtMs > CACHE_TTL_MS || SourceExpiryTime.isExpired(directData.expiresAt, nowMs)
    }

    private val preparedCache = ConcurrentHashMap<String, PreparedEpisode>()
    private val _queueStatus = MutableStateFlow<Map<String, ResolutionStatus>>(emptyMap())
    val queueStatus: StateFlow<Map<String, ResolutionStatus>> = _queueStatus.asStateFlow()
    private val _playbackBuffering = MutableStateFlow(false)
    private var workerJob: Job? = null
    @Volatile private var queueGeneration: Long = 0L
    @Volatile private var lastWindowSignature: String? = null
    private val queueDispatcher = Dispatchers.IO.limitedParallelism(MAX_BACKGROUND_CONCURRENCY)

    fun setPlaybackBuffering(buffering: Boolean) {
        _playbackBuffering.value = buffering
    }

    private suspend fun waitUntilBackgroundAllowed() {
        if (EpisodeResolutionCoordinator.isForegroundResolutionActive() || _playbackBuffering.value) {
            combine(
                EpisodeResolutionCoordinator.foregroundActiveFlow,
                _playbackBuffering
            ) { fgActive, buffering -> !fgActive && !buffering }
                .first { it }
        }
    }

    private fun isPreparedValidForSelection(
        prepared: PreparedEpisode,
        backendAnimeId: String,
        seasonNumber: Int,
        episodeId: String,
        preferredSourceId: String? = null,
        preferredProviderId: String? = null
    ): Boolean {
        if (prepared.isExpired()) return false
        val expectedProviderId = ContentIdentityHelper.deterministicProviderId(prepared.source.label)
        if (prepared.backendAnimeId != backendAnimeId ||
            prepared.episodeId != episodeId ||
            prepared.seasonNumber != seasonNumber ||
            prepared.providerId != expectedProviderId
        ) {
            return false
        }
        val normPrefSource = SourceIdentityValidator.normalizeId(preferredSourceId)
        if (normPrefSource.isNotEmpty() &&
            !SourceIdentityValidator.normalizedIdsEqual(prepared.source.id, normPrefSource)
        ) {
            return false
        }
        val normPrefProvider = SourceIdentityValidator.normalizeId(preferredProviderId)
        if (normPrefProvider.isNotEmpty() &&
            !SourceIdentityValidator.normalizedIdsEqual(prepared.providerId, normPrefProvider)
        ) {
            return false
        }
        return SourceIdentityValidator.validateDirectPlayData(prepared.directData, prepared.source.id).isValid
    }

    /**
     * Queue the next 10 canonical episodes in the same season relative to [currentEpisode].
     * Priority: Next 4 (tier 0) -> Following 6 (tier 1).
     * Never queues [currentEpisode] or crosses season boundaries.
     * Movies and invalid episode entries are excluded.
     */
    fun slideWindow(
        animeId: String,
        currentEpisode: EpisodeEntry,
        allEpisodes: List<EpisodeEntry>,
        isMovie: Boolean = false,
        preferredSourceId: String? = null,
        preferredProviderId: String? = null
    ) {
        if (animeId.isBlank() || currentEpisode.id.isBlank() ||
            isMovie || currentEpisode.seasonNumber <= 0 || currentEpisode.id.startsWith("movie_")
        ) {
            clear()
            return
        }
        val currentKey = identity(animeId, currentEpisode)
        val normPrefSource = SourceIdentityValidator.normalizeId(preferredSourceId).takeIf { it.isNotEmpty() }
        val normPrefProvider = SourceIdentityValidator.normalizeId(preferredProviderId).takeIf { it.isNotEmpty() }

        val upcoming = PreResolvePlan.upcoming(currentEpisode, allEpisodes)
        val fastTier = upcoming.take(PreResolvePlan.FAST_WINDOW_SIZE)
        val backgroundTier = upcoming.drop(PreResolvePlan.FAST_WINDOW_SIZE)

        val scheduledList = buildList {
            val seen = HashSet<String>()
            seen.add(currentKey)
            fastTier.forEachIndexed { idx, ep ->
                val k = identity(animeId, ep)
                if (ep.id.isNotBlank() && ep.seasonNumber == currentEpisode.seasonNumber && seen.add(k)) {
                    add(ScheduledTask(key = k, episode = ep, tierRank = 0, sequenceOrder = idx))
                }
            }
            backgroundTier.forEachIndexed { idx, ep ->
                val k = identity(animeId, ep)
                if (ep.id.isNotBlank() && ep.seasonNumber == currentEpisode.seasonNumber && seen.add(k)) {
                    add(ScheduledTask(key = k, episode = ep, tierRank = 1, sequenceOrder = idx))
                }
            }
        }

        val signature = buildString {
            append(animeId).append('|').append(currentKey).append('|')
            append(normPrefSource.orEmpty()).append('|').append(normPrefProvider.orEmpty()).append('|')
            append(upcoming.joinToString(",") { identity(animeId, it) })
        }
        if (signature == lastWindowSignature && workerJob?.isActive == true) return
        lastWindowSignature = signature
        val generation = ++queueGeneration
        workerJob?.cancel()

        // Evict entries belonging to another anime or expired entries, keeping current window warm
        val activeWindowKeys = scheduledList.mapTo(HashSet()) { it.key }
        preparedCache.entries.removeIf { (key, entry) ->
            entry.isExpired() ||
                entry.backendAnimeId != animeId ||
                entry.seasonNumber != currentEpisode.seasonNumber ||
                (preparedCache.size > MAX_PREPARED_CACHE_ENTRIES && key !in activeWindowKeys && key != currentKey)
        }

        if (scheduledList.isEmpty()) {
            workerJob = null
            metricsTracker.recordPreloadCycle(0L)
            _queueStatus.value = emptyMap()
            return
        }

        val targetKeys = scheduledList.associateBy { it.key }
        val pendingTasks = ArrayList<ScheduledTask>(scheduledList.size)
        updateStatuses { prior ->
            val next = LinkedHashMap<String, ResolutionStatus>(targetKeys.size)
            targetKeys.forEach { (key, task) ->
                val prepared = preparedCache[key]
                val validPrepared = prepared != null && isPreparedValidForSelection(
                    prepared = prepared,
                    backendAnimeId = animeId,
                    seasonNumber = task.episode.seasonNumber,
                    episodeId = task.episode.id,
                    preferredSourceId = normPrefSource,
                    preferredProviderId = normPrefProvider
                )
                if (validPrepared) {
                    next[key] = ResolutionStatus.VERIFIED_PLAYABLE
                } else {
                    if (prepared != null) preparedCache.remove(key)
                    next[key] = when {
                        prepared != null && prepared.isExpired() -> ResolutionStatus.EXPIRED
                        prior[key] == ResolutionStatus.VERIFIED_PLAYABLE && prepared == null -> ResolutionStatus.EXPIRED
                        else -> ResolutionStatus.QUEUED
                    }
                    pendingTasks += task
                }
            }
            next
        }

        if (pendingTasks.isEmpty()) {
            workerJob = null
            metricsTracker.recordPreloadCycle(0L)
            return
        }

        val d1ReadsBeforeCycle = metricsTracker.snapshot().d1Reads
        workerJob = scope.launch(queueDispatcher) {
            val queueLock = Any()
            val readyQueue = PriorityQueue<ScheduledTask>().apply { addAll(pendingTasks) }
            val wakeSignal = Channel<Unit>(Channel.UNLIMITED)
            var inProgressCount = 0
            var pendingRetryCount = 0

            suspend fun pollNextTask(): ScheduledTask? {
                while (isActive && generation == queueGeneration) {
                    synchronized(queueLock) {
                        val next = readyQueue.poll()
                        if (next != null) {
                            inProgressCount++
                            return next
                        }
                        if (inProgressCount == 0 && pendingRetryCount == 0) {
                            return null
                        }
                    }
                    wakeSignal.receive()
                }
                return null
            }

            try {
                coroutineScope {
                    val retryScope = this
                    val workers = (1..MAX_BACKGROUND_CONCURRENCY).map {
                        launch {
                            while (isActive && generation == queueGeneration) {
                                waitUntilBackgroundAllowed()
                                if (!isActive || generation != queueGeneration) break
                                val task = pollNextTask() ?: break
                                try {
                                    waitUntilBackgroundAllowed()
                                    if (!isActive || generation != queueGeneration) break
                                    val cached = preparedCache[task.key]
                                    if (cached != null && isPreparedValidForSelection(
                                            prepared = cached,
                                            backendAnimeId = animeId,
                                            seasonNumber = task.episode.seasonNumber,
                                            episodeId = task.episode.id,
                                            preferredSourceId = normPrefSource,
                                            preferredProviderId = normPrefProvider
                                        )
                                    ) {
                                        if (generation == queueGeneration) {
                                            updateStatusForGeneration(generation, task.key, ResolutionStatus.VERIFIED_PLAYABLE)
                                        }
                                        continue
                                    }
                                    if (cached != null) {
                                        preparedCache.remove(task.key, cached)
                                        if (generation == queueGeneration && cached.isExpired()) {
                                            updateStatusForGeneration(generation, task.key, ResolutionStatus.EXPIRED)
                                        }
                                    }
                                    if (generation == queueGeneration) {
                                        updateStatusForGeneration(
                                            generation,
                                            task.key,
                                            if (task.attempt == 1) ResolutionStatus.RESOLVING else ResolutionStatus.RETRY_PENDING
                                        )
                                    }
                                    val resolved = preResolveEpisode(
                                        animeId = animeId,
                                        episode = task.episode,
                                        expectedGeneration = generation,
                                        preferredSourceId = normPrefSource,
                                        preferredProviderId = normPrefProvider
                                    )
                                    if (generation != queueGeneration) break
                                    if (resolved) {
                                        updateStatusForGeneration(generation, task.key, ResolutionStatus.VERIFIED_PLAYABLE)
                                    } else if (task.attempt < MAX_ATTEMPTS && isActive && generation == queueGeneration) {
                                        updateStatusForGeneration(generation, task.key, ResolutionStatus.RETRY_PENDING)
                                        synchronized(queueLock) {
                                            pendingRetryCount++
                                        }
                                        retryScope.launch {
                                            try {
                                                delay(RETRY_DELAY_MS)
                                                if (isActive && generation == queueGeneration) {
                                                    synchronized(queueLock) {
                                                        pendingRetryCount--
                                                        readyQueue.offer(task.copy(attempt = task.attempt + 1))
                                                    }
                                                    wakeSignal.trySend(Unit)
                                                } else {
                                                    synchronized(queueLock) { pendingRetryCount-- }
                                                    wakeSignal.trySend(Unit)
                                                }
                                            } catch (cancelled: CancellationException) {
                                                synchronized(queueLock) {
                                                    pendingRetryCount = (pendingRetryCount - 1).coerceAtLeast(0)
                                                }
                                                wakeSignal.trySend(Unit)
                                                throw cancelled
                                            }
                                        }
                                    } else if (generation == queueGeneration) {
                                        updateStatusForGeneration(generation, task.key, ResolutionStatus.FAILED)
                                    }
                                } finally {
                                    synchronized(queueLock) {
                                        inProgressCount--
                                        if (readyQueue.isEmpty() && inProgressCount == 0 && pendingRetryCount == 0) {
                                            wakeSignal.trySend(Unit)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    workers.joinAll()
                }
                if (generation == queueGeneration) {
                    val d1Delta = (metricsTracker.snapshot().d1Reads - d1ReadsBeforeCycle).coerceAtLeast(0L)
                    metricsTracker.recordPreloadCycle(d1Delta)
                }
            } catch (cancelled: CancellationException) {
                if (generation == queueGeneration) {
                    updateStatuses { statuses ->
                        statuses.mapValues { (_, state) ->
                            if (state == ResolutionStatus.RESOLVING || state == ResolutionStatus.QUEUED) ResolutionStatus.RETRY_PENDING else state
                        }
                    }
                }
                throw cancelled
            } finally {
                wakeSignal.close()
            }
        }
    }

    private suspend fun preResolveEpisode(
        animeId: String,
        episode: EpisodeEntry,
        expectedGeneration: Long,
        preferredSourceId: String? = null,
        preferredProviderId: String? = null
    ): Boolean {
        val key = identity(animeId, episode)
        return try {
            waitUntilBackgroundAllowed()
            if (expectedGeneration != queueGeneration) return false
            withTimeout(35_000L) {
                // DB-first (Memory -> D1 -> existing Backend resolver fallback) with background priority
                val verified = repository.resolveVerifiedPlaybackForEpisode(
                    animeOrBackendId = animeId,
                    episode = episode,
                    preferredSourceId = preferredSourceId,
                    preferredProviderId = preferredProviderId,
                    isForeground = false
                ).getOrNull() ?: return@withTimeout false
                if (expectedGeneration != queueGeneration) return@withTimeout false

                updateStatusForGeneration(expectedGeneration, key, ResolutionStatus.RESOLVED)
                val direct = verified.directData
                val validation = SourceIdentityValidator.validateDirectPlayData(direct, verified.source.id)
                if (!validation.isValid) {
                    SourceIdentityValidator.recordDiagnostic(
                        code = (validation as com.example.data.repository.SourceValidationResult.Invalid).code,
                        scopeKey = key,
                        sourceId = verified.source.id,
                        providerLabel = verified.source.displayProviderName,
                        detail = validation.reason
                    )
                    return@withTimeout false
                }
                if (preferredSourceId != null &&
                    !SourceIdentityValidator.normalizedIdsEqual(verified.source.id, preferredSourceId)
                ) {
                    return@withTimeout false
                }
                val normDirect = direct.copy(sourceId = SourceIdentityValidator.normalizeId(direct.sourceId))
                val normSource = SourceIdentityValidator.normalizeDto(verified.source)
                val allCandidates = verified.candidates.map { SourceIdentityValidator.normalizeDto(it) }
                    .ifEmpty { listOf(normSource) }
                val candidatePrepared = PreparedEpisode(
                    backendAnimeId = animeId,
                    episodeId = episode.id,
                    seasonNumber = episode.seasonNumber,
                    source = normSource,
                    directData = normDirect,
                    resolvedAtMs = System.currentTimeMillis(),
                    candidates = allCandidates,
                    providerId = ContentIdentityHelper.deterministicProviderId(normSource.label),
                    episodeNumber = episode.episodeNumber
                )
                if (expectedGeneration != queueGeneration || candidatePrepared.isExpired()) {
                    return@withTimeout false
                }
                preparedCache[key] = candidatePrepared
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

    fun getPreparedSource(
        backendAnimeId: String,
        episode: EpisodeEntry,
        preferredSourceId: String? = null,
        preferredProviderId: String? = null
    ): PreparedEpisode? {
        val key = identity(backendAnimeId, episode)
        val entry = preparedCache[key] ?: return null
        val matchesIdentity = isPreparedValidForSelection(
            prepared = entry,
            backendAnimeId = backendAnimeId,
            seasonNumber = episode.seasonNumber,
            episodeId = episode.id,
            preferredSourceId = preferredSourceId,
            preferredProviderId = preferredProviderId
        )
        return if (matchesIdentity) {
            entry
        } else {
            val mismatchPref = preferredSourceId?.let {
                SourceIdentityValidator.normalizeId(it).isNotEmpty() &&
                    !SourceIdentityValidator.normalizedIdsEqual(entry.source.id, it)
            } == true
            if (!mismatchPref) {
                preparedCache.remove(key, entry)
                updateStatusFor(key, if (entry.isExpired()) ResolutionStatus.EXPIRED else ResolutionStatus.FAILED)
            }
            null
        }
    }

    fun discardPreparedSource(backendAnimeId: String, episode: EpisodeEntry) {
        val key = identity(backendAnimeId, episode)
        preparedCache.remove(key)
        updateStatusFor(key, ResolutionStatus.RETRY_PENDING)
    }

    private fun updateStatusForGeneration(expectedGeneration: Long, key: String, status: ResolutionStatus) {
        updateStatuses { prior ->
            if (queueGeneration == expectedGeneration) prior + (key to status) else prior
        }
    }

    private fun updateStatusFor(key: String, status: ResolutionStatus) {
        updateStatuses { it + (key to status) }
    }

    private fun updateStatuses(transform: (Map<String, ResolutionStatus>) -> Map<String, ResolutionStatus>) {
        _queueStatus.update(transform)
    }

    fun clear() {
        lastWindowSignature = null
        queueGeneration++
        workerJob?.cancel()
        workerJob = null
        _playbackBuffering.value = false
        preparedCache.clear()
        _queueStatus.value = emptyMap()
    }
}
