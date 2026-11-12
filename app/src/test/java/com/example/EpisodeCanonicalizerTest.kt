package com.example

import com.example.data.identity.EpisodeCanonicalizer
import com.example.data.local.EpisodeEntry
import org.junit.Assert.assertEquals
import com.example.data.identity.EpisodeSourceEpisodeIds
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
    @Test
    fun duplicatePlayableAndUnavailableRecordsPreserveBothLookupIdsInEitherOrder() {
        val a = episode("playable-id", 1)
        val b = episode("retry-id", 1)
        val first = EpisodeCanonicalizer.canonicalize(listOf(a, b)).single()
        val reversed = EpisodeCanonicalizer.canonicalize(listOf(b, a)).single()
        assertEquals(1, listOf(first, reversed).distinctBy { it.id to EpisodeSourceEpisodeIds.read(it).toSet() }.size)
        assertEquals(setOf("playable-id", "retry-id"), EpisodeSourceEpisodeIds.read(first).toSet())
        assertEquals(setOf("playable-id", "retry-id"), EpisodeSourceEpisodeIds.read(reversed).toSet())
    }
    @Test
    fun incomingRefreshAndNullMetadataDoNotErasePreviouslyKnownAliasIds() {
        val old = episode("playable-id", 1)
        val incoming = episode("retry-id", 1)
        val merged = EpisodeCanonicalizer.canonicalize(listOf(old), listOf(incoming)).single()
        assertEquals(setOf("playable-id", "retry-id"), EpisodeSourceEpisodeIds.read(merged).toSet())
        val refreshOnlyOneRepresentation = EpisodeCanonicalizer.canonicalize(listOf(merged), listOf(episode("playable-id", 1))).single()
        assertEquals(setOf("playable-id", "retry-id"), EpisodeSourceEpisodeIds.read(refreshOnlyOneRepresentation).toSet())
    }
    @Test
    fun aliasIdsDoNotCrossAnimeOrSeasonBoundaries() {
        val records = listOf(episode("s1", 1), episode("s2", 1).copy(seasonNumber = 2),
            episode("other-anime", 1).copy(animeId = "other"))
        val merged = EpisodeCanonicalizer.canonicalize(records)
        assertEquals(3, merged.size)
        merged.forEach { assertEquals(listOf(it.id), EpisodeSourceEpisodeIds.read(it)) }
    }

}
