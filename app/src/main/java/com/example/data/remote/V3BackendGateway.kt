package com.example.data.remote

import com.example.data.mapper.V3Mappers
import com.example.data.remote.session.PvSessionManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import retrofit2.Response

/**
 * Clean V3 backend gateway wrapping [ProphecyVerseApiService], managing
 * [PvSessionManager] tokens and enforcing Global Episode Integrity Rules.
 */
class V3BackendGateway(
    private val api: ProphecyVerseApiService
) {
    private val sessionMutex = Mutex()

    suspend fun ensureSession(hintQuery: String = "bleach"): Boolean {
        if (PvSessionManager.currentSessionId() != null) return true
        return sessionMutex.withLock {
            if (PvSessionManager.currentSessionId() != null) return@withLock true
            val resp = runCatching { api.searchAnime(hintQuery) }.getOrNull()
            val sid = resp?.takeIf { it.isSuccessful }?.body()?.sessionId
            if (!sid.isNullOrBlank()) {
                PvSessionManager.updateSessionId(sid)
                true
            } else {
                false
            }
        }
    }

    private suspend fun <T> withSessionRetry(
        hintQuery: String = "bleach",
        block: suspend () -> Response<T>
    ): Response<T> {
        ensureSession(hintQuery)
        val first = block()
        if (first.code() == 401) {
            PvSessionManager.clearSession()
            if (ensureSession(hintQuery)) {
                return block()
            }
        }
        return first
    }

    suspend fun searchCompat(query: String): Response<ProphecySearchResponse> {
        val resp = api.searchAnime(query)
        val body = resp.body()
        if (resp.isSuccessful && body != null) {
            PvSessionManager.updateSessionId(body.sessionId)
            return Response.success(V3Mappers.toCompatSearchResponse(body), resp.raw())
        }
        return Response.error(resp.code(), resp.errorBody() ?: okhttp3.ResponseBody.create(null, ""))
    }

    suspend fun getDetailsCompat(animeId: String): Response<ProphecyAnimeDetailsResponse> {
        val detResp = withSessionRetry { api.getAnimeDetails(animeId) }
        val detBody = detResp.body()
        if (!detResp.isSuccessful || detBody == null) {
            return Response.error(detResp.code(), detResp.errorBody() ?: okhttp3.ResponseBody.create(null, ""))
        }
        val seasonsResp = runCatching { withSessionRetry { api.getAnimeSeasons(animeId) } }.getOrNull()
        val seasons = seasonsResp?.takeIf { it.isSuccessful }?.body()?.seasons.orEmpty()
        val gate = Semaphore(3)
        val seasonsWithEpisodes = coroutineScope {
            seasons.map { season ->
                async {
                    gate.withPermit {
                        val eps = runCatching { withSessionRetry { api.getSeasonEpisodes(season.seasonId) } }
                            .getOrNull()
                            ?.takeIf { it.isSuccessful }
                            ?.body()
                            ?.episodes
                            .orEmpty()
                        season to eps
                    }
                }
            }.awaitAll()
        }
        return Response.success(
            V3Mappers.toCompatDetailsResponse(detBody, seasonsWithEpisodes),
            detResp.raw()
        )
    }

    suspend fun getEpisodesFlatCompat(animeId: String): Response<List<ProphecyEpisodeDto>> {
        val seasonsResp = withSessionRetry { api.getAnimeSeasons(animeId) }
        val seasonsBody = seasonsResp.body()
        if (!seasonsResp.isSuccessful || seasonsBody == null) {
            return Response.error(seasonsResp.code(), seasonsResp.errorBody() ?: okhttp3.ResponseBody.create(null, ""))
        }
        if (seasonsBody.seasons.isEmpty()) {
            val det = runCatching { withSessionRetry { api.getAnimeDetails(animeId) } }.getOrNull()
            val primaryEpId = det?.takeIf { it.isSuccessful }?.body()?.primaryEpisodeId?.takeIf { it.isNotBlank() }
            if (primaryEpId != null) {
                return Response.success(
                    listOf(
                        ProphecyEpisodeDto(
                            id = primaryEpId,
                            canonicalId = primaryEpId,
                            season = 1,
                            number = 1,
                            title = "Movie",
                            overview = null
                        )
                    ),
                    seasonsResp.raw()
                )
            }
        }
        val gate = Semaphore(3)
        val flat = coroutineScope {
            seasonsBody.seasons.map { season ->
                async {
                    gate.withPermit {
                        val eps = runCatching { api.getSeasonEpisodes(season.seasonId) }
                            .getOrNull()
                            ?.takeIf { it.isSuccessful }
                            ?.body()
                            ?.episodes
                            .orEmpty()
                        eps.filter {
                            it.episodeId.isNotBlank() &&
                                it.episodeNumber > 0 &&
                                V3Mappers.parseCanonicalVerification(it.verification) ==
                                com.example.domain.model.CanonicalVerification.Verified
                        }.map { ep ->
                            ProphecyEpisodeDto(
                                id = ep.episodeId,
                                canonicalId = ep.episodeId,
                                season = season.seasonNumber,
                                number = ep.episodeNumber,
                                title = ep.title,
                                overview = null
                            )
                        }
                    }
                }
            }.awaitAll().flatten()
        }
        return Response.success(flat, seasonsResp.raw())
    }

    suspend fun getEpisodeSourcesCompat(episodeId: String): Response<ProphecyEpisodeFullResponse> {
        val resp = withSessionRetry { api.getEpisodeSources(episodeId) }
        val body = resp.body()
        if (!resp.isSuccessful || body == null) {
            return Response.error(resp.code(), resp.errorBody() ?: okhttp3.ResponseBody.create(null, ""))
        }
        val compatSources = V3Mappers.toCompatSourceDtos(body)
        return Response.success(
            ProphecyEpisodeFullResponse(
                anime = null,
                selectedEpisode = null,
                sources = compatSources,
                sourcesStatus = if (compatSources.isNotEmpty()) "complete" else "empty",
                sourcesPartial = false,
                sourceError = null,
                providerStatus = body.providerCoverage
            ),
            resp.raw()
        )
    }

    suspend fun resolveEpisodeCompat(
        episodeId: String,
        sourceId: String
    ): Response<DirectPlayResponse> {
        val resp = withSessionRetry { api.resolveEpisode(episodeId, V3ResolveRequest(sourceId = sourceId)) }
        val body = resp.body()
        if (!resp.isSuccessful || body == null) {
            return Response.error(resp.code(), resp.errorBody() ?: okhttp3.ResponseBody.create(null, ""))
        }
        return Response.success(
            DirectPlayResponse(
                success = body.streamUrl.isNotBlank(),
                data = V3Mappers.toCompatDirectPlayData(body)
            ),
            resp.raw()
        )
    }
}
