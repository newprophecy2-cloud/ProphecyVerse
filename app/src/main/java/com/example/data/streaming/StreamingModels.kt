package com.example.data.streaming

import android.os.SystemClock
import android.util.Log
import com.example.data.local.AnimeEntry
import com.example.data.remote.DirectPlayData
import com.example.data.remote.ProphecySourceDto
import org.json.JSONArray
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

enum class DiscoveryStatus {
    NOT_DISCOVERED,
    IN_PROGRESS,
    COMPLETED,
    PARTIAL,
    TEMPORARY_FAILURE,
    PERMANENTLY_UNAVAILABLE
}

enum class StreamRecordStatus {
    AVAILABLE,
    MISSING_URL,
    EXPIRED,
    FAILED,
    TEMPORARILY_UNAVAILABLE,
    REMOVED
}

data class CanonicalStreamIdentity(
    val canonicalTitle: String,
    val displayTitle: String,
    val anilistAnimeId: Int?,
    val entryAniListId: Int?,
    val contentKey: String,
    val entryAnimeId: String,
    val backendAnimeId: String?,
    val isMovie: Boolean,
    val seasonId: String?,
    val seasonNumber: Int,
    val episodeId: String?,
    val episodeNumber: Int,
    val cumulativeEpisodeOffset: Int
)

enum class FirestoreWriteOperation {
    ADD,
    UPDATE,
    REMOVE
}

data class FirestoreWriteAuditEntry(
    val operation: FirestoreWriteOperation,
    val contentType: String, // "ANIME" or "MOVIE"
    val canonicalTitle: String?,
    val anilistId: String,
    val backendId: String?,
    val seasonId: String?,
    val episodeId: String?,
    val providerId: String?,
    val sourceId: String?,
    val targetPath: String,
    val startedAtMs: Long,
    val completedAtMs: Long,
    val success: Boolean,
    val failureReason: String? = null,
    val firebaseErrorCode: String? = null
)

object ContentIdentityHelper {
    private val safeDocChars = Regex("[^a-zA-Z0-9._-]")

    fun isMovie(entry: AnimeEntry?): Boolean {
        if (entry == null) return false
        return entry.mediaCategory.equals("MOVIE", ignoreCase = true) ||
            entry.format.equals("MOVIE", ignoreCase = true) ||
            entry.format.equals("FILM", ignoreCase = true)
    }

    /**
     * Produces the stable Firestore/Room streaming container ID:
     * uses canonical prequel AniList ID for multi-season TV franchises when present,
     * else own AniList numeric ID so shared documents are deterministic across devices.
     */
    fun canonicalContentKey(animeId: String, entry: AnimeEntry? = null): String {
        val isMovieEntry = isMovie(entry)
        if (!isMovieEntry) {
            entry?.canonicalAnimeId?.takeIf { it > 0 }?.let { return it.toString() }
        }
        entry?.aniListId?.takeIf { it > 0 }?.let { return it.toString() }
        if (animeId.startsWith("anilist_")) {
            val numeric = animeId.removePrefix("anilist_").trim()
            if (numeric.toIntOrNull() != null) return numeric
        }
        return sanitizeDocId(animeId)
    }

    fun canonicalTitle(entry: AnimeEntry?, fallback: String = ""): String =
        entry?.canonicalTitle?.takeIf { it.isNotBlank() }
            ?: entry?.preferredTitle?.takeIf { it.isNotBlank() }
            ?: entry?.titleEnglish?.takeIf { it.isNotBlank() }
            ?: entry?.titleRomaji?.takeIf { it.isNotBlank() }
            ?: fallback

    fun effectiveSeasonNumber(entry: AnimeEntry?, requestedSeasonNumber: Int, isMovie: Boolean = isMovie(entry)): Int {
        if (isMovie) return 0
        val canonSeason = entry?.canonicalSeasonNumber?.takeIf { it > 1 }
        return when {
            requestedSeasonNumber > 1 -> requestedSeasonNumber
            canonSeason != null -> canonSeason
            else -> requestedSeasonNumber.coerceAtLeast(1)
        }
    }

