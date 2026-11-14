package com.example.data.streaming

import android.util.Log
import com.example.data.firebase.FirestoreFailureCategory
import com.example.data.firebase.FirestoreOperationException
import com.example.data.firebase.FirestoreRequestGuard
import com.example.data.local.StreamCacheDao
import com.example.data.local.StreamContainerEntity
import com.example.data.local.StreamEpisodeEntity
import com.example.data.local.StreamProviderEntity
import com.example.data.local.StreamSeasonEntity
import com.example.data.local.StreamSourceEntity
import com.example.data.repository.SourceExpiryTime
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared Firestore repository strictly responsible for streaming discovery,
 * cached playable Stream URLs, distributed resolution leases, and scoped real-time sync.
 *
 * Anime metadata descriptions, posters, and search are NEVER stored here.
 *
 * Anime path:
 *   anime_streams/{anilistAnimeId}/seasons/season_{s}/episodes/ep_{e}/providers/{providerId}/sources/{sourceDocId}
 *
 * Movie path (strictly flat — zero season/episode documents):
 *   movie_streams/{anilistMovieId}/providers/{providerId}/sources/{sourceDocId}
 */
class FirestoreStreamingRepository(
    private val streamCacheDao: StreamCacheDao,
    private val firestoreProvider: () -> FirebaseFirestore? = {
        runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    },
    private val isClientWriteAuthorized: () -> Boolean = {
        runCatching { FirebaseAuth.getInstance().currentUser != null }.getOrDefault(false)
    },
    val ownerId: String = "apk_${UUID.randomUUID().toString().take(8)}",
    private val cloudStoreProvider: (() -> StreamingCloudStore?)? = null
) {
    companion object {
        private const val TAG = "FirestoreStreamingRepo"
        private const val AUDIT_TAG = "FirestoreWriteAudit"
        private const val MAX_AUDIT_LOGS = 200
        const val COLLECTION_ANIME_STREAMS = "anime_streams"
        const val COLLECTION_MOVIE_STREAMS = "movie_streams"
        const val COLLECTION_RESOLVE_LOCKS = "stream_resolve_locks"
        const val SUB_SEASONS = "seasons"
        const val SUB_EPISODES = "episodes"
        const val SUB_PROVIDERS = "providers"
        const val SUB_SOURCES = "sources"
        const val DEFAULT_LEASE_TTL_MS = 20_000L
        private const val DISCOVERY_FRESHNESS_MS = 10 * 60 * 1000L
        private val sharedProductionOutbox = ConcurrentHashMap<String, PendingOutboxWrite>()
    }

    enum class ScopeReadStatus {
        HIT,
        NOT_FOUND,
        CACHE_MISS,
        PERMISSION_DENIED,
        UNAUTHENTICATED,
        QUOTA_EXCEEDED,
        TIMEOUT,
        NETWORK_ERROR,
        UNAVAILABLE,
        FAILED
    }

    data class ScopeReadOutcome(
        val data: ScopeStreamingData?,
        val status: ScopeReadStatus,
        val message: String? = null,
        val isHardError: Boolean = false
    )

    enum class WriteOutcomeState {
        SUCCEEDED,
        DEFERRED_UNAUTHENTICATED,
        DEFERRED_OFFLINE_OR_TRANSIENT,
        REJECTED_PERMISSION,
        QUOTA_COOLDOWN,
        FAILED
    }

    data class WriteOutcome(
        val state: WriteOutcomeState,
        val message: String? = null,
        val errorCode: String? = null,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private sealed class PendingOutboxWrite {
        abstract val key: String

        data class Discovery(
            override val key: String,
            val contentKey: String,
            val anilistId: Int?,
            val isMovie: Boolean,
            val backendId: String?,
            val seasonNumber: Int,
            val episodeNumber: Int,
            val catalogEpisodeId: String?,
            val backendEpisodeId: String?,
            val seasonCount: Int?,
            val totalEpisodeCount: Int?,
            val seasonEpisodeCount: Int,
            val providers: List<StreamProviderEntity>,
            val sources: List<StreamSourceEntity>,
            val discoveryStatus: DiscoveryStatus,
            val canonicalTitle: String?
        ) : PendingOutboxWrite()

        data class ResolvedUrl(
            override val key: String,
            val sourceEntity: StreamSourceEntity,
            val contentKey: String,
            val isMovie: Boolean,
            val seasonNumber: Int,
            val episodeNumber: Int,
            val streamUrl: String,
            val urlType: String,
            val contentType: String?,
            val safeHeaders: List<List<String>>,
            val expiresAt: String?,
            val verifiedAt: Long,
            val canonicalTitle: String?,
            val backendId: String?,
            val anilistId: Int?
        ) : PendingOutboxWrite()

        data class PlaybackSuccess(
            override val key: String,
            val sourceEntity: StreamSourceEntity,
            val contentKey: String,
            val isMovie: Boolean,
            val seasonNumber: Int,
            val episodeNumber: Int,
            val canonicalTitle: String?,
            val backendId: String?
        ) : PendingOutboxWrite()

        data class SoftDelete(
            override val key: String,
            val sourceEntity: StreamSourceEntity,
            val contentKey: String,
            val isMovie: Boolean,
            val seasonNumber: Int,
            val episodeNumber: Int,
            val canonicalTitle: String?,
            val backendId: String?
        ) : PendingOutboxWrite()
    }

    data class ScopeStreamingData(
        val discoveryStatus: String,
        val backendId: String? = null,
        val providers: List<StreamProviderEntity>,
        val sources: List<StreamSourceEntity>,
        val version: Long,
        val updatedAt: Long,
        val fromFirestoreServer: Boolean
    )

    data class ResolveLease(
        val lockKey: String,
        val ownerId: String,
        val acquiredRemote: Boolean,
        val expiresAtMs: Long
    )

    private val localLocks = ConcurrentHashMap<String, ResolveLease>()
    private val listenerSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val auditHistory = CopyOnWriteArrayList<FirestoreWriteAuditEntry>()
    private val _writeAuditLogs = MutableStateFlow<List<FirestoreWriteAuditEntry>>(emptyList())
    val writeAuditLogs: StateFlow<List<FirestoreWriteAuditEntry>> = _writeAuditLogs.asStateFlow()
    private val instanceOutbox = ConcurrentHashMap<String, PendingOutboxWrite>()

    private fun outboxMap(): ConcurrentHashMap<String, PendingOutboxWrite> =
        if (cloudStoreProvider == null) sharedProductionOutbox else instanceOutbox

    fun pendingOutboxCount(): Int = outboxMap().size

    @Volatile
    var lastWriteRejectionReason: String? = null
        private set

    @Volatile
    var lastWriteOutcome: WriteOutcome? = null
        private set

    fun recentWriteLogs(): List<FirestoreWriteAuditEntry> = auditHistory.toList()

    fun clearWriteLogs() {
        auditHistory.clear()
        _writeAuditLogs.value = emptyList()
    }

    private fun resolveCloudStore(): StreamingCloudStore? {
        cloudStoreProvider?.invoke()?.let { return it }
        return firestoreProvider()?.let(::RealFirestoreCloudStore)
    }

    fun containerDocumentPath(contentKey: String, isMovie: Boolean): String =
        if (isMovie) "$COLLECTION_MOVIE_STREAMS/$contentKey" else "$COLLECTION_ANIME_STREAMS/$contentKey"

    fun animeSeasonPath(contentKey: String, seasonNumber: Int): String =
        "$COLLECTION_ANIME_STREAMS/$contentKey/$SUB_SEASONS/${ContentIdentityHelper.seasonId(seasonNumber)}"

    fun movieProviderPath(contentKey: String, providerId: String): String =
        "$COLLECTION_MOVIE_STREAMS/$contentKey/$SUB_PROVIDERS/$providerId"

    fun movieSourcePath(contentKey: String, providerId: String, sourceId: String): String =
        "$COLLECTION_MOVIE_STREAMS/$contentKey/$SUB_PROVIDERS/$providerId/$SUB_SOURCES/${ContentIdentityHelper.sourceDocId(sourceId)}"

    fun animeEpisodePath(contentKey: String, seasonNumber: Int, episodeNumber: Int): String =
        "$COLLECTION_ANIME_STREAMS/$contentKey/$SUB_SEASONS/${ContentIdentityHelper.seasonId(seasonNumber)}/$SUB_EPISODES/${ContentIdentityHelper.episodeId(episodeNumber)}"

    fun animeProviderPath(contentKey: String, seasonNumber: Int, episodeNumber: Int, providerId: String): String =
        "${animeEpisodePath(contentKey, seasonNumber, episodeNumber)}/$SUB_PROVIDERS/$providerId"

    fun animeSourcePath(contentKey: String, seasonNumber: Int, episodeNumber: Int, providerId: String, sourceId: String): String =
        "${animeProviderPath(contentKey, seasonNumber, episodeNumber, providerId)}/$SUB_SOURCES/${ContentIdentityHelper.sourceDocId(sourceId)}"

    fun scopePath(contentKey: String, isMovie: Boolean, seasonNumber: Int, episodeNumber: Int): String =
        if (isMovie) containerDocumentPath(contentKey, true) else animeEpisodePath(contentKey, seasonNumber, episodeNumber)

    fun sourceDocumentPath(contentKey: String, isMovie: Boolean, seasonNumber: Int, episodeNumber: Int, providerId: String, sourceId: String): String =
        if (isMovie) movieSourcePath(contentKey, providerId, sourceId) else animeSourcePath(contentKey, seasonNumber, episodeNumber, providerId, sourceId)

    /**
     * Reads shared provider, source, and cached Stream URL data from Firestore
     * (cache read timing + server fetch timing), and syncs into Room transactionally.
     */
    suspend fun fetchAndSyncScope(
        contentKey: String,
        isMovie: Boolean,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        catalogEpisodeId: String? = null,
        preferServer: Boolean = false
    ): ScopeStreamingData? = fetchAndSyncScopeWithOutcome(
        contentKey = contentKey,
        isMovie = isMovie,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        catalogEpisodeId = catalogEpisodeId,
        preferServer = preferServer
    ).data

    suspend fun fetchAndSyncScopeWithOutcome(
        contentKey: String,
        isMovie: Boolean,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        catalogEpisodeId: String? = null,
        preferServer: Boolean = false,
        requirePlayableUrl: Boolean = false
    ): ScopeReadOutcome {
        val store = resolveCloudStore() ?: return ScopeReadOutcome(
            data = null,
            status = ScopeReadStatus.UNAVAILABLE,
            message = "Firestore instance unavailable.",
            isHardError = true
        )
        val scopeKey = ContentIdentityHelper.scopeKey(contentKey, isMovie, seasonNumber, episodeNumber)
        val scopeDocPath = scopePath(contentKey, isMovie, seasonNumber, episodeNumber)

        // 1. Firestore local SDK cache read first (when not forcing server)
        if (!preferServer) {
            val cacheStarted = MonotonicMetrics.nowMs()
            val cacheResult = runCatching {
                readScopeSnapshot(store, scopeDocPath, scopeKey, contentKey, isMovie, seasonNumber, episodeNumber, catalogEpisodeId, preferCache = true)
            }.getOrNull()
            MonotonicMetrics.recordDuration("firestore_cache_read_ms", cacheStarted, "hit=${cacheResult != null}")
            val cacheHasValidUrl = cacheResult != null && cacheResult.sources.any {
                it.status != "REMOVED" && it.deletedAt == null &&
                    SafePlaybackHeaders.isValidStreamUrl(it.streamUrl) &&
                    !SourceExpiryTime.isExpiredOrInvalid(it.expiresAt)
            }
            if (cacheHasValidUrl) {
                return ScopeReadOutcome(data = cacheResult, status = ScopeReadStatus.HIT)
            }
        }

        val cooldownRemaining = FirestoreRequestGuard.cooldownRemainingMs()
        if (cooldownRemaining > 0L) {
            return ScopeReadOutcome(
                data = null,
                status = ScopeReadStatus.QUOTA_EXCEEDED,
                message = "Firestore quota cooldown active (${(cooldownRemaining + 999L) / 1000L}s remaining).",
                isHardError = true
            )
        }

        // 2. Firestore server fetch (bounded timeout so unresponsive Firestore never stalls source discovery)
        val serverStarted = MonotonicMetrics.nowMs()
        return try {
            val data = kotlinx.coroutines.withTimeout(4_000L) {
                readScopeSnapshot(
                    store, scopeDocPath, scopeKey, contentKey, isMovie, seasonNumber, episodeNumber, catalogEpisodeId, preferCache = false
                )
            }
            MonotonicMetrics.recordDuration("firestore_server_fetch_ms", serverStarted, "found=${data != null}")
            if (data == null) {
                ScopeReadOutcome(data = null, status = ScopeReadStatus.NOT_FOUND)
            } else if (requirePlayableUrl && data.sources.none {
                    it.status != "REMOVED" && it.deletedAt == null &&
                        SafePlaybackHeaders.isValidStreamUrl(it.streamUrl) &&
                        !SourceExpiryTime.isExpiredOrInvalid(it.expiresAt)
                }
            ) {
                ScopeReadOutcome(data = data, status = ScopeReadStatus.CACHE_MISS)
            } else {
                ScopeReadOutcome(data = data, status = ScopeReadStatus.HIT)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            MonotonicMetrics.recordDuration("firestore_server_fetch_ms", serverStarted, "error=TIMEOUT")
            Log.w(TAG, "Firestore read timed out after 4000ms; falling back to Room/Backend")
            ScopeReadOutcome(data = null, status = ScopeReadStatus.TIMEOUT, message = "Firestore read timed out", isHardError = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FirestoreOperationException) {
            MonotonicMetrics.recordDuration("firestore_server_fetch_ms", serverStarted, "error=${error.category.name}")
            Log.w(TAG, "Firestore read skipped (${error.category.name}): ${error.message}")
            val status = when (error.category) {
                FirestoreFailureCategory.RESOURCE_EXHAUSTED -> ScopeReadStatus.QUOTA_EXCEEDED
                FirestoreFailureCategory.PERMISSION_DENIED -> ScopeReadStatus.PERMISSION_DENIED
                FirestoreFailureCategory.UNAUTHENTICATED -> ScopeReadStatus.UNAUTHENTICATED
                FirestoreFailureCategory.DEADLINE_EXCEEDED -> ScopeReadStatus.TIMEOUT
                FirestoreFailureCategory.NETWORK_ERROR -> ScopeReadStatus.NETWORK_ERROR
                FirestoreFailureCategory.UNAVAILABLE -> ScopeReadStatus.UNAVAILABLE
                FirestoreFailureCategory.UNKNOWN_ERROR -> ScopeReadStatus.FAILED
            }
            ScopeReadOutcome(data = null, status = status, message = error.message, isHardError = true)
        } catch (error: Exception) {
            MonotonicMetrics.recordDuration("firestore_server_fetch_ms", serverStarted, "error=UNKNOWN")
            Log.w(TAG, "Firestore read failed cleanly: ${error.message}")
            ScopeReadOutcome(data = null, status = ScopeReadStatus.FAILED, message = error.message, isHardError = true)
        }
    }

    private suspend fun readScopeSnapshot(
        store: StreamingCloudStore,
        scopeDocPath: String,
        scopeKey: String,
        contentKey: String,
        isMovie: Boolean,
        seasonNumber: Int,
        episodeNumber: Int,
        catalogEpisodeId: String?,
        preferCache: Boolean
    ): ScopeStreamingData? {
        val cPath = containerDocumentPath(contentKey, isMovie)
        val containerDoc = store.getDoc("read_stream_container", cPath, preferCache)
        val scopeDoc = if (isMovie) containerDoc else store.getDoc("read_stream_episode", scopeDocPath, preferCache)

        val containerExists = containerDoc?.exists == true
        val scopeExists = scopeDoc?.exists == true
        if (!scopeExists && !containerExists) return null

        val backendId = containerDoc?.data?.stringValue("backend_id")
            ?: scopeDoc?.data?.stringValue("backend_episode_id")
        val canonicalTitle = containerDoc?.data?.stringValue("canonical_title")
        val discoveryStatus = scopeDoc?.data?.stringValue("discovery_status")
            ?: containerDoc?.data?.stringValue(if (isMovie) "discovery_status" else "provider_discovery_status")
            ?: DiscoveryStatus.NOT_DISCOVERED.name
        val scopeVersion = scopeDoc?.data?.longValue("version") ?: 1L
        val scopeUpdatedAt = scopeDoc?.data?.longValue("updated_at") ?: System.currentTimeMillis()

        val providerDocs = store.listCollection("read_providers", "$scopeDocPath/$SUB_PROVIDERS", preferCache)
        val providers = mutableListOf<StreamProviderEntity>()
        val sources = mutableListOf<StreamSourceEntity>()

        for (pDoc in providerDocs) {
            val provider = parseProviderDoc(pDoc, scopeKey) ?: continue
            providers += provider
            val sourceDocs = store.listCollection("read_sources", "$scopeDocPath/$SUB_PROVIDERS/${pDoc.id}/$SUB_SOURCES", preferCache)
            for (sDoc in sourceDocs) {
                parseSourceDoc(sDoc, scopeKey, provider.providerId)?.let { sources += it }
            }
        }

        persistScopeToRoom(
            contentKey = contentKey,
            canonicalTitle = canonicalTitle,
            isMovie = isMovie,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            catalogEpisodeId = catalogEpisodeId,
            backendId = backendId,
            backendEpisodeId = scopeDoc?.data?.stringValue("backend_episode_id"),
            discoveryStatus = discoveryStatus,
            version = scopeVersion,
            providers = providers,
            sources = sources,
            authoritativeComplete = discoveryStatus == DiscoveryStatus.COMPLETED.name
        )

        return ScopeStreamingData(
            discoveryStatus = discoveryStatus,
            backendId = backendId,
            providers = providers,
            sources = sources,
            version = scopeVersion,
            updatedAt = scopeUpdatedAt,
            fromFirestoreServer = !preferCache
        )
    }

    /**
     * Attaches a strictly scoped real-time snapshot listener ONLY for the currently opened
     * Movie or active Anime Episode. Automatically detaches all listeners on cancellation/exit.
     */
    fun observeScopedStreamingUpdates(
        contentKey: String,
        isMovie: Boolean,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        catalogEpisodeId: String? = null
    ): Flow<ScopeStreamingData> = callbackFlow {
        val store = resolveCloudStore()
        if (store == null) {
            awaitClose { }
            return@callbackFlow
        }

        val scopeKey = ContentIdentityHelper.scopeKey(contentKey, isMovie, seasonNumber, episodeNumber)
        val scopeDocPath = scopePath(contentKey, isMovie, seasonNumber, episodeNumber)
        val detachCallbacks = mutableListOf<() -> Unit>()
        val sourceDetachCallbacks = ConcurrentHashMap<String, () -> Unit>()

        val currentProviders = ConcurrentHashMap<String, StreamProviderEntity>()
        val currentSourcesByProvider = ConcurrentHashMap<String, List<StreamSourceEntity>>()
        var latestDiscoveryStatus = DiscoveryStatus.NOT_DISCOVERED.name
        var latestVersion = 1L
        var latestUpdatedAt = 0L

        fun emitCombined() {
            val allProviders = currentProviders.values.sortedBy { it.providerName }
            val allSources = currentSourcesByProvider.values.flatten().sortedBy { it.sourceId }
            val propagationStarted = MonotonicMetrics.nowMs()
            val payload = ScopeStreamingData(
                discoveryStatus = latestDiscoveryStatus,
                providers = allProviders,
                sources = allSources,
                version = latestVersion,
                updatedAt = latestUpdatedAt,
                fromFirestoreServer = true
            )
            trySend(payload)
            listenerSyncScope.launch {
                persistScopeToRoom(
                    contentKey = contentKey,
                    canonicalTitle = null,
                    isMovie = isMovie,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    catalogEpisodeId = catalogEpisodeId,
                    backendId = null,
                    backendEpisodeId = null,
                    discoveryStatus = latestDiscoveryStatus,
                    version = latestVersion,
                    providers = allProviders,
                    sources = allSources,
                    authoritativeComplete = latestDiscoveryStatus == DiscoveryStatus.COMPLETED.name
                )
                MonotonicMetrics.recordDuration("firestore_snapshot_propagation_ms", propagationStarted, "sources=${allSources.size}")
            }
        }

        detachCallbacks += store.observeDoc(
            path = scopeDocPath,
            onUpdate = { snap ->
                if (snap != null && snap.exists) {
                    latestDiscoveryStatus = snap.data.stringValue("discovery_status")
                        ?: snap.data.stringValue("provider_discovery_status")
                        ?: latestDiscoveryStatus
                    latestVersion = snap.data.longValue("version") ?: latestVersion
                    latestUpdatedAt = snap.data.longValue("updated_at") ?: System.currentTimeMillis()
                    emitCombined()
                }
            },
            onError = { error ->
                FirestoreRequestGuard.recordListenerFailure(error)
                Log.w(TAG, "Scoped scope listener error (${extractFirebaseErrorCode(error) ?: "UNKNOWN"}): ${error.message}")
            }
        )

        detachCallbacks += store.observeCollection(
            collectionPath = "$scopeDocPath/$SUB_PROVIDERS",
            onUpdate = { providerDocs ->
                val seenProviderIds = mutableSetOf<String>()
                for (pDoc in providerDocs) {
                    val pEntity = parseProviderDoc(pDoc, scopeKey) ?: continue
                    seenProviderIds += pEntity.providerId
                    currentProviders[pEntity.providerId] = pEntity
                    if (!sourceDetachCallbacks.containsKey(pEntity.providerId)) {
                        sourceDetachCallbacks[pEntity.providerId] = store.observeCollection(
                            collectionPath = "$scopeDocPath/$SUB_PROVIDERS/${pDoc.id}/$SUB_SOURCES",
                            onUpdate = { sDocs ->
                                val parsed = sDocs.mapNotNull { parseSourceDoc(it, scopeKey, pEntity.providerId) }
                                currentSourcesByProvider[pEntity.providerId] = parsed
                                emitCombined()
                            },
                            onError = { sErr ->
                                FirestoreRequestGuard.recordListenerFailure(sErr)
                            }
                        )
                    }
                }
                sourceDetachCallbacks.keys.filter { it !in seenProviderIds }.forEach { obsoleteId ->
                    sourceDetachCallbacks.remove(obsoleteId)?.invoke()
                    currentProviders.remove(obsoleteId)
                    currentSourcesByProvider.remove(obsoleteId)
                }
                emitCombined()
            },
            onError = { error ->
                FirestoreRequestGuard.recordListenerFailure(error)
                Log.w(TAG, "Scoped providers listener error (${extractFirebaseErrorCode(error) ?: "UNKNOWN"}): ${error.message}")
            }
        )

        awaitClose {
            detachCallbacks.forEach { runCatching { it() } }
            sourceDetachCallbacks.values.forEach { runCatching { it() } }
            sourceDetachCallbacks.clear()
        }
    }

    /**
     * Publishes discovered providers & sources to Firestore when client write is authorized,
     * and always synchronizes Room locally using transactions.
     * Returns true if cloud write succeeded, false if skipped or rejected.
     */
    suspend fun publishDiscoveryAndSyncRoom(
        contentKey: String,
        anilistId: Int?,
        isMovie: Boolean,
        backendId: String?,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        catalogEpisodeId: String? = null,
        backendEpisodeId: String? = null,
        seasonCount: Int? = null,
        totalEpisodeCount: Int? = null,
        seasonEpisodeCount: Int = 0,
        providers: List<StreamProviderEntity>,
        sources: List<StreamSourceEntity>,
        discoveryStatus: DiscoveryStatus,
        canonicalTitle: String? = null
    ): Boolean {
        val existingContainer = streamCacheDao.getContainer(ContentIdentityHelper.containerScopeKey(contentKey, isMovie))
        val isUpdateOp = existingContainer != null && existingContainer.discoveryStatus != DiscoveryStatus.NOT_DISCOVERED.name
        val nextVersion = (existingContainer?.version ?: 0L) + 1L
        val now = System.currentTimeMillis()
        val resolvedTitle = canonicalTitle?.takeIf { it.isNotBlank() } ?: existingContainer?.canonicalTitle

        // 1. Always update local Room cache transactionally first for instant UI / offline readiness
        persistScopeToRoom(
            contentKey = contentKey,
            canonicalTitle = resolvedTitle,
            isMovie = isMovie,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            catalogEpisodeId = catalogEpisodeId,
            backendId = backendId,
            backendEpisodeId = backendEpisodeId,
            anilistId = anilistId,
            seasonCount = if (isMovie) null else seasonCount,
            totalEpisodeCount = if (isMovie) null else totalEpisodeCount,
            seasonEpisodeCount = seasonEpisodeCount,
            discoveryStatus = discoveryStatus.name,
            version = nextVersion,
            providers = providers,
            sources = sources,
            authoritativeComplete = discoveryStatus == DiscoveryStatus.COMPLETED
        )

        // 2. Shared Firestore publish via authorized path only
        val op = if (isUpdateOp) FirestoreWriteOperation.UPDATE else FirestoreWriteOperation.ADD
        val contentType = if (isMovie) "MOVIE" else "ANIME"
        val seasonIdStr = if (isMovie) null else ContentIdentityHelper.seasonId(seasonNumber)
        val episodeIdStr = if (isMovie) null else ContentIdentityHelper.episodeId(episodeNumber)
        val primaryTargetPath = sources.firstOrNull()?.let {
            sourceDocumentPath(contentKey, isMovie, seasonNumber, episodeNumber, it.providerId, it.sourceId)
        } ?: providers.firstOrNull()?.let {
            if (isMovie) movieProviderPath(contentKey, it.providerId) else animeProviderPath(contentKey, seasonNumber, episodeNumber, it.providerId)
        } ?: scopePath(contentKey, isMovie, seasonNumber, episodeNumber)

        val discoveryOutboxKey = "discovery:${ContentIdentityHelper.scopeKey(contentKey, isMovie, seasonNumber, episodeNumber)}"
        val startedAt = System.currentTimeMillis()
        val store = resolveCloudStore()
        if (store == null) {
            val reason = "Firestore instance unavailable."
            lastWriteRejectionReason = reason
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.FAILED, reason)
            outboxMap()[discoveryOutboxKey] = PendingOutboxWrite.Discovery(
                key = discoveryOutboxKey, contentKey = contentKey, anilistId = anilistId, isMovie = isMovie,
                backendId = backendId, seasonNumber = seasonNumber, episodeNumber = episodeNumber,
                catalogEpisodeId = catalogEpisodeId, backendEpisodeId = backendEpisodeId,
                seasonCount = seasonCount, totalEpisodeCount = totalEpisodeCount,
                seasonEpisodeCount = seasonEpisodeCount, providers = providers, sources = sources,
                discoveryStatus = discoveryStatus, canonicalTitle = resolvedTitle
            )
            recordWriteAudit(
                operation = op,
                contentType = contentType,
                canonicalTitle = resolvedTitle,
                anilistId = contentKey,
                backendId = backendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = providers.firstOrNull()?.providerId,
                sourceId = sources.firstOrNull()?.sourceId,
                targetPath = primaryTargetPath,
                startedAtMs = startedAt,
                success = false,
                failureReason = reason,
                firebaseErrorCode = null
            )
            return false
        }
        if (!isClientWriteAuthorized()) {
            val reason = "Firestore write deferred: client is unauthenticated (zero-trust rules require signed-in account)."
            lastWriteRejectionReason = reason
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.DEFERRED_UNAUTHENTICATED, reason, "UNAUTHENTICATED")
            outboxMap()[discoveryOutboxKey] = PendingOutboxWrite.Discovery(
                key = discoveryOutboxKey, contentKey = contentKey, anilistId = anilistId, isMovie = isMovie,
                backendId = backendId, seasonNumber = seasonNumber, episodeNumber = episodeNumber,
                catalogEpisodeId = catalogEpisodeId, backendEpisodeId = backendEpisodeId,
                seasonCount = seasonCount, totalEpisodeCount = totalEpisodeCount,
                seasonEpisodeCount = seasonEpisodeCount, providers = providers, sources = sources,
                discoveryStatus = discoveryStatus, canonicalTitle = resolvedTitle
            )
            recordWriteAudit(
                operation = op,
                contentType = contentType,
                canonicalTitle = resolvedTitle,
                anilistId = contentKey,
                backendId = backendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = providers.firstOrNull()?.providerId,
                sourceId = sources.firstOrNull()?.sourceId,
                targetPath = primaryTargetPath,
                startedAtMs = startedAt,
                success = false,
                failureReason = reason,
                firebaseErrorCode = "UNAUTHENTICATED"
            )
            return false
        }

        val providerNamesById = providers.associate { it.providerId to it.providerName }
        val writes = buildDiscoveryWrites(
            contentKey = contentKey,
            anilistId = anilistId,
            canonicalTitle = resolvedTitle,
            isMovie = isMovie,
            backendId = backendId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            backendEpisodeId = backendEpisodeId,
            seasonCount = seasonCount,
            totalEpisodeCount = totalEpisodeCount,
            seasonEpisodeCount = seasonEpisodeCount,
            providers = providers,
            sources = sources,
            providerNamesById = providerNamesById,
            discoveryStatus = discoveryStatus,
            version = nextVersion,
            now = now
        )

        return try {
            store.commitBatch("publish_stream_discovery", writes)
            lastWriteRejectionReason = null
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.SUCCEEDED)
            outboxMap().remove(discoveryOutboxKey)
            if (sources.isEmpty()) {
                recordWriteAudit(
                    operation = op,
                    contentType = contentType,
                    canonicalTitle = resolvedTitle,
                    anilistId = contentKey,
                    backendId = backendId,
                    seasonId = seasonIdStr,
                    episodeId = episodeIdStr,
                    providerId = providers.firstOrNull()?.providerId,
                    sourceId = null,
                    targetPath = primaryTargetPath,
                    startedAtMs = startedAt,
                    success = true
                )
            } else {
                for (src in sources) {
                    recordWriteAudit(
                        operation = op,
                        contentType = contentType,
                        canonicalTitle = resolvedTitle,
                        anilistId = contentKey,
                        backendId = backendId,
                        seasonId = seasonIdStr,
                        episodeId = episodeIdStr,
                        providerId = src.providerId,
                        sourceId = src.sourceId,
                        targetPath = sourceDocumentPath(contentKey, isMovie, seasonNumber, episodeNumber, src.providerId, src.sourceId),
                        startedAtMs = startedAt,
                        success = true
                    )
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FirestoreOperationException) {
            val code = extractFirebaseErrorCode(error) ?: error.category.name
            val msg = handleWriteFailure("publish_stream_discovery", error)
            if (shouldQueueForRetry(error.category)) {
                outboxMap()[discoveryOutboxKey] = PendingOutboxWrite.Discovery(
                    key = discoveryOutboxKey, contentKey = contentKey, anilistId = anilistId, isMovie = isMovie,
                    backendId = backendId, seasonNumber = seasonNumber, episodeNumber = episodeNumber,
                    catalogEpisodeId = catalogEpisodeId, backendEpisodeId = backendEpisodeId,
                    seasonCount = seasonCount, totalEpisodeCount = totalEpisodeCount,
                    seasonEpisodeCount = seasonEpisodeCount, providers = providers, sources = sources,
                    discoveryStatus = discoveryStatus, canonicalTitle = resolvedTitle
                )
            } else {
                outboxMap().remove(discoveryOutboxKey)
            }
            recordWriteAudit(
                operation = op,
                contentType = contentType,
                canonicalTitle = resolvedTitle,
                anilistId = contentKey,
                backendId = backendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = providers.firstOrNull()?.providerId,
                sourceId = sources.firstOrNull()?.sourceId,
                targetPath = primaryTargetPath,
                startedAtMs = startedAt,
                success = false,
                failureReason = msg,
                firebaseErrorCode = code
            )
            false
        } catch (error: Exception) {
            val code = extractFirebaseErrorCode(error)
            val msg = "Firestore publish skipped cleanly: ${error.message}"
            lastWriteRejectionReason = msg
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.DEFERRED_OFFLINE_OR_TRANSIENT, msg, code)
            outboxMap()[discoveryOutboxKey] = PendingOutboxWrite.Discovery(
                key = discoveryOutboxKey, contentKey = contentKey, anilistId = anilistId, isMovie = isMovie,
                backendId = backendId, seasonNumber = seasonNumber, episodeNumber = episodeNumber,
                catalogEpisodeId = catalogEpisodeId, backendEpisodeId = backendEpisodeId,
                seasonCount = seasonCount, totalEpisodeCount = totalEpisodeCount,
                seasonEpisodeCount = seasonEpisodeCount, providers = providers, sources = sources,
                discoveryStatus = discoveryStatus, canonicalTitle = resolvedTitle
            )
            recordWriteAudit(
                operation = op,
                contentType = contentType,
                canonicalTitle = resolvedTitle,
                anilistId = contentKey,
                backendId = backendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = providers.firstOrNull()?.providerId,
                sourceId = sources.firstOrNull()?.sourceId,
                targetPath = primaryTargetPath,
                startedAtMs = startedAt,
                success = false,
                failureReason = msg,
                firebaseErrorCode = code
            )
            false
        }
    }

    /**
     * Publishes a newly resolved and validated Stream URL (with safe headers only) to Firestore
     * and updates Room. Ensures parent container/season/episode/provider docs exist so no orphan
     * source documents are ever written. Never overwrites a previously valid URL with a failed resolution.
     */
    suspend fun publishResolvedStreamUrl(
        sourceEntity: StreamSourceEntity,
        contentKey: String,
        isMovie: Boolean,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        streamUrl: String,
        urlType: String,
        contentType: String?,
        safeHeaders: List<List<String>>,
        expiresAt: String?,
        verifiedAt: Long = System.currentTimeMillis(),
        canonicalTitle: String? = null,
        backendId: String? = null,
        anilistId: Int? = contentKey.toIntOrNull()
    ): Boolean {
        val normalizedStreamUrl = streamUrl.trim()
        if (sourceEntity.providerId.isBlank() || sourceEntity.sourceId.isBlank()) {
            val reason = "Resolved stream publication rejected: provider/source identity is blank."
            lastWriteRejectionReason = reason
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.FAILED, reason)
            Log.e(TAG, "$reason sourceDocKey=${sourceEntity.sourceDocKey}")
            return false
        }
        if (!SafePlaybackHeaders.isValidStreamUrl(normalizedStreamUrl)) {
            val reason = "Resolved stream publication rejected: playback URL is invalid."
            lastWriteRejectionReason = reason
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.FAILED, reason)
            Log.w(TAG, "$reason provider=${sourceEntity.providerId} source=${sourceEntity.sourceId}")
            return false
        }
        if (SourceExpiryTime.isExpiredOrInvalid(expiresAt)) {
            val reason = "Resolved stream publication rejected: playback URL expiry is invalid or expired."
            lastWriteRejectionReason = reason
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.FAILED, reason)
            Log.w(TAG, "$reason provider=${sourceEntity.providerId} source=${sourceEntity.sourceId}")
            return false
        }
        val existing = streamCacheDao.getAllSources(sourceEntity.scopeKey)
            .firstOrNull { it.sourceDocKey == sourceEntity.sourceDocKey }
        val nextVersion = maxOf(sourceEntity.version, existing?.version ?: 0L) + 1L
        val sanitizedJson = SafePlaybackHeaders.toJson(safeHeaders)
        val updatedEntity = sourceEntity.copy(
            streamUrl = normalizedStreamUrl,
            urlType = urlType,
            contentType = contentType ?: sourceEntity.contentType,
            safeHeadersJson = sanitizedJson,
            expiresAt = expiresAt ?: sourceEntity.expiresAt,
            status = StreamRecordStatus.AVAILABLE.name,
            version = nextVersion,
            lastVerifiedAt = verifiedAt,
            updatedAt = verifiedAt
        )
        streamCacheDao.upsertSource(updatedEntity)

        val existingContainer = streamCacheDao.getContainer(ContentIdentityHelper.containerScopeKey(contentKey, isMovie))
        val resolvedTitle = canonicalTitle?.takeIf { it.isNotBlank() } ?: existingContainer?.canonicalTitle
        val resolvedBackendId = backendId?.takeIf { it.isNotBlank() } ?: existingContainer?.backendId
        val targetPath = sourceDocumentPath(contentKey, isMovie, seasonNumber, episodeNumber, updatedEntity.providerId, updatedEntity.sourceId)
        val contentTypeLabel = if (isMovie) "MOVIE" else "ANIME"
        val seasonIdStr = if (isMovie) null else ContentIdentityHelper.seasonId(seasonNumber)
        val episodeIdStr = if (isMovie) null else ContentIdentityHelper.episodeId(episodeNumber)
        val urlOutboxKey = "resolved:${updatedEntity.sourceDocKey}"
        val startedAt = System.currentTimeMillis()
        val pendingResolved = PendingOutboxWrite.ResolvedUrl(
            key = urlOutboxKey,
            sourceEntity = updatedEntity,
            contentKey = contentKey,
            isMovie = isMovie,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            streamUrl = normalizedStreamUrl,
            urlType = urlType,
            contentType = contentType,
            safeHeaders = safeHeaders,
            expiresAt = expiresAt,
            verifiedAt = verifiedAt,
            canonicalTitle = resolvedTitle,
            backendId = resolvedBackendId,
            anilistId = anilistId
        )

        val store = resolveCloudStore()
        if (store == null) {
            val reason = "Firestore instance unavailable."
            lastWriteRejectionReason = reason
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.FAILED, reason)
            outboxMap()[urlOutboxKey] = pendingResolved
            recordWriteAudit(
                operation = FirestoreWriteOperation.UPDATE,
                contentType = contentTypeLabel,
                canonicalTitle = resolvedTitle,
                anilistId = contentKey,
                backendId = resolvedBackendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = updatedEntity.providerId,
                sourceId = updatedEntity.sourceId,
                targetPath = targetPath,
                startedAtMs = startedAt,
                success = false,
                failureReason = reason,
                firebaseErrorCode = null
            )
            return false
        }
        if (!isClientWriteAuthorized()) {
            val reason = "Stream URL shared write deferred: unauthenticated session."
            lastWriteRejectionReason = reason
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.DEFERRED_UNAUTHENTICATED, reason, "UNAUTHENTICATED")
            outboxMap()[urlOutboxKey] = pendingResolved
            recordWriteAudit(
                operation = FirestoreWriteOperation.UPDATE,
                contentType = contentTypeLabel,
                canonicalTitle = resolvedTitle,
                anilistId = contentKey,
                backendId = resolvedBackendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = updatedEntity.providerId,
                sourceId = updatedEntity.sourceId,
                targetPath = targetPath,
                startedAtMs = startedAt,
                success = false,
                failureReason = reason,
                firebaseErrorCode = "UNAUTHENTICATED"
            )
            return false
        }

        val providerName = streamCacheDao.getAllProviders(sourceEntity.scopeKey)
            .firstOrNull { it.providerId == updatedEntity.providerId }?.providerName
            ?: updatedEntity.sourceName

        return try {
            store.runTx("publish_resolved_url", targetPath) { currentSnap ->
                val remoteVersion = currentSnap?.data?.longValue("version") ?: 0L
                val effectiveVersion = maxOf(remoteVersion + 1L, nextVersion)
                val parentWrites = buildParentEnsureWrites(
                    contentKey = contentKey,
                    anilistId = anilistId,
                    canonicalTitle = resolvedTitle,
                    isMovie = isMovie,
                    backendId = resolvedBackendId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    backendEpisodeId = updatedEntity.backendEpisodeId,
                    providerId = updatedEntity.providerId,
                    providerName = providerName,
                    version = effectiveVersion,
                    now = verifiedAt
                )
                val sourceWrite = StreamingCloudStore.DocWrite(
                    path = targetPath,
                    data = buildSourceFirestorePayload(
                        src = updatedEntity.copy(version = effectiveVersion),
                        providerName = providerName
                    )
                )
                Unit to (parentWrites + sourceWrite)
            }
            lastWriteRejectionReason = null
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.SUCCEEDED)
            outboxMap().remove(urlOutboxKey)
            Log.i(
                TAG,
                "Firestore UPDATE succeeded operation=publish_resolved_url " +
                    "path=$targetPath provider=${updatedEntity.providerId} source=${updatedEntity.sourceId} " +
                    "status=${StreamRecordStatus.AVAILABLE.name}"
            )
            recordWriteAudit(
                operation = FirestoreWriteOperation.UPDATE,
                contentType = contentTypeLabel,
                canonicalTitle = resolvedTitle,
                anilistId = contentKey,
                backendId = resolvedBackendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = updatedEntity.providerId,
                sourceId = updatedEntity.sourceId,
                targetPath = targetPath,
                startedAtMs = startedAt,
                success = true
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FirestoreOperationException) {
            val msg = handleWriteFailure("publish_resolved_url", error)
            Log.e(TAG, "Firestore UPDATE failed operation=publish_resolved_url path=$targetPath: $msg", error)
            if (shouldQueueForRetry(error.category)) {
                outboxMap()[urlOutboxKey] = pendingResolved
            } else {
                outboxMap().remove(urlOutboxKey)
            }
            recordWriteAudit(
                operation = FirestoreWriteOperation.UPDATE,
                contentType = contentTypeLabel,
                canonicalTitle = resolvedTitle,
                anilistId = contentKey,
                backendId = resolvedBackendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = updatedEntity.providerId,
                sourceId = updatedEntity.sourceId,
                targetPath = targetPath,
                startedAtMs = startedAt,
                success = false,
                failureReason = msg,
                firebaseErrorCode = extractFirebaseErrorCode(error) ?: error.category.name
            )
            false
        } catch (error: Exception) {
            val code = extractFirebaseErrorCode(error)
            val msg = "Failed to share resolved URL to Firestore (Room preserved): ${error.message}"
            Log.e(TAG, "Firestore UPDATE failed operation=publish_resolved_url path=$targetPath: $msg", error)
            lastWriteRejectionReason = msg
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.DEFERRED_OFFLINE_OR_TRANSIENT, msg, code)
            outboxMap()[urlOutboxKey] = pendingResolved
            recordWriteAudit(
                operation = FirestoreWriteOperation.UPDATE,
                contentType = contentTypeLabel,
                canonicalTitle = resolvedTitle,
                anilistId = contentKey,
                backendId = resolvedBackendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = updatedEntity.providerId,
                sourceId = updatedEntity.sourceId,
                targetPath = targetPath,
                startedAtMs = startedAt,
                success = false,
                failureReason = msg,
                firebaseErrorCode = code
            )
            false
        }
    }

    suspend fun recordPlaybackSuccess(
        sourceEntity: StreamSourceEntity,
        contentKey: String,
        isMovie: Boolean,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        canonicalTitle: String? = null,
        backendId: String? = null
    ): Boolean {
        if (!SafePlaybackHeaders.isValidStreamUrl(sourceEntity.streamUrl) ||
            SourceExpiryTime.isExpiredOrInvalid(sourceEntity.expiresAt)
        ) {
            val reason = "Playback success ignored: source has no valid, unexpired stream URL."
            lastWriteRejectionReason = reason
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.FAILED, reason)
            Log.w(TAG, "$reason provider=${sourceEntity.providerId} source=${sourceEntity.sourceId}")
            return false
        }
        val now = System.currentTimeMillis()
        streamCacheDao.upsertSource(
            sourceEntity.copy(
                lastPlaybackSuccessAt = now,
                lastVerifiedAt = now,
                status = StreamRecordStatus.AVAILABLE.name,
                updatedAt = now
            )
        )
        val contentTypeLabel = if (isMovie) "MOVIE" else "ANIME"
        val targetPath = sourceDocumentPath(contentKey, isMovie, seasonNumber, episodeNumber, sourceEntity.providerId, sourceEntity.sourceId)
        val seasonIdStr = if (isMovie) null else ContentIdentityHelper.seasonId(seasonNumber)
        val episodeIdStr = if (isMovie) null else ContentIdentityHelper.episodeId(episodeNumber)
        val startedAt = System.currentTimeMillis()
        val outboxKey = "playback_success:${sourceEntity.sourceDocKey}"
        val pendingSuccess = PendingOutboxWrite.PlaybackSuccess(
            key = outboxKey,
            sourceEntity = sourceEntity,
            contentKey = contentKey,
            isMovie = isMovie,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            canonicalTitle = canonicalTitle,
            backendId = backendId
        )

        val store = resolveCloudStore()
        if (store == null) {
            val reason = "Firestore instance unavailable."
            lastWriteRejectionReason = reason
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.FAILED, reason)
            outboxMap()[outboxKey] = pendingSuccess
            return false
        }
        if (!isClientWriteAuthorized()) {
            val reason = "Playback success sync deferred: unauthenticated session."
            lastWriteRejectionReason = reason
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.DEFERRED_UNAUTHENTICATED, reason, "UNAUTHENTICATED")
            outboxMap()[outboxKey] = pendingSuccess
            recordWriteAudit(
                operation = FirestoreWriteOperation.UPDATE,
                contentType = contentTypeLabel,
                canonicalTitle = canonicalTitle,
                anilistId = contentKey,
                backendId = backendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = sourceEntity.providerId,
                sourceId = sourceEntity.sourceId,
                targetPath = targetPath,
                startedAtMs = startedAt,
                success = false,
                failureReason = reason,
                firebaseErrorCode = "UNAUTHENTICATED"
            )
            return false
        }

        return try {
            store.mergeDoc(
                operation = "record_playback_success",
                path = targetPath,
                data = mapOf(
                    "provider_id" to sourceEntity.providerId,
                    "source_id" to sourceEntity.sourceId,
                    "last_playback_success_at" to now,
                    "last_verified_at" to now,
                    "status" to StreamRecordStatus.AVAILABLE.name,
                    "updated_at" to now
                )
            )
            lastWriteRejectionReason = null
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.SUCCEEDED)
            outboxMap().remove(outboxKey)
            recordWriteAudit(
                operation = FirestoreWriteOperation.UPDATE,
                contentType = contentTypeLabel,
                canonicalTitle = canonicalTitle,
                anilistId = contentKey,
                backendId = backendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = sourceEntity.providerId,
                sourceId = sourceEntity.sourceId,
                targetPath = targetPath,
                startedAtMs = startedAt,
                success = true
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FirestoreOperationException) {
            val msg = handleWriteFailure("record_playback_success", error)
            if (shouldQueueForRetry(error.category)) {
                outboxMap()[outboxKey] = pendingSuccess
            } else {
                outboxMap().remove(outboxKey)
            }
            recordWriteAudit(
                operation = FirestoreWriteOperation.UPDATE,
                contentType = contentTypeLabel,
                canonicalTitle = canonicalTitle,
                anilistId = contentKey,
                backendId = backendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = sourceEntity.providerId,
                sourceId = sourceEntity.sourceId,
                targetPath = targetPath,
                startedAtMs = startedAt,
                success = false,
                failureReason = msg,
                firebaseErrorCode = extractFirebaseErrorCode(error) ?: error.category.name
            )
            false
        } catch (error: Exception) {
            val code = extractFirebaseErrorCode(error)
            val msg = error.message ?: "Unknown write error"
            lastWriteRejectionReason = msg
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.DEFERRED_OFFLINE_OR_TRANSIENT, msg, code)
            outboxMap()[outboxKey] = pendingSuccess
            recordWriteAudit(
                operation = FirestoreWriteOperation.UPDATE,
                contentType = contentTypeLabel,
                canonicalTitle = canonicalTitle,
                anilistId = contentKey,
                backendId = backendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = sourceEntity.providerId,
                sourceId = sourceEntity.sourceId,
                targetPath = targetPath,
                startedAtMs = startedAt,
                success = false,
                failureReason = msg,
                firebaseErrorCode = code
            )
            false
        }
    }

    /**
     * Soft-deletes a confirmed permanently removed/obsolete source (NEVER called for timeouts/temporary errors).
     */
    suspend fun softDeleteSource(
        sourceEntity: StreamSourceEntity,
        contentKey: String,
        isMovie: Boolean,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        canonicalTitle: String? = null,
        backendId: String? = null
    ): Boolean {
        val now = System.currentTimeMillis()
        val nextVersion = sourceEntity.version + 1L
        streamCacheDao.softDeleteSource(sourceEntity.sourceDocKey, now, nextVersion)

        val contentTypeLabel = if (isMovie) "MOVIE" else "ANIME"
        val targetPath = sourceDocumentPath(contentKey, isMovie, seasonNumber, episodeNumber, sourceEntity.providerId, sourceEntity.sourceId)
        val seasonIdStr = if (isMovie) null else ContentIdentityHelper.seasonId(seasonNumber)
        val episodeIdStr = if (isMovie) null else ContentIdentityHelper.episodeId(episodeNumber)
        val startedAt = System.currentTimeMillis()
        val outboxKey = "soft_delete:${sourceEntity.sourceDocKey}"
        val pendingDelete = PendingOutboxWrite.SoftDelete(
            key = outboxKey,
            sourceEntity = sourceEntity,
            contentKey = contentKey,
            isMovie = isMovie,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            canonicalTitle = canonicalTitle,
            backendId = backendId
        )

        val store = resolveCloudStore()
        if (store == null) {
            val reason = "Firestore instance unavailable."
            lastWriteRejectionReason = reason
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.FAILED, reason)
            outboxMap()[outboxKey] = pendingDelete
            return false
        }
        if (!isClientWriteAuthorized()) {
            val reason = "Soft delete write deferred: unauthenticated session."
            lastWriteRejectionReason = reason
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.DEFERRED_UNAUTHENTICATED, reason, "UNAUTHENTICATED")
            outboxMap()[outboxKey] = pendingDelete
            recordWriteAudit(
                operation = FirestoreWriteOperation.REMOVE,
                contentType = contentTypeLabel,
                canonicalTitle = canonicalTitle,
                anilistId = contentKey,
                backendId = backendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = sourceEntity.providerId,
                sourceId = sourceEntity.sourceId,
                targetPath = targetPath,
                startedAtMs = startedAt,
                success = false,
                failureReason = reason,
                firebaseErrorCode = "UNAUTHENTICATED"
            )
            return false
        }

        return try {
            store.mergeDoc(
                operation = "soft_delete_source",
                path = targetPath,
                data = mapOf(
                    "provider_id" to sourceEntity.providerId,
                    "source_id" to sourceEntity.sourceId,
                    "status" to StreamRecordStatus.REMOVED.name,
                    "deleted_at" to now,
                    "updated_at" to now,
                    "version" to nextVersion
                )
            )
            lastWriteRejectionReason = null
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.SUCCEEDED)
            outboxMap().remove(outboxKey)
            recordWriteAudit(
                operation = FirestoreWriteOperation.REMOVE,
                contentType = contentTypeLabel,
                canonicalTitle = canonicalTitle,
                anilistId = contentKey,
                backendId = backendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = sourceEntity.providerId,
                sourceId = sourceEntity.sourceId,
                targetPath = targetPath,
                startedAtMs = startedAt,
                success = true
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FirestoreOperationException) {
            val msg = handleWriteFailure("soft_delete_source", error)
            if (shouldQueueForRetry(error.category)) {
                outboxMap()[outboxKey] = pendingDelete
            } else {
                outboxMap().remove(outboxKey)
            }
            recordWriteAudit(
                operation = FirestoreWriteOperation.REMOVE,
                contentType = contentTypeLabel,
                canonicalTitle = canonicalTitle,
                anilistId = contentKey,
                backendId = backendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = sourceEntity.providerId,
                sourceId = sourceEntity.sourceId,
                targetPath = targetPath,
                startedAtMs = startedAt,
                success = false,
                failureReason = msg,
                firebaseErrorCode = extractFirebaseErrorCode(error) ?: error.category.name
            )
            false
        } catch (error: Exception) {
            val code = extractFirebaseErrorCode(error)
            val msg = error.message ?: "Unknown write error"
            lastWriteRejectionReason = msg
            lastWriteOutcome = WriteOutcome(WriteOutcomeState.DEFERRED_OFFLINE_OR_TRANSIENT, msg, code)
            outboxMap()[outboxKey] = pendingDelete
            recordWriteAudit(
                operation = FirestoreWriteOperation.REMOVE,
                contentType = contentTypeLabel,
                canonicalTitle = canonicalTitle,
                anilistId = contentKey,
                backendId = backendId,
                seasonId = seasonIdStr,
                episodeId = episodeIdStr,
                providerId = sourceEntity.providerId,
                sourceId = sourceEntity.sourceId,
                targetPath = targetPath,
                startedAtMs = startedAt,
                success = false,
                failureReason = msg,
                firebaseErrorCode = code
            )
            false
        }
    }

    /**
     * Atomic distributed resolve lease in `stream_resolve_locks/{lockKey}`.
     * Prevents concurrent devices from resolving the same missing Source URL simultaneously,
     * while recovering cleanly via `expires_at_ms` if a lock-holding device crashes.
     */
    suspend fun acquireResolveLease(
        contentKey: String,
        isMovie: Boolean,
        providerId: String,
        sourceId: String,
        seasonNumber: Int = 0,
        episodeNumber: Int = 0,
        ttlMs: Long = DEFAULT_LEASE_TTL_MS
    ): ResolveLease? {
        val now = System.currentTimeMillis()
        val expiresAt = now + ttlMs
        val lockKey = ContentIdentityHelper.resolveLockKey(contentKey, isMovie, providerId, sourceId, seasonNumber, episodeNumber)

        val existingLocal = localLocks[lockKey]
        if (existingLocal != null && existingLocal.expiresAtMs > now && existingLocal.ownerId != ownerId) {
            return null
        }

        val store = resolveCloudStore()
        if (store == null || !isClientWriteAuthorized()) {
            val lease = ResolveLease(lockKey, ownerId, acquiredRemote = false, expiresAtMs = expiresAt)
            localLocks[lockKey] = lease
            return lease
        }

        val lockPath = "$COLLECTION_RESOLVE_LOCKS/$lockKey"
        return try {
            val acquired = store.runTx("acquire_resolve_lease", lockPath) { snap ->
                val remoteStatus = snap?.data?.stringValue("resolve_status")
                val remoteExpiresAt = snap?.data?.longValue("expires_at_ms") ?: 0L
                val remoteOwner = snap?.data?.stringValue("lock_owner")
                val busyByOther = snap?.exists == true &&
                    remoteStatus == "IN_PROGRESS" &&
                    remoteExpiresAt > now &&
                    remoteOwner != null &&
                    remoteOwner != ownerId

                if (busyByOther) {
                    false to emptyList()
                } else {
                    true to listOf(
                        StreamingCloudStore.DocWrite(
                            path = lockPath,
                            data = mapOf(
                                "lock_key" to lockKey,
                                "content_id" to contentKey,
                                "provider_id" to providerId,
                                "source_id" to sourceId,
                                "lock_owner" to ownerId,
                                "acquired_at" to now,
                                "expires_at_ms" to expiresAt,
                                "resolve_status" to "IN_PROGRESS",
                                "updated_at" to now
                            )
                        )
                    )
                }
            }
            if (acquired) {
                ResolveLease(lockKey, ownerId, acquiredRemote = true, expiresAtMs = expiresAt).also {
                    localLocks[lockKey] = it
                }
            } else {
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FirestoreOperationException) {
            handleWriteFailure("acquire_resolve_lease", error)
            val fallbackLease = ResolveLease(lockKey, ownerId, acquiredRemote = false, expiresAtMs = expiresAt)
            localLocks[lockKey] = fallbackLease
            fallbackLease
        } catch (_: Exception) {
            val fallbackLease = ResolveLease(lockKey, ownerId, acquiredRemote = false, expiresAtMs = expiresAt)
            localLocks[lockKey] = fallbackLease
            fallbackLease
        }
    }

    suspend fun releaseResolveLease(lease: ResolveLease, resolveStatus: String) {
        localLocks.remove(lease.lockKey)
        if (!lease.acquiredRemote) return
        val store = resolveCloudStore() ?: return
        if (!isClientWriteAuthorized()) return
        runCatching {
            val lockPath = "$COLLECTION_RESOLVE_LOCKS/${lease.lockKey}"
            val now = System.currentTimeMillis()
            store.runTx("release_resolve_lease", lockPath) { snap ->
                val writes = if (snap?.data?.stringValue("lock_owner") == lease.ownerId) {
                    listOf(
                        StreamingCloudStore.DocWrite(
                            path = lockPath,
                            data = mapOf(
                                "resolve_status" to resolveStatus,
                                "expires_at_ms" to now,
                                "updated_at" to now
                            )
                        )
                    )
                } else {
                    emptyList()
                }
                Unit to writes
            }
        }
    }

    private fun buildDiscoveryWrites(
        contentKey: String,
        anilistId: Int?,
        canonicalTitle: String?,
        isMovie: Boolean,
        backendId: String?,
        seasonNumber: Int,
        episodeNumber: Int,
        backendEpisodeId: String?,
        seasonCount: Int?,
        totalEpisodeCount: Int?,
        seasonEpisodeCount: Int,
        providers: List<StreamProviderEntity>,
        sources: List<StreamSourceEntity>,
        providerNamesById: Map<String, String>,
        discoveryStatus: DiscoveryStatus,
        version: Long,
        now: Long
    ): List<StreamingCloudStore.DocWrite> {
        val writes = mutableListOf<StreamingCloudStore.DocWrite>()
        val activeProviders = providers.filter { it.status != "REMOVED" && it.deletedAt == null }
        val activeSources = sources.filter { it.status != "REMOVED" && it.deletedAt == null }
        val cPath = containerDocumentPath(contentKey, isMovie)

        if (isMovie) {
            writes += StreamingCloudStore.DocWrite(
                path = cPath,
                data = buildMap {
                    put("movie_id", contentKey)
                    anilistId?.let { put("anilist_id", it) }
                    canonicalTitle?.let { put("canonical_title", it) }
                    put("content_type", "MOVIE")
                    backendId?.let { put("backend_id", it) }
                    put("provider_count", activeProviders.size)
                    put("source_count", activeSources.size)
                    put("discovery_status", discoveryStatus.name)
                    put("version", version)
                    put("updated_at", now)
                }
            )
        } else {
            val sId = ContentIdentityHelper.seasonId(seasonNumber)
            val eId = ContentIdentityHelper.episodeId(episodeNumber)
            val resolvedAniListId = anilistId ?: contentKey.toIntOrNull()

            writes += StreamingCloudStore.DocWrite(
                path = cPath,
                data = buildMap {
                    resolvedAniListId?.let { put("anilist_id", it) }
                    canonicalTitle?.let { put("canonical_title", it) }
                    put("content_type", "ANIME")
                    backendId?.let { put("backend_id", it) }
                    seasonCount?.let { put("season_count", it) }
                    totalEpisodeCount?.let { put("total_episode_count", it) }
                    put("provider_discovery_status", discoveryStatus.name)
                    put("version", version)
                    put("updated_at", now)
                }
            )

            writes += StreamingCloudStore.DocWrite(
                path = animeSeasonPath(contentKey, seasonNumber),
                data = buildMap {
                    put("season_id", sId)
                    put("season_number", seasonNumber)
                    resolvedAniListId?.let { put("anilist_id", it) }
                    put("episode_count", seasonEpisodeCount)
                    put("discovery_status", discoveryStatus.name)
                    put("version", version)
                    put("updated_at", now)
                }
            )

            writes += StreamingCloudStore.DocWrite(
                path = animeEpisodePath(contentKey, seasonNumber, episodeNumber),
                data = buildMap {
                    put("episode_id", eId)
                    put("season_id", sId)
                    put("season_number", seasonNumber)
                    put("episode_number", episodeNumber)
                    backendEpisodeId?.let { put("backend_episode_id", it) }
                    put("provider_count", activeProviders.size)
                    put("source_count", activeSources.size)
                    put("discovery_status", discoveryStatus.name)
                    put("version", version)
                    put("updated_at", now)
                }
            )
        }

        val scopeDocPath = scopePath(contentKey, isMovie, seasonNumber, episodeNumber)
        for (provider in providers) {
            writes += StreamingCloudStore.DocWrite(
                path = "$scopeDocPath/$SUB_PROVIDERS/${provider.providerId}",
                data = buildMap {
                    put("provider_id", provider.providerId)
                    put("provider_name", provider.providerName)
                    put("status", provider.status)
                    put("source_count", provider.sourceCount)
                    put("version", provider.version)
                    put("updated_at", provider.updatedAt)
                    provider.deletedAt?.let { put("deleted_at", it) }
                }
            )
        }

        for (src in sources) {
            writes += StreamingCloudStore.DocWrite(
                path = sourceDocumentPath(contentKey, isMovie, seasonNumber, episodeNumber, src.providerId, src.sourceId),
                data = buildSourceFirestorePayload(src, providerNamesById[src.providerId])
            )
        }
        return writes
    }

    private fun buildParentEnsureWrites(
        contentKey: String,
        anilistId: Int?,
        canonicalTitle: String?,
        isMovie: Boolean,
        backendId: String?,
        seasonNumber: Int,
        episodeNumber: Int,
        backendEpisodeId: String?,
        providerId: String,
        providerName: String,
        version: Long,
        now: Long
    ): List<StreamingCloudStore.DocWrite> {
        val writes = mutableListOf<StreamingCloudStore.DocWrite>()
        val cPath = containerDocumentPath(contentKey, isMovie)
        val resolvedAniListId = anilistId ?: contentKey.toIntOrNull()

        if (isMovie) {
            writes += StreamingCloudStore.DocWrite(
                path = cPath,
                data = buildMap {
                    put("movie_id", contentKey)
                    resolvedAniListId?.let { put("anilist_id", it) }
                    canonicalTitle?.let { put("canonical_title", it) }
                    put("content_type", "MOVIE")
                    backendId?.let { put("backend_id", it) }
                    put("discovery_status", DiscoveryStatus.COMPLETED.name)
                    put("version", version)
                    put("updated_at", now)
                }
            )
        } else {
            val sId = ContentIdentityHelper.seasonId(seasonNumber)
            val eId = ContentIdentityHelper.episodeId(episodeNumber)
            writes += StreamingCloudStore.DocWrite(
                path = cPath,
                data = buildMap {
                    resolvedAniListId?.let { put("anilist_id", it) }
                    canonicalTitle?.let { put("canonical_title", it) }
                    put("content_type", "ANIME")
                    backendId?.let { put("backend_id", it) }
                    put("provider_discovery_status", DiscoveryStatus.COMPLETED.name)
                    put("version", version)
                    put("updated_at", now)
                }
            )
            writes += StreamingCloudStore.DocWrite(
                path = animeSeasonPath(contentKey, seasonNumber),
                data = buildMap {
                    put("season_id", sId)
                    put("season_number", seasonNumber)
                    resolvedAniListId?.let { put("anilist_id", it) }
                    put("discovery_status", DiscoveryStatus.COMPLETED.name)
                    put("version", version)
                    put("updated_at", now)
                }
            )
            writes += StreamingCloudStore.DocWrite(
                path = animeEpisodePath(contentKey, seasonNumber, episodeNumber),
                data = buildMap {
                    put("episode_id", eId)
                    put("season_id", sId)
                    put("season_number", seasonNumber)
                    put("episode_number", episodeNumber)
                    backendEpisodeId?.let { put("backend_episode_id", it) }
                    put("discovery_status", DiscoveryStatus.COMPLETED.name)
                    put("version", version)
                    put("updated_at", now)
                }
            )
        }

        val scopeDocPath = scopePath(contentKey, isMovie, seasonNumber, episodeNumber)
        writes += StreamingCloudStore.DocWrite(
            path = "$scopeDocPath/$SUB_PROVIDERS/$providerId",
            data = mapOf(
                "provider_id" to providerId,
                "provider_name" to providerName,
                "status" to StreamRecordStatus.AVAILABLE.name,
                "version" to version,
                "updated_at" to now
            )
        )
        return writes
    }

    private suspend fun persistScopeToRoom(
        contentKey: String,
        canonicalTitle: String?,
        isMovie: Boolean,
        seasonNumber: Int,
        episodeNumber: Int,
        catalogEpisodeId: String?,
        backendId: String?,
        backendEpisodeId: String?,
        anilistId: Int? = contentKey.toIntOrNull(),
        seasonCount: Int? = null,
        totalEpisodeCount: Int? = null,
        seasonEpisodeCount: Int = 0,
        discoveryStatus: String,
        version: Long,
        providers: List<StreamProviderEntity>,
        sources: List<StreamSourceEntity>,
        authoritativeComplete: Boolean
    ) {
        val txStarted = MonotonicMetrics.nowMs()
        val now = System.currentTimeMillis()
        val containerScopeKey = ContentIdentityHelper.containerScopeKey(contentKey, isMovie)
        val existingContainer = streamCacheDao.getContainer(containerScopeKey)
        val activeProvidersCount = providers.count { it.status != "REMOVED" && it.deletedAt == null }
        val activeSourcesCount = sources.count { it.status != "REMOVED" && it.deletedAt == null }

        streamCacheDao.upsertContainer(
            StreamContainerEntity(
                scopeKey = containerScopeKey,
                contentKey = contentKey,
                anilistId = anilistId ?: existingContainer?.anilistId,
                contentType = if (isMovie) "MOVIE" else "ANIME",
                backendId = backendId ?: existingContainer?.backendId,
                canonicalTitle = canonicalTitle ?: existingContainer?.canonicalTitle,
                seasonCount = if (isMovie) null else (seasonCount ?: existingContainer?.seasonCount),
                totalEpisodeCount = if (isMovie) null else (totalEpisodeCount ?: existingContainer?.totalEpisodeCount),
                providerCount = if (isMovie) activeProvidersCount else maxOf(activeProvidersCount, existingContainer?.providerCount ?: 0),
                sourceCount = if (isMovie) activeSourcesCount else maxOf(activeSourcesCount, existingContainer?.sourceCount ?: 0),
                discoveryStatus = discoveryStatus,
                version = maxOf(version, existingContainer?.version ?: 1L),
                updatedAt = now
            )
        )

        if (!isMovie) {
            val sDocKey = "$contentKey:s$seasonNumber"
            val existingSeason = streamCacheDao.getSeason(sDocKey)
            streamCacheDao.upsertSeason(
                StreamSeasonEntity(
                    seasonDocKey = sDocKey,
                    contentKey = contentKey,
                    seasonId = ContentIdentityHelper.seasonId(seasonNumber),
                    seasonNumber = seasonNumber,
                    backendSeasonId = existingSeason?.backendSeasonId,
                    episodeCount = if (seasonEpisodeCount > 0) seasonEpisodeCount else (existingSeason?.episodeCount ?: 0),
                    discoveryStatus = discoveryStatus,
                    version = maxOf(version, existingSeason?.version ?: 1L),
                    updatedAt = now
                )
            )
            val eDocKey = "$contentKey:s$seasonNumber:e$episodeNumber"
            val existingEpisode = streamCacheDao.getEpisode(eDocKey)
            streamCacheDao.upsertEpisode(
                StreamEpisodeEntity(
                    episodeDocKey = eDocKey,
                    contentKey = contentKey,
                    seasonId = ContentIdentityHelper.seasonId(seasonNumber),
                    episodeId = ContentIdentityHelper.episodeId(episodeNumber),
                    catalogEpisodeId = catalogEpisodeId ?: existingEpisode?.catalogEpisodeId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    backendEpisodeId = backendEpisodeId ?: existingEpisode?.backendEpisodeId,
                    backendSourceEpisodeIds = existingEpisode?.backendSourceEpisodeIds,
                    providerCount = activeProvidersCount,
                    sourceCount = activeSourcesCount,
                    discoveryStatus = discoveryStatus,
                    version = maxOf(version, existingEpisode?.version ?: 1L),
                    updatedAt = now
                )
            )
        }

        val scopeKey = ContentIdentityHelper.scopeKey(contentKey, isMovie, seasonNumber, episodeNumber)
        streamCacheDao.syncScopeRecords(
            scopeKey = scopeKey,
            incomingProviders = providers,
            incomingSources = sources,
            authoritativeComplete = authoritativeComplete
        )
        MonotonicMetrics.recordDuration("room_transaction_ms", txStarted, "scope=$scopeKey")
    }

    internal fun buildSourceFirestorePayload(
        src: StreamSourceEntity,
        providerName: String? = null
    ): Map<String, Any?> = buildMap {
        put("provider_id", src.providerId)
        put("provider_name", providerName?.takeIf { it.isNotBlank() } ?: src.sourceName)
        put("source_id", src.sourceId)
        put("source_name", src.sourceName)
        src.backendEpisodeId?.let { put("backend_episode_id", it) }
        if (SafePlaybackHeaders.isValidStreamUrl(src.streamUrl)) {
            put("stream_url", src.streamUrl)
        }
        put("url_type", src.urlType)
        src.contentType?.let { put("content_type", it) }
        src.quality?.let { put("quality", it) }
        src.codec?.let { put("codec", it) }
        src.audioLanguage?.let { put("audio_language", it) }
        src.downloadable?.let { put("downloadable", it) }
        src.sizeBytes?.let { put("size_bytes", it) }
        val safeHeadersMap = SafePlaybackHeaders.toSafeMap(SafePlaybackHeaders.fromJson(src.safeHeadersJson))
        if (safeHeadersMap.isNotEmpty()) {
            put("safe_headers", safeHeadersMap)
        }
        src.expiresAt?.let { put("expires_at", it) }
        put("status", src.status)
        put("version", src.version)
        src.lastVerifiedAt?.let { put("last_verified_at", it) }
        src.lastPlaybackSuccessAt?.let { put("last_playback_success_at", it) }
        put("updated_at", src.updatedAt)
        src.deletedAt?.let { put("deleted_at", it) }
    }

    private fun parseProviderDoc(doc: StreamingCloudStore.RemoteSnapshot, scopeKey: String): StreamProviderEntity? {
        if (!doc.exists) return null
        val providerId = doc.data.stringValue("provider_id")?.takeIf { it.isNotBlank() } ?: doc.id
        return StreamProviderEntity(
            providerDocKey = "$scopeKey|p|$providerId",
            scopeKey = scopeKey,
            providerId = providerId,
            providerName = doc.data.stringValue("provider_name")?.takeIf { it.isNotBlank() } ?: "Unknown provider",
            status = doc.data.stringValue("status") ?: "AVAILABLE",
            sourceCount = (doc.data.longValue("source_count") ?: 0L).toInt(),
            version = doc.data.longValue("version") ?: 1L,
            updatedAt = doc.data.longValue("updated_at") ?: System.currentTimeMillis(),
            deletedAt = doc.data.longValue("deleted_at")
        )
    }

    private fun parseSourceDoc(doc: StreamingCloudStore.RemoteSnapshot, scopeKey: String, providerId: String): StreamSourceEntity? {
        if (!doc.exists) return null
        val sourceId = doc.data.stringValue("source_id")?.takeIf { it.isNotBlank() } ?: doc.id
        val rawUrl = doc.data.stringValue("stream_url")?.takeIf { SafePlaybackHeaders.isValidStreamUrl(it) }
        val rawHeaders = (doc.data["safe_headers"] as? Map<*, *>)?.let { SafePlaybackHeaders.fromSafeMap(it) }.orEmpty()
        return StreamSourceEntity(
            sourceDocKey = "$scopeKey|p|$providerId|s|${ContentIdentityHelper.sanitizeDocId(sourceId)}",
            scopeKey = scopeKey,
            providerId = doc.data.stringValue("provider_id")?.takeIf { it.isNotBlank() } ?: providerId,
            sourceId = sourceId,
            sourceName = doc.data.stringValue("source_name")?.takeIf { it.isNotBlank() }
                ?: doc.data.stringValue("provider_name")?.takeIf { it.isNotBlank() }
                ?: "Unknown provider",
            backendEpisodeId = doc.data.stringValue("backend_episode_id"),
            streamUrl = rawUrl,
            urlType = doc.data.stringValue("url_type") ?: "direct",
            contentType = doc.data.stringValue("content_type"),
            quality = doc.data.stringValue("quality"),
            codec = doc.data.stringValue("codec"),
            audioLanguage = doc.data.stringValue("audio_language"),
            downloadable = doc.data["downloadable"] as? Boolean,
            sizeBytes = doc.data.longValue("size_bytes"),
            safeHeadersJson = SafePlaybackHeaders.toJson(rawHeaders),
            expiresAt = doc.data.stringValue("expires_at"),
            status = doc.data.stringValue("status") ?: if (rawUrl != null) "AVAILABLE" else "MISSING_URL",
            version = doc.data.longValue("version") ?: 1L,
            lastVerifiedAt = doc.data.longValue("last_verified_at"),
            lastPlaybackSuccessAt = doc.data.longValue("last_playback_success_at"),
            updatedAt = doc.data.longValue("updated_at") ?: System.currentTimeMillis(),
            deletedAt = doc.data.longValue("deleted_at")
        )
    }

    private fun recordWriteAudit(
        operation: FirestoreWriteOperation,
        contentType: String,
        canonicalTitle: String?,
        anilistId: String,
        backendId: String?,
        seasonId: String?,
        episodeId: String?,
        providerId: String?,
        sourceId: String?,
        targetPath: String,
        startedAtMs: Long,
        success: Boolean,
        failureReason: String? = null,
        firebaseErrorCode: String? = null
    ) {
        val completedAt = System.currentTimeMillis()
        val redactedReason = failureReason
            ?.replace(Regex("""https?://[^\s\"']+"""), "[redacted-url]")
            ?.replace(Regex("""(?i)(cookie|token|authorization|secret|signature)=[^;\s]+"""), "$1=[redacted]")
        val entry = FirestoreWriteAuditEntry(
            operation = operation,
            contentType = contentType,
            canonicalTitle = canonicalTitle,
            anilistId = anilistId,
            backendId = backendId,
            seasonId = seasonId,
            episodeId = episodeId,
            providerId = providerId,
            sourceId = sourceId,
            targetPath = targetPath,
            startedAtMs = startedAtMs,
            completedAtMs = completedAt,
            success = success,
            failureReason = redactedReason,
            firebaseErrorCode = firebaseErrorCode
        )
        auditHistory.add(entry)
        while (auditHistory.size > MAX_AUDIT_LOGS) {
            runCatching { auditHistory.removeAt(0) }
        }
        _writeAuditLogs.value = auditHistory.toList()

        val summary = buildString {
            append("op=").append(entry.operation.name)
            append(" content_type=").append(entry.contentType)
            append(" canonical_title=").append(entry.canonicalTitle ?: "unknown")
            append(" anilist_id=").append(entry.anilistId)
            append(" backend_id=").append(entry.backendId ?: "none")
            if (entry.seasonId != null) append(" season_id=").append(entry.seasonId)
            if (entry.episodeId != null) append(" episode_id=").append(entry.episodeId)
            append(" provider_id=").append(entry.providerId ?: "none")
            append(" source_id=").append(entry.sourceId ?: "none")
            append(" path=").append(entry.targetPath)
            append(" start_ms=").append(entry.startedAtMs)
            append(" end_ms=").append(entry.completedAtMs)
            append(" success=").append(entry.success)
            if (!entry.success) {
                append(" code=").append(entry.firebaseErrorCode ?: "NONE")
                append(" reason=").append(entry.failureReason ?: "unknown")
            }
        }
        runCatching {
            if (entry.success) Log.i(AUDIT_TAG, summary) else Log.w(AUDIT_TAG, summary)
        }
    }

    private fun shouldQueueForRetry(category: FirestoreFailureCategory): Boolean = when (category) {
        FirestoreFailureCategory.UNAUTHENTICATED,
        FirestoreFailureCategory.UNAVAILABLE,
        FirestoreFailureCategory.DEADLINE_EXCEEDED,
        FirestoreFailureCategory.NETWORK_ERROR,
        FirestoreFailureCategory.RESOURCE_EXHAUSTED -> true
        FirestoreFailureCategory.PERMISSION_DENIED,
        FirestoreFailureCategory.UNKNOWN_ERROR -> false
    }

    suspend fun flushPendingOutbox(): Int {
        val outbox = outboxMap()
        if (outbox.isEmpty()) return 0
        if (!isClientWriteAuthorized() || resolveCloudStore() == null) return 0
        if (FirestoreRequestGuard.cooldownRemainingMs() > 0L) return 0

        var flushed = 0
        val snapshot = outbox.entries.toList()
        for ((key, item) in snapshot) {
            if (FirestoreRequestGuard.cooldownRemainingMs() > 0L) break
            val succeeded = when (item) {
                is PendingOutboxWrite.Discovery -> publishDiscoveryAndSyncRoom(
                    contentKey = item.contentKey,
                    anilistId = item.anilistId,
                    isMovie = item.isMovie,
                    backendId = item.backendId,
                    seasonNumber = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                    catalogEpisodeId = item.catalogEpisodeId,
                    backendEpisodeId = item.backendEpisodeId,
                    seasonCount = item.seasonCount,
                    totalEpisodeCount = item.totalEpisodeCount,
                    seasonEpisodeCount = item.seasonEpisodeCount,
                    providers = item.providers,
                    sources = item.sources,
                    discoveryStatus = item.discoveryStatus,
                    canonicalTitle = item.canonicalTitle
                )
                is PendingOutboxWrite.ResolvedUrl -> publishResolvedStreamUrl(
                    sourceEntity = item.sourceEntity,
                    contentKey = item.contentKey,
                    isMovie = item.isMovie,
                    seasonNumber = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                    streamUrl = item.streamUrl,
                    urlType = item.urlType,
                    contentType = item.contentType,
                    safeHeaders = item.safeHeaders,
                    expiresAt = item.expiresAt,
                    verifiedAt = item.verifiedAt,
                    canonicalTitle = item.canonicalTitle,
                    backendId = item.backendId,
                    anilistId = item.anilistId
                )
                is PendingOutboxWrite.PlaybackSuccess -> recordPlaybackSuccess(
                    sourceEntity = item.sourceEntity,
                    contentKey = item.contentKey,
                    isMovie = item.isMovie,
                    seasonNumber = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                    canonicalTitle = item.canonicalTitle,
                    backendId = item.backendId
                )
                is PendingOutboxWrite.SoftDelete -> softDeleteSource(
                    sourceEntity = item.sourceEntity,
                    contentKey = item.contentKey,
                    isMovie = item.isMovie,
                    seasonNumber = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                    canonicalTitle = item.canonicalTitle,
                    backendId = item.backendId
                )
            }
            if (succeeded) {
                outbox.remove(key)
                flushed++
            }
        }
        return flushed
    }

    private fun handleWriteFailure(operation: String, error: FirestoreOperationException): String {
        val code = extractFirebaseErrorCode(error) ?: error.category.name
        val msg = when (error.category) {
            FirestoreFailureCategory.PERMISSION_DENIED,
            FirestoreFailureCategory.UNAUTHENTICATED ->
                "Firestore $operation rejected by security rules ($code): ${error.cause?.message ?: "missing rule or authorized role on backend."}"
            else -> "Firestore $operation failed ($code): ${error.cause?.message ?: error.message}"
        }
        val state = when (error.category) {
            FirestoreFailureCategory.PERMISSION_DENIED -> WriteOutcomeState.REJECTED_PERMISSION
            FirestoreFailureCategory.UNAUTHENTICATED -> WriteOutcomeState.DEFERRED_UNAUTHENTICATED
            FirestoreFailureCategory.RESOURCE_EXHAUSTED -> WriteOutcomeState.QUOTA_COOLDOWN
            FirestoreFailureCategory.UNAVAILABLE,
            FirestoreFailureCategory.DEADLINE_EXCEEDED,
            FirestoreFailureCategory.NETWORK_ERROR -> WriteOutcomeState.DEFERRED_OFFLINE_OR_TRANSIENT
            FirestoreFailureCategory.UNKNOWN_ERROR -> WriteOutcomeState.FAILED
        }
        lastWriteRejectionReason = msg
        lastWriteOutcome = WriteOutcome(state, msg, code)
        runCatching { Log.w(TAG, msg) }
        return msg
    }

    private fun Map<String, Any?>.stringValue(key: String): String? = this[key] as? String
    private fun Map<String, Any?>.longValue(key: String): Long? = (this[key] as? Number)?.toLong()

    fun isDiscoveryFresh(updatedAt: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        updatedAt > 0L && (nowMs - updatedAt) < DISCOVERY_FRESHNESS_MS
}
