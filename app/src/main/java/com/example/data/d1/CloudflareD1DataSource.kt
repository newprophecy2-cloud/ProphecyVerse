package com.example.data.d1

import android.util.Log
import com.example.data.metrics.DatabaseMetricsTracker
import com.example.data.metrics.PlaybackDiagnosticEvent
import com.example.data.metrics.PlaybackDiagnosticsLogger
import com.example.data.repository.ExpiryState
import com.example.data.repository.SourceExpiryTime
import com.example.data.repository.SourceIdentityValidator
import com.example.data.repository.SourceValidationResult
import com.example.data.streaming.MonotonicMetrics
import com.example.data.streaming.SafePlaybackHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import retrofit2.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

interface D1DigestStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun getLong(key: String): Long?
    fun putLong(key: String, value: Long)
    fun remove(key: String)
    fun clear()
}

class InMemoryD1DigestStore : D1DigestStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) {
        map[key] = value
    }
    override fun getLong(key: String): Long? = map[key]?.toLongOrNull()
    override fun putLong(key: String, value: Long) {
        map[key] = value.toString()
    }
    override fun remove(key: String) {
        map.remove(key)
    }
    override fun clear() {
        map.clear()
    }
}

class SharedPreferencesD1DigestStore(context: android.content.Context) : D1DigestStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        if (prefs.getString(key, null) == value) return
        val editor = prefs.edit()
        val all = prefs.all
        if (all.size >= MAX_PERSISTED_DIGESTS && !all.containsKey(key)) {
            all.keys.take(PRUNE_BATCH).forEach { editor.remove(it) }
        }
        editor.putString(key, value).apply()
    }

    override fun getLong(key: String): Long? =
        if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    override fun putLong(key: String, value: Long) {
        if (prefs.contains(key) && prefs.getLong(key, 0L) == value) return
        prefs.edit().putLong(key, value).apply()
    }

    override fun remove(key: String) {
        if (prefs.contains(key)) {
            prefs.edit().remove(key).apply()
        }
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS_NAME = "prophecy_d1_digests"
        private const val MAX_PERSISTED_DIGESTS = 4000
        private const val PRUNE_BATCH = 500
    }
}

/**
 * Cloudflare D1 REST Data Source backed by the Prophecy-verse-DB Worker API.
 * Strictly stores and validates resolved Stream URLs in the canonical `stream_sources` table,
 * keyed by `(anime_id, episode_id, source_id)` via deterministic `sourceDocKey`,
 * storing `expiresAt` ONLY when actual expiry is known.
 */
