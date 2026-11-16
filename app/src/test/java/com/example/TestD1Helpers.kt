package com.example

import com.example.data.cache.MemoryCacheManager
import com.example.data.d1.CloudflareD1ApiService
import com.example.data.d1.CloudflareD1DataSource
import com.example.data.d1.D1ListResponse
import com.example.data.d1.D1RecordResponse
import com.example.data.d1.D1StreamSourceRecordDto
import com.example.data.d1.D1TablesResponse
import com.example.data.metrics.DatabaseMetricsTracker
import com.example.data.remote.ProphecySourceDto
import com.example.data.repository.AnimeRepository
import com.example.data.repository.EpisodeSourceResponseState
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap

internal class FakeInMemoryD1Api : CloudflareD1ApiService {
    val records = ConcurrentHashMap<String, D1StreamSourceRecordDto>()
    var readCount = 0
    var writeCount = 0
    var deleteCount = 0
    var failReads = false
    var failWrites = false
    var availableTables: List<String> = listOf("stream_sources")

    override suspend fun listTables(): Response<D1TablesResponse> =
        Response.success(
            D1TablesResponse(
                success = true,
                count = availableTables.size,
                tables = availableTables
            )
        )

    override suspend fun listStreamSources(
        where: String?,
        limit: Int?,
        offset: Int?
    ): Response<D1ListResponse<D1StreamSourceRecordDto>> {
        if (failReads) return Response.error(503, "D1 unavailable".toResponseBody(null))
        if (!availableTables.any { it.equals(CloudflareD1DataSource.CANONICAL_TABLE, ignoreCase = true) }) {
            return Response.error(
                404,
                """{"success":false,"error":"Table not found","table":"stream_sources"}""".toResponseBody(null)
            )
        }
        readCount++
        val rows = records.values.toList().let { list ->
            val off = (offset ?: 0).coerceAtLeast(0)
            val lim = (limit ?: 50).coerceAtLeast(1)
            list.drop(off).take(lim)
        }
        return Response.success(
            D1ListResponse(
                success = true,
                table = CloudflareD1DataSource.CANONICAL_TABLE,
                total = records.size,
                limit = limit ?: 50,
                offset = offset ?: 0,
                count = rows.size,
                data = rows
            )
        )
    }

    override suspend fun getStreamSource(id: String): Response<D1RecordResponse<D1StreamSourceRecordDto>> {
        if (failReads) return Response.error(503, "D1 unavailable".toResponseBody(null))
        readCount++
        val found = records[id]
            ?: return Response.error(404, """{"success":false,"error":"Record not found"}""".toResponseBody(null))
        return Response.success(
            D1RecordResponse(
                success = true,
                table = CloudflareD1DataSource.CANONICAL_TABLE,
                data = found
            )
        )
    }

    override suspend fun upsertStreamSource(
        record: D1StreamSourceRecordDto
    ): Response<D1RecordResponse<D1StreamSourceRecordDto>> {
        if (failWrites) return Response.error(503, "D1 write error".toResponseBody(null))
        writeCount++
        val key = record.sourceDocKey.takeIf { it.isNotBlank() }
            ?: CloudflareD1DataSource.deterministicRecordId(
                record.scopeKey,
                record.backendEpisodeId.orEmpty(),
                record.sourceId
            )
        val saved = record.copy(sourceDocKey = key)
        records[key] = saved
        return Response.success(
            D1RecordResponse(
                success = true,
                message = "Record saved",
                table = CloudflareD1DataSource.CANONICAL_TABLE,
                data = saved
            )
        )
    }

    override suspend fun deleteStreamSource(id: String): Response<D1RecordResponse<D1StreamSourceRecordDto>> {
        if (failWrites) return Response.error(503, "D1 delete error".toResponseBody(null))
        deleteCount++
        val removed = records.remove(id)
            ?: return Response.error(404, """{"success":false,"error":"Record not found"}""".toResponseBody(null))
        return Response.success(
            D1RecordResponse(
                success = true,
                message = "Record deleted",
                table = CloudflareD1DataSource.CANONICAL_TABLE,
                changes = 1,
                data = removed
            )
        )
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
