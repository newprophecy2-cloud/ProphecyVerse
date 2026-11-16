package com.example.data.d1

import android.util.Log
import com.example.data.metrics.DatabaseMetricsTracker
import com.example.data.repository.ExpiryState
import com.example.data.repository.SourceExpiryTime
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
import org.json.JSONObject
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
 * Strictly stores and validates resolved Stream URLs only, keyed by (anime_id, episode_id, source_id),
 * storing expires_at only when actual expiry is known.
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
        val expiresAt: String? = null
    )

    private val singleFlightScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightReads = ConcurrentHashMap<String, CompletableDeferred<Any?>>()
    private val writtenDigests = ConcurrentHashMap<String, String>()
    private val writeMutexes = ConcurrentHashMap<String, Mutex>()

    fun attachDigestStore(store: D1DigestStore) {
        digestStore = store
    }

    private fun readDigest(key: String): String? =
        writtenDigests[key] ?: digestStore.get(key)?.also { writtenDigests[key] = it }

    private fun saveDigest(key: String, digest: String) {
        writtenDigests[key] = digest
        digestStore.put(key, digest)
    }

    private fun removeDigest(key: String) {
        writtenDigests.remove(key)
        digestStore.remove(key)
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
                if (!transient || attempt >= MAX_RETRIES) {
                    metricsTracker.recordD1Error(elapsed)
                    val errBody = runCatching { response.errorBody()?.string()?.take(180) }.getOrNull()
                    Log.w(TAG, "D1 $operationName failed with HTTP $code ${errBody.orEmpty()}".trim())
                    return response
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: IOException) {
                val elapsed = (MonotonicMetrics.nowMs() - startedAt).coerceAtLeast(0L)
                metricsTracker.recordApiRequest(false)
                if (attempt >= MAX_RETRIES) {
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

    suspend fun verifyHealth(): Boolean {
        val resp = executeWithRetry("verifyHealth") { api.listTables() } ?: return false
        return resp.isSuccessful && resp.body()?.success == true
    }

    /**
     * Looks up a cached Stream URL from D1 by exact (anime_id, episode_id, source_id).
     * Validates URL scheme/host and expiry; treats expired, invalid, or unavailable entries as misses.
     */
    suspend fun getValidStreamUrl(
        animeId: String,
        episodeId: String,
        sourceId: String,
        nowMs: Long = System.currentTimeMillis()
    ): CachedStreamUrl? {
        val normAnime = animeId.trim()
        val normEpisode = episodeId.trim()
        val normSource = sourceId.trim()
        if (normAnime.isEmpty() || normEpisode.isEmpty() || normSource.isEmpty()) return null

        val lockKey = digestKey(normAnime, normEpisode, normSource)
        val recordId = deterministicRecordId(normAnime, normEpisode, normSource)
        val flightKey = "stream_url:$normAnime|$normEpisode|$normSource"
        return coalesceRead(flightKey) {
            val resp = executeWithRetry("getStreamUrl") {
                api.getStreamUrl(recordId)
            }
            if (resp == null || !resp.isSuccessful) {
                return@coalesceRead null
            }
            val body = resp.body()
            if (body == null || !body.success) {
                Log.w(TAG, "D1 getStreamUrl unsuccessful response: ${body?.error ?: "empty body"}")
                return@coalesceRead null
            }
            val record = body.data ?: return@coalesceRead null

            if (record.animeId.trim() != normAnime ||
                record.episodeId.trim() != normEpisode ||
                record.sourceId.trim() != normSource
            ) {
                removeDigest(lockKey)
                return@coalesceRead null
            }
            val candidateUrl = record.streamUrl.trim()
            if (!SafePlaybackHeaders.isValidStreamUrl(candidateUrl)) {
                removeDigest(lockKey)
                return@coalesceRead null
            }
            val rawExpiry = record.expiresAt?.trim()?.takeIf { it.isNotEmpty() }
            when (SourceExpiryTime.evaluateExpiry(rawExpiry, nowMs)) {
                ExpiryState.EXPIRED, ExpiryState.INVALID -> {
                    removeDigest(lockKey)
                    null
                }
                ExpiryState.MISSING -> {
                    saveDigest(lockKey, sha256Hex("$normAnime\n$normEpisode\n$normSource\n$candidateUrl\n"))
                    CachedStreamUrl(
                        animeId = normAnime,
                        episodeId = normEpisode,
                        sourceId = normSource,
                        streamUrl = candidateUrl,
                        expiresAt = null
                    )
                }
                ExpiryState.VALID -> {
                    saveDigest(lockKey, sha256Hex("$normAnime\n$normEpisode\n$normSource\n$candidateUrl\n${rawExpiry.orEmpty()}"))
                    CachedStreamUrl(
                        animeId = normAnime,
                        episodeId = normEpisode,
                        sourceId = normSource,
                        streamUrl = candidateUrl,
                        expiresAt = rawExpiry
                    )
                }
            }
        }
    }

    /**
     * Saves a validated resolved Stream URL to D1 keyed by (anime_id, episode_id, source_id).
     * Stores expires_at ONLY when actual valid expiry is known; never stores invalid or already-expired URLs.
     */
    suspend fun saveStreamUrl(
        animeId: String,
        episodeId: String,
        sourceId: String,
        streamUrl: String,
        expiresAt: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val normAnime = animeId.trim()
        val normEpisode = episodeId.trim()
        val normSource = sourceId.trim()
        val normUrl = streamUrl.trim()
        if (normAnime.isEmpty() || normEpisode.isEmpty() || normSource.isEmpty()) return false
        if (!SafePlaybackHeaders.isValidStreamUrl(normUrl)) return false

        val normalizedExpiry: String? = expiresAt?.trim()?.takeIf { it.isNotEmpty() }?.let { rawExp ->
            when (SourceExpiryTime.evaluateExpiry(rawExp, nowMs)) {
                ExpiryState.VALID -> rawExp
                ExpiryState.EXPIRED -> return false
                ExpiryState.INVALID, ExpiryState.MISSING -> null
            }
        }

        val lockKey = digestKey(normAnime, normEpisode, normSource)
        return withWriteLock(lockKey) {
            val digest = sha256Hex("$normAnime\n$normEpisode\n$normSource\n$normUrl\n${normalizedExpiry.orEmpty()}")
            if (readDigest(lockKey) == digest) {
                metricsTracker.recordD1WriteSkipped()
                return@withWriteLock true
            }

            val record = D1StreamUrlRecordDto(
                id = deterministicRecordId(normAnime, normEpisode, normSource),
                animeId = normAnime,
                episodeId = normEpisode,
                sourceId = normSource,
                streamUrl = normUrl,
                expiresAt = normalizedExpiry
            )
            val resp = executeWithRetry("upsertStreamUrl", isWrite = true) {
                api.upsertStreamUrl(record)
            }
            val succeeded = resp != null && resp.isSuccessful && (resp.body()?.success != false)
            if (succeeded) {
                saveDigest(lockKey, digest)
            } else {
                Log.w(TAG, "D1 saveStreamUrl failed: ${resp?.body()?.error ?: "http=${resp?.code()}"}")
            }
            succeeded
        }
    }

    /**
     * Removes or invalidates a cached Stream URL entry in D1 (e.g., after playback failure on stale cached link).
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
            val resp = executeWithRetry("deleteStreamUrl", isWrite = true) {
                api.deleteStreamUrl(id)
            }
            resp != null && (resp.isSuccessful || resp.code() == 404)
        }
    }

    fun clearDigestCache() {
        writtenDigests.clear()
        digestStore.clear()
    }

    private fun digestKey(animeId: String, episodeId: String, sourceId: String): String =
        "su:$animeId|$episodeId|$sourceId"

    companion object {
        private const val TAG = "CloudflareD1"
        private const val MAX_RETRIES = 2
        private const val BASE_RETRY_DELAY_MS = 180L

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