class CloudflareD1DataSource(
    private val api: CloudflareD1ApiService,
    private val metricsTracker: DatabaseMetricsTracker = DatabaseMetricsTracker.getInstance(),
    @Volatile private var digestStore: D1DigestStore = InMemoryD1DigestStore()
) {
    data class CachedStreamUrl(
        val animeId: String,
        val episodeId: String,
        val sourceId: String,
        val streamUrl: String,
        val expiresAt: String? = null,
        val urlType: String = "direct",
        val contentType: String? = null,
        val safeHeadersJson: String? = null,
        val safeHeaders: List<List<String>> = emptyList(),
        val providerId: String? = null,
        val quality: String? = null,
        val codec: String? = null,
        val audioLanguage: String? = null,
        val downloadable: Boolean? = null,
        val sizeBytes: Long? = null,
        val lookupDurationMs: Long = 0L
    )

    private val singleFlightScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightReads = ConcurrentHashMap<String, CompletableDeferred<Any?>>()
    private val writtenDigests = ConcurrentHashMap<String, String>()
    private val writeMutexes = ConcurrentHashMap<String, Mutex>()

    fun attachDigestStore(store: D1DigestStore) {
        digestStore = store
    }

    private fun readDigest(key: String): String? =
        writtenDigests[key] ?: digestStore.get(key)

    private fun isPersistedDigestStale(key: String, nowMs: Long): Boolean {
        if (writtenDigests.containsKey(key)) return false
        val savedAt = digestStore.getLong("ts:$key") ?: return true
        return (nowMs - savedAt).coerceAtLeast(0L) >= PERSISTED_DIGEST_MAX_AGE_MS
    }

    private fun saveDigest(key: String, digest: String, nowMs: Long = System.currentTimeMillis()) {
        writtenDigests[key] = digest
        digestStore.put(key, digest)
        digestStore.putLong("ts:$key", nowMs)
    }

    private fun removeDigest(key: String) {
        writtenDigests.remove(key)
        digestStore.remove(key)
        digestStore.remove("ts:$key")
    }

    private suspend fun <T> withWriteLock(key: String, block: suspend () -> T): T {
        val mutex = writeMutexes.computeIfAbsent(key) { Mutex() }
        return mutex.withLock { block() }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> coalesceRead(key: String, block: suspend () -> T): T {
        val existing = inFlightReads[key]
        if (existing != null && existing.isActive) {
            metricsTracker.recordDuplicateRequestPrevented()
            return existing.await() as T
        }
        val deferred = CompletableDeferred<Any?>()
        val raced = inFlightReads.putIfAbsent(key, deferred)
        if (raced != null && raced.isActive) {
            metricsTracker.recordDuplicateRequestPrevented()
            return raced.await() as T
        }
        singleFlightScope.launch {
            try {
                val value = withTimeout(15_000L) { block() }
                deferred.complete(value)
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            } finally {
                inFlightReads.remove(key, deferred)
            }
        }
        return deferred.await() as T
    }

    private suspend fun <T> executeWithRetry(
        operationName: String,
        isWrite: Boolean = false,
        maxRetries: Int = MAX_RETRIES,
        call: suspend () -> Response<T>
    ): Response<T>? {
        var attempt = 0
        while (true) {
            val startedAt = MonotonicMetrics.nowMs()
            try {
                val response = call()
                val elapsed = (MonotonicMetrics.nowMs() - startedAt).coerceAtLeast(0L)
                metricsTracker.recordApiRequest(response.isSuccessful || response.code() == 404)
                if (response.isSuccessful) {
                    if (isWrite) {
                        metricsTracker.recordD1Write(elapsed)
                    } else {
                        val rowCount = extractRowCount(response.body())
                        metricsTracker.recordD1Read(rowsReturned = rowCount, durationMs = elapsed)
                    }
                    return response
                }
                val code = response.code()
                if (code == 404) {
                    if (!isWrite) metricsTracker.recordD1Read(rowsReturned = 0, durationMs = elapsed)
                    return response
                }
                val transient = code == 429 || code in 500..599
                if (!transient || attempt >= maxRetries) {
                    metricsTracker.recordD1Error(elapsed)
                    val errBody = runCatching { response.errorBody()?.string()?.take(180) }
                        .getOrNull()
                        ?.let(PlaybackDiagnosticsLogger::sanitize)
                    Log.w(TAG, "D1 $operationName failed with HTTP $code ${errBody.orEmpty()}".trim())
                    return response
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: IOException) {
                val elapsed = (MonotonicMetrics.nowMs() - startedAt).coerceAtLeast(0L)
                metricsTracker.recordApiRequest(false)
                if (attempt >= maxRetries) {
                    metricsTracker.recordD1Error(elapsed)
                    Log.w(TAG, "D1 $operationName network error: ${e::class.simpleName}")
                    return null
                }
            } catch (e: Exception) {
                val elapsed = (MonotonicMetrics.nowMs() - startedAt).coerceAtLeast(0L)
                metricsTracker.recordApiRequest(false)
                metricsTracker.recordD1Error(elapsed)
                Log.w(TAG, "D1 $operationName unexpected error: ${e::class.simpleName}")
                return null
            }

            attempt++
            val baseDelay = BASE_RETRY_DELAY_MS shl (attempt - 1)
            val jitter = ThreadLocalRandom.current().nextLong(0L, 120L)
            delay(baseDelay + jitter)
        }
    }

    private fun extractRowCount(body: Any?): Int = when (body) {
        is D1ListResponse<*> -> body.data?.size ?: body.count ?: 0
        is D1RecordResponse<*> -> if (body.data != null) 1 else 0
        is D1TablesResponse -> body.tables.size
        else -> 0
    }

    /**
     * Verifies D1 Worker health and real queryability of the canonical `stream_sources` table.
     */
    suspend fun verifyHealth(): Boolean {
        val tablesResp = executeWithRetry("verifyHealthTables") { api.listTables() } ?: return false
        val tablesBody = tablesResp.body() ?: return false
        if (!tablesResp.isSuccessful || !tablesBody.success) return false
        val hasCanonicalTable = tablesBody.tables.any { it.equals(CANONICAL_TABLE, ignoreCase = true) }
        if (!hasCanonicalTable) {
            Log.w(TAG, "D1 health check schema mismatch: '$CANONICAL_TABLE' table missing; available=${tablesBody.tables}")
            return false
        }

        val probeResp = executeWithRetry("verifyHealthStreamSources") { api.listStreamSources(limit = 1) } ?: return false
        val probeBody = probeResp.body() ?: return false
        if (!probeResp.isSuccessful || !probeBody.success) return false
        val tableMatches = probeBody.table.isNullOrBlank() ||
            probeBody.table.equals(CANONICAL_TABLE, ignoreCase = true)
        if (!tableMatches) {
            Log.w(TAG, "D1 health check unexpected table '${probeBody.table}' for '$CANONICAL_TABLE'")
        }
        return tableMatches
    }

    data class EpisodeStreamsLookupResult(
        val streams: List<CachedStreamUrl>,
        val querySucceeded: Boolean
    )

    /**
     * Looks up a cached Stream URL from D1 `stream_sources` by exact `(anime_id, episode_id, source_id)`.
     * Primary lookup uses deterministic `sourceDocKey` (1 HTTP GET). Composite `where` fallback is
     * disabled by default to avoid duplicate D1 reads on ordinary cache misses.
     *
     * Note on future Worker optimization: Adding a server-side multi-key endpoint (e.g. batch
     * `sourceDocKey` lookup) on the Prophecy-verse-DB Worker would further collapse multi-episode
     * preload D1 checks into 1 HTTP call per cycle while preserving this client contract.
     */
    suspend fun getValidStreamUrl(
        animeId: String,
        episodeId: String,
        sourceId: String,
        nowMs: Long = System.currentTimeMillis(),
        allowCompositeFallback: Boolean = false
    ): CachedStreamUrl? {
        val normAnime = animeId.trim()
        val normEpisode = episodeId.trim()
        val normSource = sourceId.trim()
        if (normAnime.isEmpty() || normEpisode.isEmpty() || normSource.isEmpty()) return null

        val lockKey = digestKey(normAnime, normEpisode, normSource)
        val recordId = deterministicRecordId(normAnime, normEpisode, normSource)
        val flightKey = "stream_source:$normAnime|$normEpisode|$normSource|fb=$allowCompositeFallback"
        return coalesceRead(flightKey) {
            val lookupStartedAt = MonotonicMetrics.nowMs()
            val resp = executeWithRetry("getStreamSource") {
                api.getStreamSource(recordId)
            }
            if (resp == null) {
                metricsTracker.recordD1CacheMiss()
                return@coalesceRead null
            }

            var resolvedRecord: D1StreamSourceRecordDto? = null
            var expectedDocKeyForValidation: String? = recordId

            if (resp.code() == 404 || resp.code() == 410) {
                if (!allowCompositeFallback) {
                    removeDigest(lockKey)
                    metricsTracker.recordD1CacheMiss()
                    val lookupMs = (MonotonicMetrics.nowMs() - lookupStartedAt).coerceAtLeast(0L)
                    PlaybackDiagnosticsLogger.log(
                        event = PlaybackDiagnosticEvent.D1_CACHE_MISS,
                        animeId = normAnime,
                        episodeId = normEpisode,
                        sourceId = normSource,
                        reason = "http_${resp.code()}",
                        detail = "lookup_ms=$lookupMs fallback=skipped"
                    )
                    return@coalesceRead null
                }
                metricsTracker.recordD1FallbackLookup()
                // Explicit composite fallback only when caller specifically opts in
                val whereJson = buildWhereJson(
                    "scopeKey" to normAnime,
                    "backendEpisodeId" to normEpisode,
                    "sourceId" to normSource
                )
                val listResp = executeWithRetry("listStreamSourceByComposite", maxRetries = 0) {
                    api.listStreamSources(where = whereJson, limit = 10)
                }
                val listBody = listResp?.body()
                val listTableOk = listBody?.table.isNullOrBlank() ||
                    listBody?.table.equals(CANONICAL_TABLE, ignoreCase = true)
                val matchingRow = if (listResp?.isSuccessful == true && listBody?.success == true && listTableOk) {
                    listBody.data.orEmpty()
                        .asSequence()
                        .filter { row ->
                            SourceIdentityValidator.validateD1StreamSourceRecord(
                                record = row,
                                expectedAnimeId = normAnime,
                                expectedEpisodeId = normEpisode,
                                expectedSourceId = normSource,
                                expectedSourceDocKey = null,
                                nowMs = nowMs
                            ).isValid
                        }
                        .maxByOrNull { it.updatedAt }
                } else {
                    null
                }
                if (matchingRow == null) {
                    removeDigest(lockKey)
                    metricsTracker.recordD1CacheMiss()
                    val lookupMs = (MonotonicMetrics.nowMs() - lookupStartedAt).coerceAtLeast(0L)
                    PlaybackDiagnosticsLogger.log(
                        event = PlaybackDiagnosticEvent.D1_CACHE_MISS,
                        animeId = normAnime,
                        episodeId = normEpisode,
                        sourceId = normSource,
                        reason = "http_${resp.code()}",
                        detail = "lookup_ms=$lookupMs"
                    )
                    return@coalesceRead null
                }
                resolvedRecord = matchingRow
                expectedDocKeyForValidation = null
            } else {
                if (!resp.isSuccessful) {
                    metricsTracker.recordD1CacheMiss()
                    return@coalesceRead null
                }
                val body = resp.body()
                val tableMatches = body?.table.isNullOrBlank() ||
                    body?.table.equals(CANONICAL_TABLE, ignoreCase = true)
                if (body == null || !body.success || !tableMatches) {
                    removeDigest(lockKey)
                    metricsTracker.recordD1CacheMiss()
                    val lookupMs = (MonotonicMetrics.nowMs() - lookupStartedAt).coerceAtLeast(0L)
                    Log.w(TAG, "D1 getStreamSource unsuccessful response: ${body?.error ?: "empty_or_invalid_body"}")
                    PlaybackDiagnosticsLogger.log(
                        event = PlaybackDiagnosticEvent.D1_CACHE_MISS,
                        animeId = normAnime,
                        episodeId = normEpisode,
                        sourceId = normSource,
                        reason = "unsuccessful_body",
                        detail = "lookup_ms=$lookupMs"
                    )
                    return@coalesceRead null
                }
                resolvedRecord = body.data ?: run {
                    removeDigest(lockKey)
                    metricsTracker.recordD1CacheMiss()
                    val lookupMs = (MonotonicMetrics.nowMs() - lookupStartedAt).coerceAtLeast(0L)
                    PlaybackDiagnosticsLogger.log(
                        event = PlaybackDiagnosticEvent.D1_CACHE_MISS,
                        animeId = normAnime,
                        episodeId = normEpisode,
                        sourceId = normSource,
                        reason = "empty_data",
                        detail = "lookup_ms=$lookupMs"
                    )
                    return@coalesceRead null
                }
            }

            val record = resolvedRecord
            val validation = SourceIdentityValidator.validateD1StreamSourceRecord(
                record = record,
                expectedAnimeId = normAnime,
                expectedEpisodeId = normEpisode,
                expectedSourceId = normSource,
                expectedSourceDocKey = expectedDocKeyForValidation,
                nowMs = nowMs
            )
            if (validation is SourceValidationResult.Invalid) {
                removeDigest(lockKey)
                metricsTracker.recordD1CacheMiss()
                val lookupMs = (MonotonicMetrics.nowMs() - lookupStartedAt).coerceAtLeast(0L)
                PlaybackDiagnosticsLogger.log(
                    event = PlaybackDiagnosticEvent.D1_CACHE_MISS,
                    animeId = normAnime,
                    episodeId = normEpisode,
                    sourceId = normSource,
                    reason = validation.code.name,
                    detail = "lookup_ms=$lookupMs status=${record.status}"
                )
                return@coalesceRead null
            }

            val candidateUrl = record.streamUrl?.trim().orEmpty()
            val rawExpiry = record.expiresAt?.trim()?.takeIf { it.isNotEmpty() }
            val expiryState = SourceExpiryTime.evaluateStreamExpiry(rawExpiry, candidateUrl, nowMs)
            val normalizedExpiry = when (expiryState) {
                ExpiryState.MISSING -> null
                ExpiryState.VALID -> rawExpiry ?: SourceExpiryTime.deriveIsoExpiryFromUrl(candidateUrl)
                ExpiryState.EXPIRED, ExpiryState.INVALID -> {
                    removeDigest(lockKey)
                    metricsTracker.recordD1CacheMiss()
                    val lookupMs = (MonotonicMetrics.nowMs() - lookupStartedAt).coerceAtLeast(0L)
                    PlaybackDiagnosticsLogger.log(
                        event = PlaybackDiagnosticEvent.D1_CACHE_MISS,
                        animeId = normAnime,
                        episodeId = normEpisode,
                        sourceId = normSource,
                        reason = "expired_or_invalid",
                        detail = "lookup_ms=$lookupMs expiry_state=${expiryState.name}"
                    )
                    return@coalesceRead null
                }
            }

            val restoredHeaders = SafePlaybackHeaders.fromJson(record.safeHeadersJson)
            val restoredHeaderNames = restoredHeaders.joinToString(",") { it[0] }.ifEmpty { "none" }
            val resolvedUrlType = resolveEffectiveUrlType(
                explicitUrlType = record.urlType,
                url = candidateUrl,
                contentType = record.contentType
            )
            val cleanContentType = record.contentType?.trim()?.takeIf { it.isNotEmpty() }

            saveDigest(
                lockKey,
                computeMetadataDigest(
                    animeId = normAnime,
                    episodeId = normEpisode,
                    sourceId = normSource,
                    streamUrl = candidateUrl,
                    expiresAt = rawExpiry,
                    safeHeadersJson = record.safeHeadersJson,
                    contentType = cleanContentType,
                    urlType = resolvedUrlType
                ),
                nowMs
            )
            metricsTracker.recordD1CacheHit()
            val lookupMs = (MonotonicMetrics.nowMs() - lookupStartedAt).coerceAtLeast(0L)
            PlaybackDiagnosticsLogger.log(
                event = PlaybackDiagnosticEvent.D1_CACHE_HIT,
                animeId = normAnime,
                episodeId = normEpisode,
                sourceId = normSource,
                providerLabel = record.providerId,
                detail = "lookup_ms=$lookupMs status=${record.status} expiry_state=${expiryState.name} url_type=$resolvedUrlType headers=$restoredHeaderNames"
            )
            CachedStreamUrl(
                animeId = normAnime,
                episodeId = normEpisode,
                sourceId = normSource,
                streamUrl = candidateUrl,
                expiresAt = normalizedExpiry,
                urlType = resolvedUrlType,
                contentType = cleanContentType,
                safeHeadersJson = record.safeHeadersJson?.trim()?.takeIf { it.isNotEmpty() },
                safeHeaders = restoredHeaders,
                providerId = record.providerId.trim().takeIf { it.isNotEmpty() },
                quality = record.quality?.trim()?.takeIf { it.isNotEmpty() },
                codec = record.codec?.trim()?.takeIf { it.isNotEmpty() },
                audioLanguage = record.audioLanguage?.trim()?.takeIf { it.isNotEmpty() },
                downloadable = record.downloadable,
                sizeBytes = record.sizeBytes,
                lookupDurationMs = lookupMs
            )
        }
    }

    /**
     * Lists all valid, non-deleted, non-expired cached stream source rows for a given `(animeId, episodeId)`
     * in a single coalesced query.
     */
    suspend fun getStreamUrlsForEpisode(
        animeId: String,
        episodeId: String,
        nowMs: Long = System.currentTimeMillis()
    ): List<CachedStreamUrl> = getStreamUrlsForEpisodeResult(animeId, episodeId, nowMs).streams

    suspend fun getStreamUrlsForEpisodeResult(
        animeId: String,
        episodeId: String,
        nowMs: Long = System.currentTimeMillis()
    ): EpisodeStreamsLookupResult {
        val normAnime = animeId.trim()
        val normEpisode = episodeId.trim()
        if (normAnime.isEmpty() || normEpisode.isEmpty()) {
            return EpisodeStreamsLookupResult(emptyList(), querySucceeded = false)
        }

        val flightKey = "episode_streams:$normAnime|$normEpisode"
        return coalesceRead(flightKey) {
            val whereJson = buildWhereJson(
                "scopeKey" to normAnime,
                "backendEpisodeId" to normEpisode
            )
            val resp = executeWithRetry("listEpisodeStreamSources") {
                api.listStreamSources(where = whereJson, limit = 50)
            } ?: return@coalesceRead EpisodeStreamsLookupResult(emptyList(), querySucceeded = false)
            if (!resp.isSuccessful) {
                return@coalesceRead EpisodeStreamsLookupResult(emptyList(), querySucceeded = false)
            }
            val body = resp.body()
                ?: return@coalesceRead EpisodeStreamsLookupResult(emptyList(), querySucceeded = false)
            val tableOk = body.table.isNullOrBlank() || body.table.equals(CANONICAL_TABLE, ignoreCase = true)
            if (!body.success || !tableOk) {
                return@coalesceRead EpisodeStreamsLookupResult(emptyList(), querySucceeded = false)
            }

            val validRows = body.data.orEmpty().mapNotNull { record ->
                val srcId = record.sourceId.trim()
                if (srcId.isEmpty()) return@mapNotNull null
                val valid = SourceIdentityValidator.validateD1StreamSourceRecord(
                    record = record,
                    expectedAnimeId = normAnime,
                    expectedEpisodeId = normEpisode,
                    expectedSourceId = srcId,
                    expectedSourceDocKey = null,
                    nowMs = nowMs
                ).isValid
                if (!valid) return@mapNotNull null
                val url = record.streamUrl?.trim().orEmpty()
                val rawExpiry = record.expiresAt?.trim()?.takeIf { it.isNotEmpty() }
                val effectiveExpiry = rawExpiry ?: SourceExpiryTime.deriveIsoExpiryFromUrl(url)
                val safeHeaders = SafePlaybackHeaders.fromJson(record.safeHeadersJson)
                CachedStreamUrl(
                    animeId = normAnime,
                    episodeId = normEpisode,
                    sourceId = srcId,
                    streamUrl = url,
                    expiresAt = effectiveExpiry,
                    urlType = resolveEffectiveUrlType(record.urlType, url, record.contentType),
                    contentType = record.contentType?.trim()?.takeIf { it.isNotEmpty() },
                    safeHeadersJson = record.safeHeadersJson?.trim()?.takeIf { it.isNotEmpty() },
                    safeHeaders = safeHeaders,
                    providerId = record.providerId.trim().takeIf { it.isNotEmpty() },
                    quality = record.quality?.trim()?.takeIf { it.isNotEmpty() },
                    codec = record.codec?.trim()?.takeIf { it.isNotEmpty() },
                    audioLanguage = record.audioLanguage?.trim()?.takeIf { it.isNotEmpty() },
                    downloadable = record.downloadable,
                    sizeBytes = record.sizeBytes
                )
            }
            if (validRows.isNotEmpty()) {
                metricsTracker.recordD1CacheHit()
            } else {
                metricsTracker.recordD1CacheMiss()
            }
            EpisodeStreamsLookupResult(validRows, querySucceeded = true)
        }
    }

    private suspend fun verifyRemoteRecordMatches(
        recordId: String,
        normAnime: String,
        normEpisode: String,
        normSource: String,
        normUrl: String,
        normalizedExpiry: String?,
        normalizedSafeHeadersJson: String?,
        normalizedContentType: String?,
        nowMs: Long
    ): Boolean {
        val resp = executeWithRetry("verifyRemoteStreamSource", maxRetries = 0) {
            api.getStreamSource(recordId)
        } ?: return false
        if (!resp.isSuccessful) return false
        val body = resp.body() ?: return false
        if (!body.success) return false
        val tableMatches = body.table.isNullOrBlank() ||
            body.table.equals(CANONICAL_TABLE, ignoreCase = true)
        if (!tableMatches) return false
        val remote = body.data ?: return false
        val validation = SourceIdentityValidator.validateD1StreamSourceRecord(
            record = remote,
            expectedAnimeId = normAnime,
            expectedEpisodeId = normEpisode,
            expectedSourceId = normSource,
            expectedSourceDocKey = recordId,
            nowMs = nowMs
        )
        if (validation !is SourceValidationResult.Valid) return false
        val remoteExpiry = remote.expiresAt?.trim()?.takeIf { it.isNotEmpty() }
        val remoteHeadersJson = remote.safeHeadersJson?.trim()?.takeIf { it.isNotEmpty() }
        val remoteContentType = remote.contentType?.trim()?.takeIf { it.isNotEmpty() }
        return remote.streamUrl?.trim() == normUrl &&
            remoteExpiry == normalizedExpiry &&
            remoteHeadersJson == normalizedSafeHeadersJson &&
            remoteContentType == normalizedContentType
    }

    /**
     * Saves a validated resolved Stream URL to D1 `stream_sources` keyed by `(anime_id, episode_id, source_id)`.
     * Stores `expiresAt` ONLY when actual valid expiry is known (explicit or derived from SigV4 URL params),
     * persists only privacy-safe headers via `SafePlaybackHeaders.toJson`, and skips duplicate writes cleanly.
     */
    suspend fun saveStreamUrl(
        animeId: String,
        episodeId: String,
        sourceId: String,
        streamUrl: String,
        expiresAt: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        forceWrite: Boolean = false,
        providerId: String? = null,
        sourceName: String? = null,
        urlType: String? = null,
        contentType: String? = null,
        headers: List<List<String>> = emptyList(),
        quality: String? = null,
        codec: String? = null,
        audioLanguage: String? = null,
        downloadable: Boolean? = null,
        sizeBytes: Long? = null,
        lastPlaybackSuccessAt: Long? = null
    ): Boolean {
        val normAnime = animeId.trim()
        val normEpisode = episodeId.trim()
        val normSource = sourceId.trim()
        val normUrl = streamUrl.trim()
        if (normAnime.isEmpty() || normEpisode.isEmpty() || normSource.isEmpty()) return false
        if (!SafePlaybackHeaders.isValidStreamUrl(normUrl)) return false

        val urlDerivedExpiry = SourceExpiryTime.deriveIsoExpiryFromUrl(normUrl)
        val rawExplicitExpiry = expiresAt?.trim()?.takeIf { it.isNotEmpty() }
        val streamExpiryState = SourceExpiryTime.evaluateStreamExpiry(rawExplicitExpiry, normUrl, nowMs)
        if (streamExpiryState == ExpiryState.EXPIRED) return false

        val normalizedExpiry: String? = when (SourceExpiryTime.evaluateExpiry(rawExplicitExpiry, nowMs)) {
            ExpiryState.VALID -> rawExplicitExpiry
            ExpiryState.EXPIRED -> return false
            ExpiryState.INVALID, ExpiryState.MISSING -> urlDerivedExpiry
        }

        val cleanProviderId = providerId?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_PROVIDER_ID
        val cleanSourceName = sourceName?.trim()?.takeIf { it.isNotEmpty() } ?: normSource
        val cleanContentType = contentType?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedUrlType = resolveEffectiveUrlType(urlType, normUrl, cleanContentType)
        val safeHeadersJson = SafePlaybackHeaders.toJson(headers)
        val cleanQuality = quality?.trim()?.takeIf { it.isNotEmpty() }
        val cleanCodec = codec?.trim()?.takeIf { it.isNotEmpty() }
        val cleanLanguage = audioLanguage?.trim()?.takeIf { it.isNotEmpty() }

        val lockKey = digestKey(normAnime, normEpisode, normSource)
        val recordId = deterministicRecordId(normAnime, normEpisode, normSource)
        return withWriteLock(lockKey) {
            val digest = computeMetadataDigest(
                animeId = normAnime,
                episodeId = normEpisode,
                sourceId = normSource,
                streamUrl = normUrl,
                expiresAt = normalizedExpiry,
                safeHeadersJson = safeHeadersJson,
                contentType = cleanContentType,
                urlType = resolvedUrlType
            )
            if (!forceWrite && readDigest(lockKey) == digest && !isPersistedDigestStale(lockKey, nowMs)) {
                val remoteStillPresent = verifyRemoteRecordMatches(
                    recordId = recordId,
                    normAnime = normAnime,
                    normEpisode = normEpisode,
                    normSource = normSource,
                    normUrl = normUrl,
                    normalizedExpiry = normalizedExpiry,
                    normalizedSafeHeadersJson = safeHeadersJson,
                    normalizedContentType = cleanContentType,
                    nowMs = nowMs
                )
                if (remoteStillPresent) {
                    writtenDigests[lockKey] = digest
                    metricsTracker.recordD1WriteSkipped()
                    return@withWriteLock true
                }
                removeDigest(lockKey)
            }

            val record = D1StreamSourceRecordDto(
                sourceDocKey = recordId,
                scopeKey = normAnime,
                providerId = cleanProviderId,
                sourceId = normSource,
                sourceName = cleanSourceName,
                backendEpisodeId = normEpisode,
                streamUrl = normUrl,
                urlType = resolvedUrlType,
                contentType = cleanContentType,
                quality = cleanQuality,
                codec = cleanCodec,
                audioLanguage = cleanLanguage,
                downloadable = downloadable,
                sizeBytes = sizeBytes,
                safeHeadersJson = safeHeadersJson,
                expiresAt = normalizedExpiry,
                status = STATUS_AVAILABLE,
                version = 1L,
                lastVerifiedAt = nowMs,
                lastPlaybackSuccessAt = lastPlaybackSuccessAt,
                updatedAt = nowMs,
                deletedAt = null
            )
            val resp = executeWithRetry("upsertStreamSource", isWrite = true) {
                api.upsertStreamSource(record)
            }
            val responseBody = resp?.body()
            val savedRecord = responseBody?.data
            val tableMatches = responseBody?.table.isNullOrBlank() ||
                responseBody?.table.equals(CANONICAL_TABLE, ignoreCase = true)
            val savedExpiry = savedRecord?.expiresAt?.trim()?.takeIf { it.isNotEmpty() }
            val savedIdentityMatches = savedRecord != null &&
                savedRecord.sourceDocKey.trim() == recordId &&
                savedRecord.scopeKey.trim() == normAnime &&
                savedRecord.backendEpisodeId?.trim() == normEpisode &&
                savedRecord.sourceId.trim() == normSource &&
                savedRecord.streamUrl?.trim() == normUrl &&
                savedRecord.deletedAt == null &&
                savedExpiry == normalizedExpiry

            val succeeded =
                resp?.isSuccessful == true &&
                responseBody?.success == true &&
                tableMatches &&
                savedIdentityMatches

            if (succeeded) {
                saveDigest(lockKey, digest, nowMs)
                val headerNames = SafePlaybackHeaders.fromJson(safeHeadersJson)
                    .joinToString(",") { it[0] }.ifEmpty { "none" }
                PlaybackDiagnosticsLogger.log(
                    event = PlaybackDiagnosticEvent.D1_CACHE_WRITE,
                    animeId = normAnime,
                    episodeId = normEpisode,
                    sourceId = normSource,
                    providerLabel = cleanProviderId,
                    detail = "url_type=$resolvedUrlType headers=$headerNames has_expiry=${normalizedExpiry != null}"
                )
            } else {
                removeDigest(lockKey)
                Log.w(
                    TAG,
                    "D1 saveStreamUrl unconfirmed/failed: ${responseBody?.error ?: "http=${resp?.code()}, bodyPresent=${responseBody != null}, dataPresent=${savedRecord != null}"}"
                )
            }
            succeeded
        }
    }

    /**
     * Removes or invalidates a cached Stream URL entry in D1 `stream_sources`
     * (e.g., after playback failure on stale cached link).
     */
    suspend fun invalidateStreamUrl(
        animeId: String,
        episodeId: String,
        sourceId: String
    ): Boolean {
        val normAnime = animeId.trim()
        val normEpisode = episodeId.trim()
        val normSource = sourceId.trim()
        if (normAnime.isEmpty() || normEpisode.isEmpty() || normSource.isEmpty()) return false

        val lockKey = digestKey(normAnime, normEpisode, normSource)
        return withWriteLock(lockKey) {
            removeDigest(lockKey)
            val id = deterministicRecordId(normAnime, normEpisode, normSource)
            val resp = executeWithRetry("deleteStreamSource", isWrite = true) {
                api.deleteStreamSource(id)
            }
            val responseBody = resp?.body()
            val ok = resp != null &&
                ((resp.isSuccessful && responseBody?.success == true) || resp.code() == 404)
            if (ok) {
                PlaybackDiagnosticsLogger.log(
                    event = PlaybackDiagnosticEvent.D1_CACHE_INVALIDATED,
                    animeId = normAnime,
                    episodeId = normEpisode,
                    sourceId = normSource
                )
            }
            ok
        }
    }

    fun clearDigestCache() {
        writtenDigests.clear()
        digestStore.clear()
    }

    private fun digestKey(animeId: String, episodeId: String, sourceId: String): String =
        "ss:$animeId|$episodeId|$sourceId"

    private fun computeMetadataDigest(
        animeId: String,
        episodeId: String,
        sourceId: String,
        streamUrl: String,
        expiresAt: String?,
        safeHeadersJson: String?,
        contentType: String?,
        urlType: String
    ): String = sha256Hex(
        listOf(
            animeId,
            episodeId,
            sourceId,
            streamUrl,
            expiresAt.orEmpty(),
            safeHeadersJson.orEmpty(),
            contentType.orEmpty(),
            urlType
        ).joinToString("\n")
    )

    private fun buildWhereJson(vararg fields: Pair<String, String>): String {
        val json = org.json.JSONObject()
        fields.forEach { (k, v) -> json.put(k, v) }
        return json.toString()
    }

    companion object {
        private const val TAG = "CloudflareD1"
        const val CANONICAL_TABLE = "stream_sources"
        private const val DEFAULT_PROVIDER_ID = "direct"
        private const val STATUS_AVAILABLE = "AVAILABLE"
        private const val MAX_RETRIES = 2
        private const val BASE_RETRY_DELAY_MS = 180L
        private const val PERSISTED_DIGEST_MAX_AGE_MS = 10 * 60 * 1000L

        fun resolveEffectiveUrlType(explicitUrlType: String?, url: String, contentType: String? = null): String {
            val cleanExplicit = explicitUrlType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (cleanExplicit != null && cleanExplicit != "direct") return cleanExplicit
            return inferUrlType(url, contentType)
        }

        fun inferUrlType(url: String, contentType: String? = null): String {
            val ct = contentType?.lowercase().orEmpty()
            if (ct.contains("mpegurl") || ct.contains("m3u8")) return "hls"
            if (ct.contains("dash") || ct.contains("mpd")) return "dash"

            val clean = url.substringBefore('?').lowercase()
            return when {
                clean.endsWith(".m3u8") -> "hls"
                clean.endsWith(".mpd") -> "dash"
                else -> {
                    val dispositionFilename = extractResponseDispositionFilename(url)?.lowercase().orEmpty()
                    when {
                        dispositionFilename.endsWith(".m3u8") -> "hls"
                        dispositionFilename.endsWith(".mpd") -> "dash"
                        else -> "direct"
                    }
                }
            }
        }

        /**
         * Extracts the filename from signed storage query parameters such as AWS S3 / Cloudflare R2
         * `response-content-disposition=attachment; filename="Episode_01.mkv"`.
         * Never guesses from generic arbitrary query params like `?file=...`.
         */
        fun extractResponseDispositionFilename(url: String): String? {
            val query = runCatching { java.net.URI(url).rawQuery }.getOrNull()
                ?: url.substringAfter('?', "").substringBefore('#').takeIf { it.isNotEmpty() }
                ?: return null
            for (pair in query.split('&')) {
                val eqIdx = pair.indexOf('=')
                if (eqIdx <= 0) continue
                val key = runCatching { java.net.URLDecoder.decode(pair.substring(0, eqIdx), "UTF-8") }
                    .getOrDefault(pair.substring(0, eqIdx)).trim().lowercase()
                if (key != "response-content-disposition") continue
                val rawValue = runCatching { java.net.URLDecoder.decode(pair.substring(eqIdx + 1), "UTF-8") }
                    .getOrDefault(pair.substring(eqIdx + 1))
                val filenameMatch = Regex("""(?i)filename\*?=(?:UTF-8'')?\"?([^;\"]+)\"?""").find(rawValue)
                val name = filenameMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                if (name != null) return name
            }
            return null
        }

        fun deterministicRecordId(animeId: String, episodeId: String, sourceId: String): String {
            val raw = "${animeId.trim()}\n${episodeId.trim()}\n${sourceId.trim()}"
            return "su_" + sha256Hex(raw).take(32)
        }

        private fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