    fun resolveCanonicalIdentity(
        animeId: String,
        entry: AnimeEntry?,
        requestedSeasonNumber: Int = 1,
        requestedEpisodeNumber: Int = 1
    ): CanonicalStreamIdentity {
        val isMovieEntry = isMovie(entry)
        val contentKey = canonicalContentKey(animeId, entry)
        val anilistId = if (!isMovieEntry) {
            entry?.canonicalAnimeId?.takeIf { it > 0 }
                ?: entry?.aniListId?.takeIf { it > 0 }
                ?: contentKey.toIntOrNull()
        } else {
            entry?.aniListId?.takeIf { it > 0 } ?: contentKey.toIntOrNull()
        }
        val seasonNum = effectiveSeasonNumber(entry, requestedSeasonNumber, isMovieEntry)
        val epNum = if (isMovieEntry) 1 else requestedEpisodeNumber.coerceAtLeast(1)
        val canonTitle = canonicalTitle(entry, fallback = contentKey)
        val dispTitle = entry?.preferredTitle?.takeIf { it.isNotBlank() } ?: canonTitle
        return CanonicalStreamIdentity(
            canonicalTitle = canonTitle,
            displayTitle = dispTitle,
            anilistAnimeId = anilistId,
            entryAniListId = entry?.aniListId,
            contentKey = contentKey,
            entryAnimeId = entry?.id ?: animeId,
            backendAnimeId = entry?.backendAnimeId,
            isMovie = isMovieEntry,
            seasonId = if (isMovieEntry) null else seasonId(seasonNum),
            seasonNumber = seasonNum,
            episodeId = if (isMovieEntry) null else episodeId(epNum),
            episodeNumber = epNum,
            cumulativeEpisodeOffset = if (isMovieEntry) 0 else (entry?.cumulativeEpisodeOffset ?: 0).coerceAtLeast(0)
        )
    }

    fun containerScopeKey(contentKey: String, isMovie: Boolean): String =
        if (isMovie) "movie:$contentKey" else "anime:$contentKey"

    fun animeEpisodeScopeKey(contentKey: String, seasonNumber: Int, episodeNumber: Int): String =
        "anime:$contentKey:s$seasonNumber:e$episodeNumber"

    fun movieScopeKey(contentKey: String): String = "movie:$contentKey"

    fun scopeKey(contentKey: String, isMovie: Boolean, seasonNumber: Int = 1, episodeNumber: Int = 1): String =
        if (isMovie) movieScopeKey(contentKey) else animeEpisodeScopeKey(contentKey, seasonNumber, episodeNumber)

    fun seasonId(seasonNumber: Int): String = "season_$seasonNumber"

    fun episodeId(episodeNumber: Int): String = "ep_$episodeNumber"

    fun sourceDocId(sourceId: String): String = sanitizeDocId(sourceId)

    /**
     * Deterministic provider document ID derived from normalized provider label hash,
     * never using raw display titles directly as Firestore document IDs.
     */
    fun deterministicProviderId(providerLabel: String?): String {
        val normalized = providerLabel?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalized.isEmpty() || normalized == "unknown provider") return "prov_unknown"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)
        return "prov_$digest"
    }

    fun sanitizeDocId(raw: String): String {
        val cleaned = raw.trim().replace(safeDocChars, "_")
        return if (cleaned.isNotEmpty()) cleaned.take(120) else "unknown"
    }

    fun resolveLockKey(contentKey: String, isMovie: Boolean, providerId: String, sourceId: String, seasonNumber: Int = 0, episodeNumber: Int = 0): String {
        val scope = if (isMovie) "movie_$contentKey" else "anime_${contentKey}_s${seasonNumber}_e${episodeNumber}"
        return sanitizeDocId("${scope}_${providerId}_${sourceId}")
    }
}

object SafePlaybackHeaders {
    private val allowedSharedHeaders = setOf(
        "user-agent",
        "referer",
        "origin",
        "accept",
        "accept-language",
        "range"
    )

    private val sensitiveFragments = listOf(
        "auth", "cookie", "token", "signature", "secret",
        "credential", "password", "key", "session"
    )

