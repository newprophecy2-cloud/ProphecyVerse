package com.example

import com.example.data.remote.DirectPlayRequest
import com.example.data.remote.DirectPlayResponse
import com.example.data.remote.ProphecyAnimeDetailsResponse
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecyEpisodeFullResponse
import com.example.data.remote.ProphecySearchResponse
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.remote.V3AnimeDetailsResponse
import com.example.data.remote.V3EpisodeDto
import com.example.data.remote.V3EpisodesResponse
import com.example.data.remote.V3ProviderSourceGroupDto
import com.example.data.remote.V3ResolveRequest
import com.example.data.remote.V3ResolveResponse
import com.example.data.remote.V3SearchResponse
import com.example.data.remote.V3SearchResultItem
import com.example.data.remote.V3SeasonDto
import com.example.data.remote.V3SeasonsResponse
import com.example.data.remote.V3SourceItemDto
import com.example.data.remote.V3SourcesResponse
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * Test-only bridge that adapts legacy test fixture callbacks into the V3
 * [ProphecyVerseApiService] contract so existing regression suites continue
 * validating AnimeRepository against V3BackendGateway.
 */
interface LegacyFixtureApi {
    suspend fun searchAnime(query: String, limit: Int? = 20, cursor: String? = null): Response<ProphecySearchResponse> =
        Response.error(404, "".toResponseBody(null))

    suspend fun getAnimeDetails(animeId: String): Response<ProphecyAnimeDetailsResponse> =
        Response.error(404, "".toResponseBody(null))

    suspend fun getAnimeEpisodes(animeId: String): Response<List<ProphecyEpisodeDto>> =
        Response.error(404, "".toResponseBody(null))

    suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> =
        Response.error(404, "".toResponseBody(null))

    suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> =
        Response.error(404, "".toResponseBody(null))
}

