package com.example.data.cache

import com.example.data.local.AnimeEntry
import com.example.data.local.EpisodeEntry
import com.example.data.local.SeasonEntry
import com.example.data.metrics.DatabaseMetricsTracker
import com.example.data.remote.DirectPlayData
import com.example.data.repository.AnimeRepository
import com.example.data.repository.SourceExpiryTime
import com.example.data.streaming.SafePlaybackHeaders

/**
 * Bounded, thread-safe in-memory LRU cache layer with configurable per-domain TTLs.
 * Temporary playback credentials (URLs and headers) live only up to source expiry / max bounded TTL
 * and are never written permanently to Firestore or D1.
 */
class MemoryCacheManager(
    private val metricsTracker: DatabaseMetricsTracker = DatabaseMetricsTracker.getInstance(),
    val homeTtlMs: Long = 10 * 60 * 1000L,
    val searchTtlMs: Long = 5 * 60 * 1000L,
    val animeTtlMs: Long = 24 * 60 * 60 * 1000L,
    val seasonTtlMs: Long = 12 * 60 * 60 * 1000L,
    val episodeTtlMs: Long = 12 * 60 * 60 * 1000L,
    val streamMetadataTtlMs: Long = 5 * 60 * 1000L,
    val userProfileTtlMs: Long = 30 * 60 * 1000L,
    val playbackUrlMaxTtlMs: Long = 20 * 60 * 1000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    data class CacheEntry<T>(
        val value: T,
        val cachedAtMs: Long,
        val expiresAtMs: Long,
        val version: Long = 1L
    ) {
        fun isExpired(nowMs: Long): Boolean = nowMs > expiresAtMs
    }

    data class SearchPageCacheValue(
        val results: List<AnimeEntry>,
        val currentPage: Int,
        val hasNextPage: Boolean,
        val nextCursor: String? = null
    )

    private class BoundedLruMap<V>(private val maxEntries: Int) {
        private val map = object : LinkedHashMap<String, CacheEntry<V>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry<V>>?): Boolean {
                return size > maxEntries
            }
        }
        private val lock = Any()

        fun get(key: String, nowMs: Long): CacheEntry<V>? = synchronized(lock) {
            val entry = map[key] ?: return null
            if (entry.isExpired(nowMs)) {
                map.remove(key)
                return null
            }
            entry
        }

        fun put(key: String, value: V, ttlMs: Long, nowMs: Long, version: Long = 1L) = synchronized(lock) {
            map[key] = CacheEntry(
                value = value,
                cachedAtMs = nowMs,
                expiresAtMs = nowMs + ttlMs.coerceAtLeast(0L),
                version = version
            )
        }

        fun remove(key: String) = synchronized(lock) {
            map.remove(key)
            Unit
        }

        fun removeWhere(predicate: (String) -> Boolean) = synchronized(lock) {
            val it = map.keys.iterator()
            while (it.hasNext()) {
                if (predicate(it.next())) it.remove()
            }
        }

        fun findValidEntries(nowMs: Long, predicate: (String) -> Boolean): List<Pair<String, CacheEntry<V>>> = synchronized(lock) {
            val expiredKeys = mutableListOf<String>()
            val matches = mutableListOf<Pair<String, CacheEntry<V>>>()
            for ((k, entry) in map) {
                if (predicate(k)) {
                    if (entry.isExpired(nowMs)) {
                        expiredKeys += k
                    } else {
                        matches += k to entry
                    }
                }
            }
            expiredKeys.forEach { map.remove(it) }
            matches
        }

        fun evictExpired(nowMs: Long): Int = synchronized(lock) {
            var evicted = 0
            val it = map.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.isExpired(nowMs)) {
                    it.remove()
                    evicted++
                }
            }
            evicted
        }

        fun clear() = synchronized(lock) {
            map.clear()
        }

        fun size(): Int = synchronized(lock) { map.size }
    }

    private val homeFeedCache = BoundedLruMap<List<AnimeEntry>>(maxEntries = 16)
    private val searchPageCache = BoundedLruMap<SearchPageCacheValue>(maxEntries = 64)
    private val animeCache = BoundedLruMap<AnimeEntry>(maxEntries = 200)
    private val seasonCache = BoundedLruMap<List<SeasonEntry>>(maxEntries = 120)
    private val episodePageCache = BoundedLruMap<List<EpisodeEntry>>(maxEntries = 160)
    private val streamSnapshotCache = BoundedLruMap<AnimeRepository.EpisodeSourceSnapshot>(maxEntries = 120)
    private val playbackCache = BoundedLruMap<DirectPlayData>(maxEntries = 60)
    private val profileCache = BoundedLruMap<Any>(maxEntries = 8)

    fun getHomeSection(sectionKey: String): List<AnimeEntry>? {
        val hit = homeFeedCache.get(sectionKey, clockMs())
        if (hit != null) {
            metricsTracker.recordMemoryHit()
            return hit.value
        }
        metricsTracker.recordMemoryMiss()
        return null
    }

    fun isHomeFresh(sectionKey: String = "home_bundle"): Boolean =
        homeFeedCache.get(sectionKey, clockMs()) != null

    fun putHomeSection(sectionKey: String, items: List<AnimeEntry>, ttlMs: Long = homeTtlMs) {
        homeFeedCache.put(sectionKey, items, ttlMs, clockMs())
    }

    fun invalidateHome() {
        homeFeedCache.clear()
    }

    fun searchKey(normalizedQuery: String, page: Int): String =
        "${normalizedQuery.trim()}::page=$page"

    fun getSearchPage(normalizedQuery: String, page: Int): SearchPageCacheValue? {
        val hit = searchPageCache.get(searchKey(normalizedQuery, page), clockMs())
        if (hit != null) {
            metricsTracker.recordMemoryHit()
            return hit.value
        }
        metricsTracker.recordMemoryMiss()
        return null
    }

    fun putSearchPage(normalizedQuery: String, page: Int, value: SearchPageCacheValue, ttlMs: Long = searchTtlMs) {
        searchPageCache.put(searchKey(normalizedQuery, page), value, ttlMs, clockMs())
    }

    fun invalidateSearch(normalizedQuery: String? = null) {
        if (normalizedQuery == null) {
            searchPageCache.clear()
        } else {
            val prefix = "${normalizedQuery.trim()}::page="
            searchPageCache.removeWhere { it.startsWith(prefix) }
        }
    }

    fun getAnime(animeId: String): AnimeEntry? {
        val hit = animeCache.get(animeId, clockMs())
        if (hit != null) {
            metricsTracker.recordMemoryHit()
            return hit.value
        }
        metricsTracker.recordMemoryMiss()
        return null
    }

    fun putAnime(entry: AnimeEntry, ttlMs: Long = animeTtlMs) {
        val now = clockMs()
        animeCache.put(entry.id, entry, ttlMs, now)
        entry.aniListId?.takeIf { it > 0 }?.let { id ->
            val syntheticKey = "anilist_$id"
            if (syntheticKey != entry.id) {
                animeCache.put(syntheticKey, entry, ttlMs, now)
            }
        }
    }

    fun invalidateAnime(animeId: String) {
        animeCache.remove(animeId)
    }

    fun getSeasons(animeId: String): List<SeasonEntry>? {
        val hit = seasonCache.get(animeId, clockMs())
        if (hit != null) {
            metricsTracker.recordMemoryHit()
            return hit.value
        }
        metricsTracker.recordMemoryMiss()
        return null
    }

    fun putSeasons(animeId: String, seasons: List<SeasonEntry>, ttlMs: Long = seasonTtlMs) {
        seasonCache.put(animeId, seasons, ttlMs, clockMs())
    }

    fun episodePageKey(animeId: String, seasonNumber: Int, page: Int): String =
        "$animeId::s=$seasonNumber::p=$page"

    fun getEpisodesPage(animeId: String, seasonNumber: Int, page: Int): List<EpisodeEntry>? {
        val hit = episodePageCache.get(episodePageKey(animeId, seasonNumber, page), clockMs())
        if (hit != null) {
            metricsTracker.recordMemoryHit()
            return hit.value
        }
        metricsTracker.recordMemoryMiss()
        return null
    }

    fun putEpisodesPage(animeId: String, seasonNumber: Int, page: Int, episodes: List<EpisodeEntry>, ttlMs: Long = episodeTtlMs) {
        episodePageCache.put(episodePageKey(animeId, seasonNumber, page), episodes, ttlMs, clockMs())
    }

    fun invalidateEpisodes(animeId: String, seasonNumber: Int? = null) {
        seasonCache.remove(animeId)
        val prefix = if (seasonNumber != null) "$animeId::s=$seasonNumber::" else "$animeId::s="
        episodePageCache.removeWhere { it.startsWith(prefix) }
    }

    fun getStreamSnapshot(cacheKey: String): AnimeRepository.EpisodeSourceSnapshot? {
        val hit = streamSnapshotCache.get(cacheKey, clockMs())
        if (hit != null) {
            metricsTracker.recordMemoryHit()
            return hit.value
        }
        metricsTracker.recordMemoryMiss()
        return null
    }

    fun putStreamSnapshot(cacheKey: String, snapshot: AnimeRepository.EpisodeSourceSnapshot, ttlMs: Long = streamMetadataTtlMs) {
        streamSnapshotCache.put(cacheKey, snapshot, ttlMs, clockMs())
    }

    fun invalidateStreamScope(cacheKey: String) {
        streamSnapshotCache.remove(cacheKey)
    }

    fun playbackCacheKey(
        sourceId: String,
        scopeKey: String? = null,
        providerId: String? = null,
        backendEpisodeId: String? = null
    ): String {
        val normSource = sourceId.trim()
        val normScope = scopeKey?.trim()?.takeIf { it.isNotEmpty() }
        val normProvider = providerId?.trim()?.takeIf { it.isNotEmpty() }
        val normEp = backendEpisodeId?.trim()?.takeIf { it.isNotEmpty() }
        val epSegment = if (normEp != null) "|ep|$normEp" else ""
        return when {
            normScope != null && normProvider != null -> "$normScope|p|$normProvider$epSegment::$normSource"
            normScope != null -> "$normScope$epSegment::$normSource"
            normProvider != null -> "p|$normProvider$epSegment::$normSource"
            normEp != null -> "ep|$normEp::$normSource"
            else -> normSource
        }
    }

    fun getResolvedPlayback(
        sourceId: String,
        scopeKey: String? = null,
        providerId: String? = null,
        backendEpisodeId: String? = null
    ): DirectPlayData? {
        val normSource = sourceId.trim()
        if (normSource.isEmpty()) {
            metricsTracker.recordMemoryMiss()
            return null
        }
        val normScope = scopeKey?.trim()?.takeIf { it.isNotEmpty() }
        val normProvider = providerId?.trim()?.takeIf { it.isNotEmpty() }
        val normEp = backendEpisodeId?.trim()?.takeIf { it.isNotEmpty() }
        val now = clockMs()
        playbackCache.evictExpired(now)

        val key = playbackCacheKey(normSource, normScope, normProvider, normEp)
        val exactHit = playbackCache.get(key, now)
        if (exactHit != null) {
            val valData = exactHit.value
            if (valData.sourceId.trim() != normSource ||
                !SafePlaybackHeaders.isValidStreamUrl(valData.playbackUrl) ||
                SourceExpiryTime.isExpiredOrInvalid(valData.expiresAt, now)
            ) {
                playbackCache.remove(key)
                metricsTracker.recordMemoryMiss()
                return null
            }
            metricsTracker.recordMemoryHit()
            return if (valData.sourceId == normSource) valData else valData.copy(sourceId = normSource)
        }
        // Compatibility: if caller pinned backendEpisodeId and an older caller stored the same
        // (scopeKey, providerId, sourceId) without |ep|, reuse it ONLY when both scopeKey & providerId match
        // (scopeKey already isolates canonical episode) and never match a different |ep| entry.
        if (normEp != null && normScope != null && normProvider != null) {
            val legacyKey = playbackCacheKey(normSource, normScope, normProvider, null)
            val legacyHit = playbackCache.get(legacyKey, now)
            if (legacyHit != null) {
                val valData = legacyHit.value
                if (valData.sourceId.trim() != normSource ||
                    !SafePlaybackHeaders.isValidStreamUrl(valData.playbackUrl) ||
                    SourceExpiryTime.isExpiredOrInvalid(valData.expiresAt, now)
                ) {
                    playbackCache.remove(legacyKey)
                } else {
                    metricsTracker.recordMemoryHit()
                    return if (valData.sourceId == normSource) valData else valData.copy(sourceId = normSource)
                }
            }
        }
        // Only allow unambiguous fallback when caller omitted providerId (and did not pin a mismatched episodeId)
        if (normScope != null && normProvider == null && normEp == null) {
            val prefix = "$normScope|p|"
            val suffix = "::$normSource"
            val candidates = playbackCache.findValidEntries(now) { k ->
                k.startsWith(prefix) && k.endsWith(suffix)
            }.filter { (k, entry) ->
                val v = entry.value
                val valid = v.sourceId.trim() == normSource &&
                    SafePlaybackHeaders.isValidStreamUrl(v.playbackUrl) &&
                    !SourceExpiryTime.isExpiredOrInvalid(v.expiresAt, now)
                if (!valid) playbackCache.remove(k)
                valid
            }
            if (candidates.size == 1) {
                val chosen = candidates.single().second.value
                metricsTracker.recordMemoryHit()
                return if (chosen.sourceId == normSource) chosen else chosen.copy(sourceId = normSource)
            }
        }
        metricsTracker.recordMemoryMiss()
        return null
    }

    fun putResolvedPlayback(
        sourceId: String,
        data: DirectPlayData,
        scopeKey: String? = null,
        providerId: String? = null,
        backendEpisodeId: String? = null
    ) {
        val normSource = sourceId.trim()
        val normDataId = data.sourceId.trim()
        if (normSource.isEmpty() || normDataId != normSource) return
        if (!SafePlaybackHeaders.isValidStreamUrl(data.playbackUrl)) return
        val now = clockMs()
        if (SourceExpiryTime.isExpiredOrInvalid(data.expiresAt, now)) return
        playbackCache.evictExpired(now)
        val parsedExpiryMs = SourceExpiryTime.parseEpochMillis(data.expiresAt)
        val remainingToExpiry = if (parsedExpiryMs != null) (parsedExpiryMs - now).coerceAtLeast(0L) else playbackUrlMaxTtlMs
        val effectiveTtl = minOf(playbackUrlMaxTtlMs, remainingToExpiry)
        if (effectiveTtl > 0L) {
            val normalizedData = if (data.sourceId == normSource) data else data.copy(sourceId = normSource)
            playbackCache.put(playbackCacheKey(normSource, scopeKey, providerId, backendEpisodeId), normalizedData, effectiveTtl, now)
        }
    }

    fun invalidatePlayback(
        sourceId: String,
        scopeKey: String? = null,
        providerId: String? = null,
        backendEpisodeId: String? = null
    ) {
        val normSource = sourceId.trim()
        if (normSource.isEmpty()) return
        val normScope = scopeKey?.trim()?.takeIf { it.isNotEmpty() }
        val normProvider = providerId?.trim()?.takeIf { it.isNotEmpty() }
        val normEp = backendEpisodeId?.trim()?.takeIf { it.isNotEmpty() }
        if (normEp != null) {
            playbackCache.remove(playbackCacheKey(normSource, normScope, normProvider, normEp))
            return
        }
        when {
            normScope != null && normProvider != null -> {
                val basePrefix = "$normScope|p|$normProvider"
                val suffix = "::$normSource"
                playbackCache.remove(playbackCacheKey(normSource, normScope, normProvider, null))
                playbackCache.removeWhere { k ->
                    k == "$basePrefix$suffix" || (k.startsWith("$basePrefix|ep|") && k.endsWith(suffix))
                }
            }
            normScope != null -> {
                playbackCache.remove(playbackCacheKey(normSource, normScope, null, null))
                val scopeP = "$normScope|p|"
                val scopeEp = "$normScope|ep|"
                val suffix = "::$normSource"
                playbackCache.removeWhere { k ->
                    (k.startsWith(scopeP) || k.startsWith(scopeEp)) && k.endsWith(suffix)
                }
            }
            normProvider != null -> {
                val markerNoEp = "|p|$normProvider::$normSource"
                val markerWithEp = "|p|$normProvider|ep|"
                val suffix = "::$normSource"
                playbackCache.remove("p|$normProvider::$normSource")
                playbackCache.removeWhere { k ->
                    k.endsWith(markerNoEp) ||
                        ((k.startsWith("p|$normProvider|ep|") || k.contains(markerWithEp)) && k.endsWith(suffix))
                }
            }
            else -> {
                playbackCache.remove(normSource)
                playbackCache.removeWhere { it == normSource || it.endsWith("::$normSource") }
            }
        }
    }

    fun invalidatePlaybackForScopePrefix(scopeOrContentPrefix: String) {
        val norm = scopeOrContentPrefix.trim()
        if (norm.isEmpty()) return
        playbackCache.removeWhere { key ->
            key.startsWith("$norm:") || key.startsWith("$norm|") || key.startsWith("anime:$norm:")
        }
    }

    fun evictExpiredPlaybackEntries(): Int = playbackCache.evictExpired(clockMs())

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getProfileValue(key: String): T? {
        val hit = profileCache.get(key, clockMs()) ?: run {
            metricsTracker.recordMemoryMiss()
            return null
        }
        metricsTracker.recordMemoryHit()
        return hit.value as? T
    }

    fun <T : Any> putProfileValue(key: String, value: T, ttlMs: Long = userProfileTtlMs) {
        profileCache.put(key, value, ttlMs, clockMs())
    }

    fun invalidateProfile() {
        profileCache.clear()
    }

    fun clearCatalogAndSearchCaches() {
        homeFeedCache.clear()
        searchPageCache.clear()
        animeCache.clear()
        seasonCache.clear()
        episodePageCache.clear()
        streamSnapshotCache.clear()
    }

    fun clearAll() {
        clearCatalogAndSearchCaches()
        playbackCache.clear()
        profileCache.clear()
    }

    fun snapshotCounts(): Map<String, Int> = mapOf(
        "home" to homeFeedCache.size(),
        "search" to searchPageCache.size(),
        "anime" to animeCache.size(),
        "seasons" to seasonCache.size(),
        "episodes" to episodePageCache.size(),
        "stream_scopes" to streamSnapshotCache.size(),
        "playback_memory" to playbackCache.size(),
        "profile" to profileCache.size()
    )

    companion object {
        @Volatile
        private var INSTANCE: MemoryCacheManager? = null

        fun getInstance(): MemoryCacheManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryCacheManager().also { INSTANCE = it }
            }
        }
    }
}
