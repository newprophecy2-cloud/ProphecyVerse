package com.example

import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecyAnimeDetailsResponse
import com.example.data.remote.ProphecySeasonDto
import com.example.data.repository.backendMediaCategory
import com.example.data.repository.isAuthoritativeEpisodeSnapshot
import com.example.data.repository.preserveMovieMediaCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.squareup.moshi.Moshi

class BackendMediaCategoryTest {
    @Test
    fun `explicit movie media type remains movie`() {
        assertEquals("MOVIE", backendMediaCategory("movie"))
        assertEquals("MOVIE", backendMediaCategory(" MOVIE "))
    }

    @Test
    fun `series and anime types map explicitly`() {
        assertEquals("SERIES", backendMediaCategory("series"))
        assertEquals("ANIME", backendMediaCategory("anime"))
    }

    @Test
    fun `unknown or absent type is not guessed`() {
        assertNull(backendMediaCategory("feature film?"))
        assertNull(backendMediaCategory(null))
    }

    @Test
    fun `confirmed Movie classification survives conflicting detail response`() {
        assertEquals("MOVIE", preserveMovieMediaCategory("MOVIE", "movie", "series"))
        assertEquals("MOVIE", preserveMovieMediaCategory(null, "movie", "series"))
        assertEquals("SERIES", preserveMovieMediaCategory(null, null, "series"))
    }

    @Test
    fun `only complete valid detail records may replace cached episodes`() {
        val movieSeasonOne = listOf(
            ProphecySeasonDto(
                number = 1,
                episodes = listOf(ProphecyEpisodeDto(id = "backend-episode-id", season = 1, number = 1))
            )
        )
        assertEquals(true, isAuthoritativeEpisodeSnapshot(false, movieSeasonOne))
        assertEquals(true, isAuthoritativeEpisodeSnapshot(false, emptyList()))
        assertEquals(false, isAuthoritativeEpisodeSnapshot(true, movieSeasonOne))
        assertEquals(false, isAuthoritativeEpisodeSnapshot(false, listOf(ProphecySeasonDto(number = 0))))
        assertEquals(
            false,
            isAuthoritativeEpisodeSnapshot(
                false,
                listOf(ProphecySeasonDto(number = 1, episodes = listOf(ProphecyEpisodeDto(id = "", season = 1, number = 1))))
            )
        )
    }

    @Test
    fun `movie detail DTO preserves backend season id and actual S1E1 identity`() {
        val response = Moshi.Builder().build()
            .adapter(ProphecyAnimeDetailsResponse::class.java)
            .fromJson(
                """{"id":"eyJwIjoiYW5pem9uZSIsInYiOiJ5cXVlbThqcSIsInMiOjAsImUiOjB9","title":"Air: The Motion Picture","media_type":"series","partial":true,"seasons":[{"canonical_id":"136b44ac-eac2-4d1d-a7c5-3b242ff9eefd:season:1","number":1,"episodes":[{"id":"eyJwIjoiYW5pem9uZSIsInYiOiJ5cXVlbThqcSIsInMiOjEsImUiOjF9","season":1,"number":1,"title":"Complete Movie"}]}]}"""
            )!!
        val season = response.seasons!!.single()
        val episode = season.episodes!!.single()
        assertEquals("136b44ac-eac2-4d1d-a7c5-3b242ff9eefd:season:1", season.canonicalId)
        assertEquals("eyJwIjoiYW5pem9uZSIsInYiOiJ5cXVlbThqcSIsInMiOjEsImUiOjF9", episode.id)
        assertEquals(1, episode.season)
        assertEquals(1, episode.number)
    }

    @Test
    fun `nested episode may omit season for containing-season fallback`() {
        val episode = Moshi.Builder().build()
            .adapter(ProphecyEpisodeDto::class.java)
            .fromJson("""{"id":"episode-1","number":1}""")!!
        assertNull(episode.season)
        assertEquals(1, episode.number)
    }
}