class LegacyFixtureToV3Adapter(
    val legacy: LegacyFixtureApi
) : ProphecyVerseApiService {

    private val seasonToAnime = ConcurrentHashMap<String, Pair<String, Int>>()
    private val episodeToAnime = ConcurrentHashMap<String, String>()
    private val cachedDetails = ConcurrentHashMap<String, ProphecyAnimeDetailsResponse>()

    override suspend fun searchAnime(query: String): Response<V3SearchResponse> {
        val resp = legacy.searchAnime(query, 25, null)
        val body = resp.body()
        if (!resp.isSuccessful || body == null) {
            return Response.error(resp.code(), resp.errorBody() ?: "".toResponseBody(null))
        }
        return Response.success(
            V3SearchResponse(
                status = "success",
                sessionId = "test-session",
                query = query,
                partial = body.partial,
                results = body.results.map { item ->
                    V3SearchResultItem(
                        animeId = item.id,
                        title = item.title,
                        mediaType = item.mediaType,
                        year = item.year?.toIntOrNull(),
                        posterUrl = item.posterUrl,
                        totalSeasons = item.seasonCount,
                        verifiedEpisodes = item.episodeCount,
                        providerIds = item.providerMappings?.mapNotNull { m ->
                            val k = m.provider ?: return@mapNotNull null
                            val v = m.value ?: m.id ?: return@mapNotNull null
                            k to v
                        }?.toMap()
                    )
                }
            ),
            resp.raw()
        )
    }

    override suspend fun getAnimeDetails(animeId: String): Response<V3AnimeDetailsResponse> {
        val resp = legacy.getAnimeDetails(animeId)
        val body = resp.body()
        if (!resp.isSuccessful || body == null) {
            return Response.error(resp.code(), resp.errorBody() ?: "".toResponseBody(null))
        }
        cachedDetails[animeId] = body
        body.seasons.orEmpty().forEach { s ->
            val sid = s.id ?: "${animeId}_s${s.number}"
            seasonToAnime[sid] = animeId to s.number
            s.episodes.orEmpty().forEach { ep -> episodeToAnime[ep.id] = animeId }
        }
        return Response.success(
            V3AnimeDetailsResponse(
                status = "success",
                animeId = body.id,
                title = body.title,
                alternativeTitles = body.alternativeTitles,
                mediaType = body.mediaType,
                year = body.year?.toIntOrNull(),
                description = body.description,
                posterUrl = body.posterUrl,
                genres = body.genres,
                totalSeasons = body.seasonCount ?: body.seasons?.size,
                verifiedEpisodes = body.episodeCount,
                seasonStructureStatus = if (body.partial == true) "partial" else "verified"
            ),
            resp.raw()
        )
    }

    override suspend fun getAnimeSeasons(animeId: String): Response<V3SeasonsResponse> {
        val details = cachedDetails[animeId] ?: legacy.getAnimeDetails(animeId).body()
        val seasons = details?.seasons.orEmpty()
        if (seasons.isEmpty()) {
            val flat = legacy.getAnimeEpisodes(animeId).body().orEmpty()
            flat.forEach { episodeToAnime[it.id] = animeId }
            val nums = flat.map { it.season ?: 1 }.distinct().ifEmpty { listOf(1) }
            return Response.success(
                V3SeasonsResponse(
                    status = "success",
                    animeId = animeId,
                    seasons = nums.map { n ->
                        val sid = "${animeId}_s$n"
                        seasonToAnime[sid] = animeId to n
                        V3SeasonDto(seasonId = sid, seasonNumber = n, verifiedEpisodes = flat.count { (it.season ?: 1) == n })
                    }
                )
            )
        }
        return Response.success(
            V3SeasonsResponse(
                status = "success",
                animeId = animeId,
                seasons = seasons.map { s ->
                    val sid = s.canonicalId ?: s.id ?: "${animeId}_s${s.number}"
                    seasonToAnime[sid] = animeId to s.number
                    V3SeasonDto(seasonId = sid, seasonNumber = s.number, verifiedEpisodes = s.episodes?.size)
                }
            )
        )
    }

    override suspend fun getSeasonEpisodes(seasonId: String): Response<V3EpisodesResponse> {
        val (animeId, seasonNum) = seasonToAnime[seasonId]
            ?: return Response.success(V3EpisodesResponse(status = "success", seasonId = seasonId))
        val details = cachedDetails[animeId] ?: legacy.getAnimeDetails(animeId).body()
        val epsFromDetails = details?.seasons.orEmpty()
            .firstOrNull { (it.id ?: "${animeId}_s${it.number}") == seasonId || it.number == seasonNum }
            ?.episodes.orEmpty()
        val eps = epsFromDetails.ifEmpty {
            legacy.getAnimeEpisodes(animeId).body().orEmpty().filter { (it.season ?: 1) == seasonNum }
        }
        eps.forEach { episodeToAnime[it.id] = animeId }
        return Response.success(
            V3EpisodesResponse(
                status = "success",
                seasonId = seasonId,
                episodes = eps.map { ep ->
                    V3EpisodeDto(
                        episodeId = ep.id,
                        episodeNumber = ep.number,
                        title = ep.title,
                        metadataStatus = "verified",
                        provenance = "provider_listing",
                        verification = "verified"
                    )
                }
            )
        )
    }

    override suspend fun getEpisodeSources(episodeId: String): Response<V3SourcesResponse> {
        val animeId = episodeToAnime[episodeId].orEmpty()
        val resp = legacy.getEpisodeFull(animeId, episodeId)
        val body = resp.body()
        if (!resp.isSuccessful || body == null) {
            return Response.error(resp.code(), resp.errorBody() ?: "".toResponseBody(null))
        }
        val items = body.sources.orEmpty().map { s ->
            V3SourceItemDto(
                sourceId = s.id,
                provider = s.label,
                quality = s.quality,
                codec = s.codec,
                language = s.language,
                providerMetadata = com.example.data.remote.V3ProviderMetadataDto(sizeBytes = s.sizeBytes),
                verificationStatus = "unverified",
                playabilityStatus = "available",
                available = true
            )
        }
        return Response.success(
            V3SourcesResponse(
                status = "success",
                episodeId = episodeId,
                totalSources = items.size,
                providers = if (items.isEmpty()) emptyList() else listOf(V3ProviderSourceGroupDto("Provider", items))
            ),
            resp.raw()
        )
    }

    override suspend fun resolveEpisode(episodeId: String, request: V3ResolveRequest): Response<V3ResolveResponse> {
        val animeId = episodeToAnime[episodeId].orEmpty()
        val resp = legacy.resolveDirectPlay(DirectPlayRequest(animeId = animeId, episodeId = episodeId, sourceId = request.sourceId))
        val body = resp.body()
        val data = body?.data
        if (!resp.isSuccessful || body == null || !body.success || data == null) {
            return Response.error(if (resp.isSuccessful) 422 else resp.code(), resp.errorBody() ?: "".toResponseBody(null))
        }
        return Response.success(
            V3ResolveResponse(
                status = "success",
                episodeId = episodeId,
                sourceId = data.sourceId,
                streamUrl = data.playbackUrl,
                playabilityStatus = data.compatibility,
                contentType = data.contentType,
                verificationStatus = data.message ?: "unverified",
                headers = data.headers.associate { pair ->
                    (pair.getOrNull(0) ?: "") to (pair.getOrNull(1) ?: "")
                }.filterKeys { it.isNotBlank() }
            ),
            resp.raw()
        )
    }
}
