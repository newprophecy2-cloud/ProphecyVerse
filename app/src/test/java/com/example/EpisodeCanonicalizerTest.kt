package com.example

import com.example.data.identity.EpisodeCanonicalizer
import com.example.data.local.EpisodeEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeCanonicalizerTest {
    private fun episode(
        id: String,
        number: Int,
        title: String? = null,
        overview: String? = null
    ) = EpisodeEntry(
        id = id,
        animeId = "anime",
        seasonNumber = 1,
        episodeNumber = number,
        title = title,
        overview = overview
    )

    @Test
    fun duplicateEpisodeRowsCollapseToOneCanonicalEntry() {
        val result = EpisodeCanonicalizer.canonicalize(
            existing = listOf(
                episode("playable-1", 1),
                episode("stale-1", 1),
                episode("playable-2", 2),
                episode("stale-2", 2),
                episode("playable-3", 3),
                episode("stale-3", 3)
            )
        )

        assertEquals(listOf("playable-1", "playable-2", "playable-3"), result.map { it.id })
    }

    @Test
    fun incomingRefreshRowReplacesLegacyDuplicateAndRetainsMetadata() {
        val result = EpisodeCanonicalizer.canonicalize(
            existing = listOf(episode("legacy-1", 1, title = "Old title")),
            incoming = listOf(episode("current-1", 1, overview = "Current overview"))
        )

        assertEquals(1, result.size)
        assertEquals("current-1", result.single().id)
        assertEquals("Current overview", result.single().overview)
    }

    @Test
    fun orderingIsStableAcrossEpisodesAndSeasons() {
        val result = EpisodeCanonicalizer.canonicalize(
            existing = listOf(
                episode("s1e2", 2),
                episode("s1e1", 1),
                episode("s1e2-duplicate", 2)
            ) + episode("s1e3", 3).copy(seasonNumber = 2)
        )

        assertEquals(listOf("s1e1", "s1e2", "s1e3"), result.map { it.id })
    }
}
