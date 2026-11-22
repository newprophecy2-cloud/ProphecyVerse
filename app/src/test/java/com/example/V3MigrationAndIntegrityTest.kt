package com.example

import com.example.core.network.BackendConfig
import com.example.core.network.V3ErrorMapper
import com.example.data.mapper.V3Mappers
import com.example.data.remote.V3AnimeDetailsResponse
import com.example.data.remote.V3EpisodeDto
import com.example.data.remote.V3EpisodesResponse
import com.example.data.remote.V3ResolveResponse
import com.example.data.remote.V3SearchResponse
import com.example.data.remote.V3SeasonsResponse
import com.example.data.remote.V3SourcesResponse
import com.example.data.remote.session.PvSessionManager
import com.example.domain.model.CanonicalVerification
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V3MigrationAndIntegrityTest {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Test
    fun backendConfigPointsToV3AndNotLegacy() {
        assertEquals("https://prophecy-verse-v3-backend.onrender.com/", BackendConfig.BASE_URL)
        assertFalse(BackendConfig.BASE_URL.contains("api.prophecyverse.tech"))
        assertFalse(BackendConfig.BASE_URL.contains("moviebox-tui-prophecy.onrender.com"))
    }

    @Test
    fun canonicalVerificationHasOnlyVerifiedAndUnverified() {
        assertEquals(2, CanonicalVerification.entries.size)
        assertEquals(CanonicalVerification.Verified, V3Mappers.parseCanonicalVerification("verified"))
        assertEquals(CanonicalVerification.Verified, V3Mappers.parseCanonicalVerification("Verified"))
        assertEquals(CanonicalVerification.Unverified, V3Mappers.parseCanonicalVerification("unverified"))
        assertEquals(CanonicalVerification.Unverified, V3Mappers.parseCanonicalVerification("SyntheticFallback"))
        assertEquals(CanonicalVerification.Unverified, V3Mappers.parseCanonicalVerification(null))
    }

    @Test
    fun bleachEpisodeCountUsesVerifiedCountOnlyNeverAddsUnverified() {
        val json = """
            {
              "status": "success",
              "anime_id": "NP-A-BCD79D",
              "title": "Bleach",
              "media_type": "series",
              "year": 2004,
              "total_seasons": 1,
              "total_episodes": 366,
              "verified_episodes": 366,
              "unverified_episodes": 68,
              "season_structure_status": "verified"
            }
        """.trimIndent()
        val dto = moshi.adapter(V3AnimeDetailsResponse::class.java).fromJson(json)!!
        val domain = V3Mappers.toDomainAnime(dto)
        assertEquals(366, domain.verifiedEpisodes)
    }

    @Test
    fun episodeMapperFiltersOutUnverifiedAndSyntheticEpisodes() {
        val episodes = listOf(
            V3EpisodeDto(episodeId = "NP-E-1", episodeNumber = 1, title = "Day 1", verification = "verified"),
            V3EpisodeDto(episodeId = "NP-E-2", episodeNumber = 2, title = "Hidden", verification = "unverified"),
            V3EpisodeDto(episodeId = "NP-E-3", episodeNumber = 3, title = "Fake", verification = "SyntheticFallback"),
            V3EpisodeDto(episodeId = "NP-E-4", episodeNumber = 4, title = "Missing", verification = null)
        )
        val visible = V3Mappers.toVerifiedDomainEpisodes("NP-A-1", "NP-S-1", 1, episodes)
        assertEquals(1, visible.size)
        assertEquals("NP-E-1", visible[0].episodeId)
        assertTrue(visible.all { it.isUserVisible })
    }

    @Test
    fun sourceAndResolveDtosParseAndPreservePlayabilityAndVerificationDistinction() {
        val sourcesJson = """
            {
              "status": "success",
              "episode_id": "NP-E-7",
              "total_sources": 1,
              "verified_sources": 0,
              "language_verification_status": "unverified",
              "providers": [
                {
                  "provider": "MovieBox",
                  "sources": [
                    {
                      "source_id": "src-1",
                      "provider": "MovieBox",
                      "stream_url": "https://cdn.example.com/index.mpd",
                      "quality": "1080p",
                      "codec": "hevc",
                      "audio_codec": "mp4a.40.2",
                      "channels": "2",
                      "language": "Hindi",
                      "language_code": "hi",
                      "verification_status": "unverified",
                      "playability_status": "manifest_validated",
                      "available": true
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val srcResp = moshi.adapter(V3SourcesResponse::class.java).fromJson(sourcesJson)!!
        val domainSources = V3Mappers.toDomainSources(srcResp)
        assertEquals(1, domainSources.size)
        assertEquals("manifest_validated", domainSources[0].playabilityStatus)
        assertEquals("unverified", domainSources[0].verificationStatus)

        val resolveJson = """
            {
              "status": "success",
              "episode_id": "NP-E-7",
              "source_id": "src-1",
              "provider": "MovieBox",
              "stream_url": "https://cdn.example.com/index.mpd",
              "quality": "1080p",
              "verification_status": "unverified",
              "playability_status": "manifest_validated",
              "headers": {"Referer": "https://example.com"}
            }
        """.trimIndent()
        val resResp = moshi.adapter(V3ResolveResponse::class.java).fromJson(resolveJson)!!
        val resolved = V3Mappers.toResolvedStream("NP-E-7", resResp)
        assertEquals("https://cdn.example.com/index.mpd", resolved.streamUrl)
        assertEquals("unverified", resolved.verificationStatus)
        assertEquals("manifest_validated", resolved.playabilityStatus)
        assertEquals("https://example.com", resolved.headers["Referer"])
    }

    @Test
    fun sessionManagerCapturesAndClearsSessionId() {
        PvSessionManager.clearSession()
        assertEquals(null, PvSessionManager.currentSessionId())
        PvSessionManager.updateSessionId("sess-abc-123")
        assertEquals("sess-abc-123", PvSessionManager.currentSessionId())
        assertTrue(PvSessionManager.isSessionError(401, "SESSION_REQUIRED"))
        assertTrue(PvSessionManager.isSessionError(400, "SESSION_INVALID"))
        PvSessionManager.clearSession()
    }

    @Test
    fun errorMapperDistinguishes403_404_429_500_AndNetworkErrors() {
        val f403 = V3ErrorMapper.parseFailure(403, """{"error":"ACCESS_DENIED"}""", "Forbidden")
        assertFalse(f403.retryable)
        assertTrue(V3ErrorMapper.userFriendlyMessage(f403).contains("403"))

        val f404 = V3ErrorMapper.parseFailure(404, """{"error":"EPISODE_NOT_FOUND"}""", "Missing")
        assertFalse(f404.retryable)

        val f429 = V3ErrorMapper.parseFailure(429, null, "Rate limited")
        assertTrue(f429.retryable)

        val fNet = V3ErrorMapper.parseFailure(null, null, "Timeout")
        assertTrue(fNet.retryable)
    }

    @Test
    fun searchAndSeasonsAndEpisodesDtosParseCleanly() {
        val searchJson = """{"status":"success","session_id":"s-1","query":"Bleach","results":[{"anime_id":"NP-A-1","title":"Bleach","year":2004,"verified_episodes":366,"unverified_episodes":68}]}"""
        val search = moshi.adapter(V3SearchResponse::class.java).fromJson(searchJson)!!
        assertEquals("s-1", search.sessionId)
        assertEquals(1, search.results.size)

        val seasonsJson = """{"status":"success","anime_id":"NP-A-1","seasons":[{"season_id":"NP-S-1","season_number":1,"verified_episodes":366,"unverified_episodes":0}]}"""
        val seasons = moshi.adapter(V3SeasonsResponse::class.java).fromJson(seasonsJson)!!
        assertEquals("NP-S-1", seasons.seasons[0].seasonId)

        val epsJson = """{"status":"success","season_id":"NP-S-1","episodes":[{"episode_id":"NP-E-7","episode_number":7,"title":"Greetings from a Stuffed Toy","verification":"verified"}]}"""
        val eps = moshi.adapter(V3EpisodesResponse::class.java).fromJson(epsJson)!!
        assertEquals("NP-E-7", eps.episodes[0].episodeId)
    }
}
