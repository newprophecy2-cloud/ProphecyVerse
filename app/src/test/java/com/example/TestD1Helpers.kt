package com.example

import com.example.data.cache.MemoryCacheManager
import com.example.data.d1.CloudflareD1ApiService
import com.example.data.d1.CloudflareD1DataSource
import com.example.data.d1.D1ListResponse
import com.example.data.d1.D1RecordResponse
import com.example.data.d1.D1StreamUrlRecordDto
import com.example.data.d1.D1TablesResponse
import com.example.data.metrics.DatabaseMetricsTracker
import com.example.data.remote.ProphecySourceDto
import com.example.data.repository.AnimeRepository
import com.example.data.repository.EpisodeSourceResponseState
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap

internal class FakeInMemoryD1Api : CloudflareD1ApiService {
    val records = ConcurrentHashMap<String, D1StreamUrlRecordDto>()
    var readCount = 0
    var writeCount = 0
    var deleteCount = 0
    var failReads = false
    var failWrites = false

    override suspend fun listTables(): Response<D1TablesResponse> =
        Response.success(D1TablesResponse(success = true, count = 1, tables = listOf("stream_urls")))

    override suspend fun listStreamUrls(
        where: String?,
        limit: Int?,
        offset: Int?
    ): Response<D1ListResponse<D1StreamUrlRecordDto>> {
        if (failReads) return Response.error(503, "D1 unavailable".toResponseBody(null))
        readCount++
        return Response.success(
            D1ListResponse(success = true, table = "stream_urls", data = records.values.toList())
        )
    }

    override suspend fun getStreamUrl(id: String): Response<D1RecordResponse<D1StreamUrlRecordDto>> {
        if (failReads) return Response.error(503, "D1 unavailable".toResponseBody(null))
        readCount++
        val found = records[id]
            ?: return Response.error(404, """{"success":false,"error":"Not found"}""".toResponseBody(null))
        return Response.success(D1RecordResponse(success = true, table = "stream_urls", data = found))
    }

    override suspend fun upsertStreamUrl(record: D1StreamUrlRecordDto): Response<D1RecordResponse<D1StreamUrlRecordDto>> {
        if (failWrites) return Response.error(503, "D1 write error".toResponseBody(null))
        writeCount++
        val key = record.id?.takeIf { it.isNotBlank() }
            ?: CloudflareD1DataSource.deterministicRecordId(record.animeId, record.episodeId, record.sourceId)
        val saved = record.copy(id = key)
        records[key] = saved
        return Response.success(D1RecordResponse(success = true, table = "stream_urls", data = saved))
    }

    override suspend fun deleteStreamUrl(id: String): Response<D1RecordResponse<D1StreamUrlRecordDto>> {
        if (failWrites) return Response.error(503, "D1 delete error".toResponseBody(null))
        deleteCount++
        val removed = records.remove(id)
        return Response.success(D1RecordResponse(success = true, table = "stream_urls", data = removed))
    }
}

internal fun createTestD1DataSource(
    metricsTracker: DatabaseMetricsTracker = DatabaseMetricsTracker(),
    api: CloudflareD1ApiService = FakeInMemoryD1Api()
): CloudflareD1DataSource = CloudflareD1DataSource(
    api = api,
    metricsTracker = metricsTracker
)

internal fun seedEpisodeSourceSnapshot(
    memoryCache: MemoryCacheManager,
    scopeKey: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    sources: List<ProphecySourceDto>,
    fetchedAtMs: Long = System.currentTimeMillis()
) {
    val snapshot = AnimeRepository.EpisodeSourceSnapshot(
        sources = sources,
        state = if (sources.isNotEmpty()) EpisodeSourceResponseState.AVAILABLE else EpisodeSourceResponseState.EMPTY,
        backendStatus = "ok",
        fetchedAtMs = fetchedAtMs,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        apiCandidateCount = sources.size
    )
    memoryCache.putStreamSnapshot(scopeKey, snapshot)
}
