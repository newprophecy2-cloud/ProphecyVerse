package com.example.data.d1

import android.util.Log
import com.example.data.local.AnimeAlias
import com.example.data.local.AnimeEntry
import com.example.data.local.CatalogMapping
import com.example.data.local.EpisodeEntry
import com.example.data.local.SeasonEntry
import com.example.data.local.StreamContainerEntity
import com.example.data.local.StreamEpisodeEntity
import com.example.data.local.StreamProviderEntity
import com.example.data.local.StreamSeasonEntity
import com.example.data.local.StreamSourceEntity
import com.example.data.metrics.DatabaseMetricsTracker
import com.example.data.streaming.ContentIdentityHelper
import com.example.data.streaming.DiscoveryStatus
import com.example.data.streaming.MonotonicMetrics
import com.example.data.streaming.StreamRecordStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Cloudflare D1 REST Data Source backed by the Prophecy-verse-DB Worker API.
 * Implements strict page-based indexed reads, single-flight coalescing, bounded retries
 * with exponential backoff + jitter, duplicate-alias prevention, and diff-checked writes.
 */
class CloudflareD1DataSource(
    private val api: CloudflareD1ApiService,
    private val metricsTracker: DatabaseMetricsTracker = DatabaseMetricsTracker.getInstance()
) {
    data class D1ScopedStreamingBundle(
        val container: StreamContainerEntity?,
        val season: StreamSeasonEntity?,
        val episode: StreamEpisodeEntity?,
        val providers: List<StreamProviderEntity>,
        val sources: List<StreamSourceEntity>
    )

    private val singleFlightScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightReads = ConcurrentHashMap<String, CompletableDeferred<Any?>>()
    private val writtenDigests = ConcurrentHashMap<String, String>()
    private val knownAliasesByAnime = ConcurrentHashMap<String, MutableSet<String>>()
    private val lastSuccessReportAt = ConcurrentHashMap<String, Long>()

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
                    Log.w(TAG, "D1 $operationName failed with HTTP $code")
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
        val response = executeWithRetry("list_tables") { api.listTables() } ?: return false
        return response.isSuccessful && response.body()?.success == true
    }

    suspend fun fetchHomeSection(
        sectionColumn: String,
        limit: Int = 25,
        offset: Int = 0
    ): List<D1AnimeEntryDto> {
        val boundedLimit = limit.coerceIn(1, 100)
        val safeOffset = offset.coerceAtLeast(0)
        val whereJson = when (sectionColumn) {
            "isTrending" -> buildWhereJson("isTrending" to 1)
            "isSeasonal" -> buildWhereJson("isSeasonal" to 1)
            "isPopular" -> buildWhereJson("isPopular" to 1)
            "MOVIE" -> buildWhereJson("mediaCategory" to "MOVIE")
            else -> return emptyList()
        }
        return coalesceRead("home:$sectionColumn:$boundedLimit:$safeOffset") {
            val response = executeWithRetry("home_$sectionColumn") {
                api.listAnimeEntries(
                    where = whereJson,
                    sort = "updatedAt",
                    order = "DESC",
                    limit = boundedLimit,
                    offset = safeOffset
                )
            }
            val list = response?.takeIf { it.isSuccessful }?.body()?.data.orEmpty()
            list.forEach { rememberAnimeDigest(it) }
            list
        }
    }

    suspend fun fetchAnimeById(animeId: String): D1AnimeEntryDto? {
        if (animeId.isBlank()) return null
        return coalesceRead("anime:$animeId") {
            val response = executeWithRetry("get_anime") { api.getAnimeEntry(animeId) }
            val dto = response?.takeIf { it.isSuccessful }?.body()?.data
            if (dto != null) rememberAnimeDigest(dto)
            dto
        }
    }

    suspend fun fetchAnimeByAniListId(aniListId: Int): D1AnimeEntryDto? {
        if (aniListId <= 0) return null
        return coalesceRead("anime_anilist:$aniListId") {
            val response = executeWithRetry("get_anime_by_anilist") {
                api.listAnimeEntries(
                    where = buildWhereJson("aniListId" to aniListId),
                    limit = 1,
                    offset = 0
                )
            }
            val dto = response?.takeIf { it.isSuccessful }?.body()?.data?.firstOrNull()
            if (dto != null) rememberAnimeDigest(dto)
            dto
        }
    }

    suspend fun searchAnimeByAliasPage(
        normalizedQuery: String,
        limit: Int = 25,
        offset: Int = 0
    ): List<D1AnimeEntryDto> {
        val q = normalizedQuery.trim().lowercase()
        if (q.isBlank()) return emptyList()
        val boundedLimit = limit.coerceIn(1, 100)
        val safeOffset = offset.coerceAtLeast(0)
        return coalesceRead("search_alias:$q:$boundedLimit:$safeOffset") {
            val aliasResp = executeWithRetry("search_aliases") {
                api.listAnimeAliases(
                    where = buildWhereJson("alias" to q),
                    limit = boundedLimit,
                    offset = safeOffset
                )
            }
            val animeIds = aliasResp?.takeIf { it.isSuccessful }?.body()?.data
                ?.map { it.animeId }
                ?.distinct()
                ?.take(100)
                .orEmpty()
            if (animeIds.isEmpty()) return@coalesceRead emptyList()

            val animeResp = executeWithRetry("search_anime_batch") {
                api.listAnimeEntries(
                    where = buildWhereInJson("id", animeIds),
                    limit = animeIds.size,
                    offset = 0
                )
            }
            val rows = animeResp?.takeIf { it.isSuccessful }?.body()?.data.orEmpty()
            rows.forEach { rememberAnimeDigest(it) }
            rows
        }
    }

    suspend fun fetchSeasonsForAnime(
        animeId: String,
        limit: Int = 50,
        offset: Int = 0
    ): List<D1SeasonEntryDto> {
        if (animeId.isBlank()) return emptyList()
        return coalesceRead("seasons:$animeId:$limit:$offset") {
            val response = executeWithRetry("list_seasons") {
                api.listSeasonEntries(
                    where = buildWhereJson("animeId" to animeId),
                    sort = "seasonNumber",
                    order = "ASC",
                    limit = limit.coerceIn(1, 200),
                    offset = offset.coerceAtLeast(0)
                )
            }
            val rows = response?.takeIf { it.isSuccessful }?.body()?.data.orEmpty()
            rows.forEach { writtenDigests["season:${it.id}"] = it.stableDigest() }
            rows
        }
    }

    suspend fun fetchEpisodesPage(
        animeId: String,
        seasonNumber: Int,
        limit: Int = 30,
        offset: Int = 0
    ): List<D1EpisodeEntryDto> {
        if (animeId.isBlank()) return emptyList()
        val boundedLimit = limit.coerceIn(1, 100)
        val safeOffset = offset.coerceAtLeast(0)
        return coalesceRead("episodes:$animeId:s$seasonNumber:$boundedLimit:$safeOffset") {
            val response = executeWithRetry("list_episodes") {
                api.listEpisodeEntries(
                    where = buildWhereJson("animeId" to animeId, "seasonNumber" to seasonNumber),
                    sort = "episodeNumber",
                    order = "ASC",
                    limit = boundedLimit,
                    offset = safeOffset
                )
            }
            val rows = response?.takeIf { it.isSuccessful }?.body()?.data.orEmpty()
            rows.forEach { writtenDigests["episode:${it.id}"] = it.stableDigest() }
            rows
        }
    }

    suspend fun fetchCatalogMapping(aniListId: Int): D1CatalogMappingDto? {
        if (aniListId <= 0) return null
        return coalesceRead("mapping:$aniListId") {
            val response = executeWithRetry("get_mapping") { api.getCatalogMapping(aniListId) }
            val dto = response?.takeIf { it.isSuccessful }?.body()?.data
            if (dto != null) writtenDigests["mapping:${dto.aniListId}"] = dto.stableDigest()
            dto
        }
    }

    suspend fun fetchScopedStreaming(
        contentKey: String,
        isMovie: Boolean,
        seasonNumber: Int,
        episodeNumber: Int
    ): D1ScopedStreamingBundle? {
        if (contentKey.isBlank()) return null
        val containerScopeKey = buildContainerD1ScopeKey(contentKey, isMovie)
        val scopeKey = ContentIdentityHelper.scopeKey(contentKey, isMovie, seasonNumber, episodeNumber)
        return coalesceRead("stream_scope:$scopeKey") {
            coroutineScope {
                val containerDeferred = async {
                    executeWithRetry("get_stream_container") {
                        api.getStreamContainer(containerScopeKey)
                    }?.takeIf { it.isSuccessful }?.body()?.data
                }
                val seasonDeferred = if (!isMovie) {
                    async {
                        val seasonDocKey = "$contentKey:s$seasonNumber"
                        executeWithRetry("list_stream_seasons") {
                            api.listStreamSeasons(
                                where = buildWhereJson("seasonDocKey" to seasonDocKey),
                                limit = 1
                            )
                        }?.takeIf { it.isSuccessful }?.body()?.data?.firstOrNull()
                    }
                } else null
                val episodeDeferred = if (!isMovie) {
                    async {
                        val episodeDocKey = "$contentKey:s$seasonNumber:e$episodeNumber"
                        executeWithRetry("list_stream_episodes") {
                            api.listStreamEpisodes(
                                where = buildWhereJson("episodeDocKey" to episodeDocKey),
                                limit = 1
                            )
                        }?.takeIf { it.isSuccessful }?.body()?.data?.firstOrNull()
                    }
                } else null
                val providersDeferred = async {
                    executeWithRetry("list_stream_providers") {
                        api.listStreamProviders(
                            where = buildWhereJson("scopeKey" to scopeKey),
                            limit = 100
                        )
                    }?.takeIf { it.isSuccessful }?.body()?.data.orEmpty()
                }
                val sourcesDeferred = async {
                    executeWithRetry("list_stream_sources") {
                        api.listStreamSources(
                            where = buildWhereJson("scopeKey" to scopeKey),
                            limit = 200
                        )
                    }?.takeIf { it.isSuccessful }?.body()?.data.orEmpty()
                }

                val containerDto = containerDeferred.await()
                val seasonDto = seasonDeferred?.await()
                val episodeDto = episodeDeferred?.await()
                val providerDtos = providersDeferred.await()
                val sourceDtos = sourcesDeferred.await()

                containerDto?.let { writtenDigests["sc:${it.scopeKey}"] = it.stableDigest() }
                seasonDto?.let { writtenDigests["ss:${it.seasonDocKey}"] = it.stableDigest() }
                episodeDto?.let { writtenDigests["se:${it.episodeDocKey}"] = it.stableDigest() }
                providerDtos.forEach { writtenDigests["sp:${it.providerDocKey}"] = it.stableDigest() }
                sourceDtos.forEach { writtenDigests["ssrc:${it.sourceDocKey}"] = it.stableDigest() }

                val activeProviders = providerDtos
                    .filter { it.deletedAt == null && it.status != StreamRecordStatus.REMOVED.name }
                    .map { it.toRoomEntity() }
                val activeSources = sourceDtos
                    .filter { it.deletedAt == null && it.status != StreamRecordStatus.REMOVED.name }
                    .map { it.toRoomEntity() }

                if (containerDto == null && episodeDto == null && activeProviders.isEmpty() && activeSources.isEmpty()) {
                    return@coroutineScope null
                }

                D1ScopedStreamingBundle(
                    container = containerDto?.takeIf { it.deletedAt == null }?.toRoomEntity(),
                    season = seasonDto?.takeIf { it.deletedAt == null }?.toRoomEntity(),
                    episode = episodeDto?.takeIf { it.deletedAt == null }?.toRoomEntity(),
                    providers = activeProviders,
                    sources = activeSources
                )
            }
        }
    }

    suspend fun upsertAnimeIfChanged(entry: AnimeEntry): Boolean {
        val dto = entry.toD1Dto()
        val key = "anime:${dto.id}"
        val digest = dto.stableDigest()
        if (writtenDigests[key] == digest) {
            metricsTracker.recordD1WriteSkipped()
            return true
        }
        val response = executeWithRetry("upsert_anime", isWrite = true) { api.upsertAnimeEntry(dto) }
        val ok = response?.isSuccessful == true && response.body()?.success == true
        if (ok) writtenDigests[key] = digest
        return ok
    }

    suspend fun upsertAnimeBatchIfChanged(entries: List<AnimeEntry>): Int {
        var written = 0
        for (entry in entries) {
            if (upsertAnimeIfChanged(entry)) written++
        }
        return written
    }

    suspend fun insertMissingAliases(animeId: String, desiredAliases: List<AnimeAlias>): Int {
        if (animeId.isBlank() || desiredAliases.isEmpty()) return 0
        val existingSet = knownAliasesByAnime[animeId] ?: run {
            val remoteAliases = executeWithRetry("list_existing_aliases") {
                api.listAnimeAliases(
                    where = buildWhereJson("animeId" to animeId),
                    limit = 200
                )
            }?.takeIf { it.isSuccessful }?.body()?.data.orEmpty()
            ConcurrentHashMap.newKeySet<String>().apply {
                remoteAliases.forEach { add("${it.animeId}::${it.alias}") }
                knownAliasesByAnime[animeId] = this
            }
        }
        val missing = desiredAliases
            .filter { it.animeId == animeId && it.alias.isNotBlank() }
            .distinctBy { it.alias }
            .filter { "${it.animeId}::${it.alias}" !in existingSet }
        if (missing.isEmpty()) {
            metricsTracker.recordD1WriteSkipped()
            return 0
        }
        var inserted = 0
        for (alias in missing) {
            val response = executeWithRetry("insert_alias", isWrite = true) {
                api.createAnimeAlias(alias.toD1InsertDto())
            }
            if (response?.isSuccessful == true) {
                existingSet.add("${alias.animeId}::${alias.alias}")
                inserted++
            }
        }
        return inserted
    }

    suspend fun upsertCatalogMappingIfChanged(mapping: CatalogMapping): Boolean {
        val dto = mapping.toD1Dto()
        val key = "mapping:${dto.aniListId}"
        val digest = dto.stableDigest()
        if (writtenDigests[key] == digest) {
            metricsTracker.recordD1WriteSkipped()
            return true
        }
        val response = executeWithRetry("upsert_mapping", isWrite = true) { api.upsertCatalogMapping(dto) }
        val ok = response?.isSuccessful == true && response.body()?.success == true
        if (ok) writtenDigests[key] = digest
        return ok
    }

    suspend fun upsertSeasonsIfChanged(seasons: List<SeasonEntry>): Int {
        var written = 0
        for (season in seasons.distinctBy { it.animeId to it.seasonNumber }.distinctBy { it.id }) {
            val dto = season.toD1Dto()
            val key = "season:${dto.id}"
            val digest = dto.stableDigest()
            if (writtenDigests[key] == digest) {
                metricsTracker.recordD1WriteSkipped()
                continue
            }
            val resp = executeWithRetry("upsert_season", isWrite = true) { api.upsertSeasonEntry(dto) }
            if (resp?.isSuccessful == true && resp.body()?.success == true) {
                writtenDigests[key] = digest
                written++
            }
        }
        return written
    }

    suspend fun upsertEpisodesIfChanged(episodes: List<EpisodeEntry>): Int {
        var written = 0
        val dedupedEpisodes = episodes
            .distinctBy { Triple(it.animeId, it.seasonNumber, it.episodeNumber) }
            .distinctBy { it.id }
        for (episode in dedupedEpisodes) {
            val dto = episode.toD1Dto()
            val key = "episode:${dto.id}"
            val digest = dto.stableDigest()
            if (writtenDigests[key] == digest) {
                metricsTracker.recordD1WriteSkipped()
                continue
            }
            val resp = executeWithRetry("upsert_episode", isWrite = true) { api.upsertEpisodeEntry(dto) }
            if (resp?.isSuccessful == true && resp.body()?.success == true) {
                writtenDigests[key] = digest
                written++
            }
        }
        return written
    }

    suspend fun publishScopedStreamingIfChanged(
        container: StreamContainerEntity,
        season: StreamSeasonEntity?,
        episode: StreamEpisodeEntity?,
        providers: List<StreamProviderEntity>,
        sources: List<StreamSourceEntity>
    ): Boolean = coroutineScope {
        val gate = Semaphore(4)
        val tasks = mutableListOf<kotlinx.coroutines.Deferred<Boolean>>()

        val containerDto = container.toD1Dto()
        val cKey = "sc:${containerDto.scopeKey}"
        val cDigest = containerDto.stableDigest()
        if (writtenDigests[cKey] == cDigest) {
            metricsTracker.recordD1WriteSkipped()
        } else {
            tasks += async {
                gate.withPermit {
                    val resp = executeWithRetry("upsert_stream_container", isWrite = true) {
                        api.upsertStreamContainer(containerDto)
                    }
                    if (resp?.isSuccessful == true && resp.body()?.success == true) {
                        writtenDigests[cKey] = cDigest
                        true
                    } else {
                        false
                    }
                }
            }
        }

        if (season != null) {
            val seasonDto = season.toD1Dto()
            val sKey = "ss:${seasonDto.seasonDocKey}"
            val sDigest = seasonDto.stableDigest()
            if (writtenDigests[sKey] == sDigest) {
                metricsTracker.recordD1WriteSkipped()
            } else {
                tasks += async {
                    gate.withPermit {
                        val resp = executeWithRetry("upsert_stream_season", isWrite = true) {
                            api.upsertStreamSeason(seasonDto)
                        }
                        if (resp?.isSuccessful == true && resp.body()?.success == true) {
                            writtenDigests[sKey] = sDigest
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        }

        if (episode != null) {
            val epDto = episode.toD1Dto()
            val eKey = "se:${epDto.episodeDocKey}"
            val eDigest = epDto.stableDigest()
            if (writtenDigests[eKey] == eDigest) {
                metricsTracker.recordD1WriteSkipped()
            } else {
                tasks += async {
                    gate.withPermit {
                        val resp = executeWithRetry("upsert_stream_episode", isWrite = true) {
                            api.upsertStreamEpisode(epDto)
                        }
                        if (resp?.isSuccessful == true && resp.body()?.success == true) {
                            writtenDigests[eKey] = eDigest
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        }

        for (provider in providers.distinctBy { it.providerDocKey }) {
            val pDto = provider.toD1Dto()
            val pKey = "sp:${pDto.providerDocKey}"
            val pDigest = pDto.stableDigest()
            if (writtenDigests[pKey] == pDigest) {
                metricsTracker.recordD1WriteSkipped()
                continue
            }
            tasks += async {
                gate.withPermit {
                    val resp = executeWithRetry("upsert_stream_provider", isWrite = true) {
                        api.upsertStreamProvider(pDto)
                    }
                    if (resp?.isSuccessful == true && resp.body()?.success == true) {
                        writtenDigests[pKey] = pDigest
                        true
                    } else {
                        false
                    }
                }
            }
        }

        val dedupedSources = sources
            .map { src ->
                val trimmed = src.sourceId.trim()
                if (trimmed == src.sourceId) src else src.copy(sourceId = trimmed)
            }
            .distinctBy { it.providerId to it.sourceId }
            .distinctBy { it.sourceDocKey }

        for (source in dedupedSources) {
            val sDto = source.toD1MetadataDto()
            val srcKey = "ssrc:${sDto.sourceDocKey}"
            val srcDigest = sDto.stableDigest()
            if (writtenDigests[srcKey] == srcDigest) {
                metricsTracker.recordD1WriteSkipped()
                continue
            }
            tasks += async {
                gate.withPermit {
                    val resp = executeWithRetry("upsert_stream_source", isWrite = true) {
                        api.upsertStreamSource(sDto)
                    }
                    if (resp?.isSuccessful == true && resp.body()?.success == true) {
                        writtenDigests[srcKey] = srcDigest
                        true
                    } else {
                        false
                    }
                }
            }
        }

        if (tasks.isEmpty()) true else tasks.awaitAll().all { it }
    }

    suspend fun reportPlaybackSuccessMetadata(sourceEntity: StreamSourceEntity): Boolean {
        val now = System.currentTimeMillis()
        val lastAt = maxOf(
            lastSuccessReportAt[sourceEntity.sourceDocKey] ?: 0L,
            sourceEntity.lastPlaybackSuccessAt ?: 0L
        )
        if (lastAt > 0L && (now - lastAt < PLAYBACK_SUCCESS_REPORT_COOLDOWN_MS)) {
            metricsTracker.recordD1WriteSkipped()
            return true
        }
        // Claim cooldown slot before network call so concurrent callers coalesce without racing
        val previousClaim = lastSuccessReportAt.putIfAbsent(sourceEntity.sourceDocKey, now)
        if (previousClaim != null && (now - previousClaim < PLAYBACK_SUCCESS_REPORT_COOLDOWN_MS)) {
            metricsTracker.recordD1WriteSkipped()
            return true
        }
        lastSuccessReportAt[sourceEntity.sourceDocKey] = now

        val dto = sourceEntity.toD1MetadataDto().copy(
            status = StreamRecordStatus.AVAILABLE.name,
            lastVerifiedAt = now,
            lastPlaybackSuccessAt = now,
            updatedAt = now,
            version = maxOf(sourceEntity.version, 1L) + 1L
        )
        val resp = executeWithRetry("upsert_stream_source_success", isWrite = true) {
            api.upsertStreamSource(dto)
        }
        val ok = resp?.isSuccessful == true && resp.body()?.success == true
        if (ok) {
            writtenDigests["ssrc:${dto.sourceDocKey}"] = dto.stableDigest()
        } else {
            lastSuccessReportAt.remove(sourceEntity.sourceDocKey, now)
        }
        return ok
    }

    suspend fun softDeleteSourceMetadata(sourceEntity: StreamSourceEntity): Boolean {
        val now = System.currentTimeMillis()
        val dto = sourceEntity.toD1MetadataDto().copy(
            status = StreamRecordStatus.REMOVED.name,
            deletedAt = now,
            updatedAt = now,
            version = sourceEntity.version + 1L
        )
        val key = "ssrc:${dto.sourceDocKey}"
        val digest = dto.stableDigest()
        if (writtenDigests[key] == digest) {
            metricsTracker.recordD1WriteSkipped()
            return true
        }
        val resp = executeWithRetry("soft_delete_stream_source", isWrite = true) {
            api.upsertStreamSource(dto)
        }
        val ok = resp?.isSuccessful == true && resp.body()?.success == true
        if (ok) writtenDigests[key] = digest
        return ok
    }

    private fun rememberAnimeDigest(dto: D1AnimeEntryDto) {
        writtenDigests["anime:${dto.id}"] = dto.stableDigest()
    }

    fun clearDigestCache() {
        writtenDigests.clear()
        knownAliasesByAnime.clear()
        lastSuccessReportAt.clear()
    }

    companion object {
        private const val TAG = "CloudflareD1DataSource"
        private const val MAX_RETRIES = 2
        private const val BASE_RETRY_DELAY_MS = 250L
        private const val PLAYBACK_SUCCESS_REPORT_COOLDOWN_MS = 5 * 60 * 1000L

        fun buildWhereJson(vararg pairs: Pair<String, Any?>): String {
            val obj = JSONObject()
            for ((k, v) in pairs) {
                obj.put(k, v ?: JSONObject.NULL)
            }
            return obj.toString()
        }

        fun buildWhereInJson(column: String, values: List<Any>): String {
            val arr = JSONArray()
            values.take(100).forEach { arr.put(it) }
            return JSONObject().apply { put(column, arr) }.toString()
        }
    }
}