    fun isSafeSharedHeader(name: String): Boolean {
        val lower = name.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) return false
        if (sensitiveFragments.any { lower.contains(it) }) return false
        return lower in allowedSharedHeaders
    }

    fun sanitize(headers: List<List<String>>): List<List<String>> {
        if (headers.isEmpty()) return emptyList()
        val dedup = LinkedHashMap<String, String>()
        for (pair in headers) {
            if (pair.size < 2) continue
            val key = pair[0].trim()
            val value = pair[1].replace("\r", "").replace("\n", "").trim()
            if (key.isEmpty() || value.isEmpty()) continue
            if (!isSafeSharedHeader(key)) continue
            dedup[key] = value
        }
        return dedup.entries.map { listOf(it.key, it.value) }
    }

    fun toSafeMap(headers: List<List<String>>): Map<String, String> =
        sanitize(headers).associate { it[0] to it[1] }

    fun fromSafeMap(map: Map<*, *>?): List<List<String>> {
        if (map.isNullOrEmpty()) return emptyList()
        val rawPairs = map.entries.mapNotNull { (k, v) ->
            val key = k?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val value = v?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            listOf(key, value)
        }
        return sanitize(rawPairs)
    }

    fun toJson(headers: List<List<String>>): String? {
        val safe = sanitize(headers)
        if (safe.isEmpty()) return null
        val root = JSONArray()
        safe.forEach { pair ->
            root.put(JSONArray().apply {
                put(pair[0])
                put(pair[1])
            })
        }
        return root.toString()
    }

    fun fromJson(json: String?): List<List<String>> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val raw = mutableListOf<List<String>>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONArray(i) ?: continue
                if (item.length() >= 2) {
                    raw += listOf(item.optString(0), item.optString(1))
                }
            }
            sanitize(raw)
        }.getOrDefault(emptyList())
    }

    fun isValidStreamUrl(url: String?): Boolean {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> !uri.host.isNullOrBlank()
            "file" -> !uri.path.isNullOrBlank()
            else -> false
        }
    }
}

data class ResolvedStreamPlayback(
    val source: ProphecySourceDto,
    val directData: DirectPlayData,
    val candidates: List<ProphecySourceDto>,
    val fromCache: Boolean,
    val cacheTier: String // "ROOM", "FIRESTORE", "BACKEND_RESOLVE"
)

data class TimingSummary(
    val metric: String,
    val count: Int,
    val minMs: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val maxMs: Long
)

object MonotonicMetrics {
    private const val TAG = "ProphecyMetrics"
    private const val MAX_SAMPLES = 200
    private val samples = ConcurrentHashMap<String, CopyOnWriteArrayList<Long>>()

    fun nowMs(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        System.nanoTime() / 1_000_000L
    }

    fun recordDuration(metric: String, startedAtMs: Long, metadata: String = ""): Long {
        val elapsed = (nowMs() - startedAtMs).coerceAtLeast(0L)
        recordValue(metric, elapsed)
        runCatching {
            if (metadata.isBlank()) {
                Log.i(TAG, "$metric duration_ms=$elapsed")
            } else {
                Log.i(TAG, "$metric duration_ms=$elapsed $metadata")
            }
        }
        return elapsed
    }

    fun recordValue(metric: String, durationMs: Long) {
        val list = samples.computeIfAbsent(metric) { CopyOnWriteArrayList() }
        list.add(durationMs.coerceAtLeast(0L))
        while (list.size > MAX_SAMPLES) {
            runCatching { list.removeAt(0) }
        }
    }

    fun summary(metric: String): TimingSummary? {
        val values = samples[metric]?.sorted()?.takeIf { it.isNotEmpty() } ?: return null
        val p50Index = ((values.size - 1) * 0.50).toInt().coerceIn(0, values.lastIndex)
        val p95Index = ((values.size - 1) * 0.95).toInt().coerceIn(0, values.lastIndex)
        return TimingSummary(
            metric = metric,
            count = values.size,
            minMs = values.first(),
            p50Ms = values[p50Index],
            p95Ms = values[p95Index],
            maxMs = values.last()
        )
    }

    fun allSummaries(): List<TimingSummary> = samples.keys.sorted().mapNotNull { summary(it) }

    fun clear() {
        samples.clear()
    }
}
