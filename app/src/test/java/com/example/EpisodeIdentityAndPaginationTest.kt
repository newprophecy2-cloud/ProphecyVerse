package com.example

import com.example.data.local.EpisodeEntry
import com.example.data.repository.EpisodeCatalogIdentity
import com.example.data.identity.EpisodeSourceEpisodeIds
import com.example.data.repository.EpisodeRecordCandidate
import com.example.data.repository.EpisodeRecordPagination
import com.example.data.repository.EpisodeRecordSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeIdentityAndPaginationTest {
    private fun episode(
        id: String,
        season: Int = 1,
        number: Int = 1,
        title: String? = null,
        overview: String? = null
    ) = EpisodeEntry(id, "anime", season, number, title, overview)

    @Test
    fun duplicateEndpointsPreferTheActualEpisodeListIdAndKeepUsefulMetadata() {
        val merged = EpisodeCatalogIdentity.merge(
            listOf(
                EpisodeRecordCandidate(episode("nested-id", title = "Episode title"), "canonical-1", EpisodeRecordSource.DETAILS),
                EpisodeRecordCandidate(episode("list-id", overview = "Synopsis"), "canonical-1", EpisodeRecordSource.EPISODE_LIST)
            )
        )

        assertEquals(1, merged.size)
        assertEquals("list-id", merged.single().id)
        assertEquals("Episode title", merged.single().title)
        assertEquals("Synopsis", merged.single().overview)
        assertEquals(setOf("nested-id", "list-id"), EpisodeSourceEpisodeIds.read(merged.single()).toSet())
    }

    @Test
    fun recordsWithoutCanonicalIdsCanStillUseAnimeSeasonAndEpisodeIdentity() {
        val merged = EpisodeCatalogIdentity.merge(
            listOf(
                EpisodeRecordCandidate(episode("nested"), null, EpisodeRecordSource.DETAILS),
                EpisodeRecordCandidate(episode("episode-list"), null, EpisodeRecordSource.EPISODE_LIST)
            )
        )
        assertEquals(1, merged.size)
        assertEquals("episode-list", merged.single().id)
    }

    @Test
    fun sameActualIdUpdatesMetadataWithoutDuplicating() {
        val merged = EpisodeCatalogIdentity.merge(
            listOf(
                EpisodeRecordCandidate(episode("same-id", title = "Old"), "canon-a", EpisodeRecordSource.DETAILS),
                EpisodeRecordCandidate(episode("same-id", title = "New"), "canon-b", EpisodeRecordSource.EPISODE_LIST)
            )
        )
        assertEquals(1, merged.size)
        assertEquals("same-id", merged.single().id)
        assertEquals("New", merged.single().title)
    }

    @Test
    fun canonicalConflictIsNotInventedAsOneEpisode() {
        val merged = EpisodeCatalogIdentity.merge(
            listOf(
                EpisodeRecordCandidate(episode("id-a"), "canon-a", EpisodeRecordSource.DETAILS),
                EpisodeRecordCandidate(episode("id-b"), "canon-b", EpisodeRecordSource.EPISODE_LIST)
            )
        )
        assertEquals(listOf("id-a", "id-b"), merged.map { it.id })
    }

    @Test
    fun differentIdsFromTheSameEndpointAreNotCollapsedWithoutCrossEndpointEvidence() {
        val merged = EpisodeCatalogIdentity.merge(
            listOf(
                EpisodeRecordCandidate(episode("same-endpoint-a"), null, EpisodeRecordSource.EPISODE_LIST),
                EpisodeRecordCandidate(episode("same-endpoint-b"), null, EpisodeRecordSource.EPISODE_LIST)
            )
        )
        assertEquals(listOf("same-endpoint-a", "same-endpoint-b"), merged.map { it.id })
    }

    @Test
    fun dedicatedEpisodeListWinsWhenTheSameBackendIdHasConflictingCoordinates() {
        val merged = EpisodeCatalogIdentity.merge(
            listOf(
                EpisodeRecordCandidate(episode("same-id", number = 1), null, EpisodeRecordSource.DETAILS),
                EpisodeRecordCandidate(episode("same-id", number = 2), null, EpisodeRecordSource.EPISODE_LIST)
            )
        )
        assertEquals(1, merged.size)
        assertEquals("same-id", merged.single().id)
        assertEquals(2, merged.single().episodeNumber)
    }

    @Test
    fun seasonsAndEpisodeNumbersRemainPartOfLogicalIdentity() {
        val merged = EpisodeCatalogIdentity.merge(
            listOf(
                EpisodeRecordCandidate(episode("s1-e1", season = 1, number = 1), null, EpisodeRecordSource.DETAILS),
                EpisodeRecordCandidate(episode("s2-e1", season = 2, number = 1), null, EpisodeRecordSource.EPISODE_LIST),
                EpisodeRecordCandidate(episode("s1-e2", season = 1, number = 2), null, EpisodeRecordSource.EPISODE_LIST)
            )
        )
        assertEquals(3, merged.size)
        assertEquals(setOf(1, 2), merged.map { it.seasonNumber }.toSet())
        assertEquals(setOf(1, 2), merged.filter { it.seasonNumber == 1 }.map { it.episodeNumber }.toSet())
    }

    @Test
    fun animeIdentityRemainsPartOfLogicalIdentity() {
        val firstAnime = episode("first-anime-id").copy(animeId = "anime-one")
        val secondAnime = episode("second-anime-id").copy(animeId = "anime-two")
        val merged = EpisodeCatalogIdentity.merge(
            listOf(
                EpisodeRecordCandidate(firstAnime, "same-canonical", EpisodeRecordSource.DETAILS),
                EpisodeRecordCandidate(secondAnime, "same-canonical", EpisodeRecordSource.EPISODE_LIST)
            )
        )
        assertEquals(listOf("first-anime-id", "second-anime-id"), merged.map { it.id })
    }

    @Test
    fun validPlaybackIdentityWinsOverBlankIdWithoutChangingTheBackendId() {
        val merged = EpisodeCatalogIdentity.merge(
            listOf(
                EpisodeRecordCandidate(episode(" ", title = "Title"), null, EpisodeRecordSource.EPISODE_LIST),
                EpisodeRecordCandidate(episode("actual-backend-id"), null, EpisodeRecordSource.DETAILS)
            )
        )
        assertEquals(1, merged.size)
        assertEquals("actual-backend-id", merged.single().id)
        assertEquals("Title", merged.single().title)
    }

    @Test
    fun recordPaginationCoversAllRequestedPageBoundaries() {
        listOf(1, 29, 30, 31, 60, 61, 75).forEach { count ->
            val records = (1..count).map { it }
            val first = EpisodeRecordPagination.page(records, 0, 30, catalogComplete = true)
            assertEquals(minOf(30, count), first.items.size)
            assertEquals(count > 30, first.hasNext)
            if (count > 30) {
                val second = EpisodeRecordPagination.page(records, 30, 30, catalogComplete = true)
                assertEquals(minOf(30, count - 30), second.items.size)
                assertTrue(second.hasPrevious)
                assertEquals(count > 60, second.hasNext)
            }
            if (count > 60) {
                val final = EpisodeRecordPagination.page(records, 60, 30, catalogComplete = true)
                assertEquals(minOf(30, count - 60), final.items.size)
                assertFalse(final.hasNext)
            }
        }
    }

    @Test
    fun sparseEpisodeNumbersPageActualRecordsWithoutSkipping() {
        val sparse = listOf(1, 2, 5, 9, 20, 45)
        val first = EpisodeRecordPagination.page(sparse, 0, 3, catalogComplete = true)
        val second = EpisodeRecordPagination.page(sparse, 3, 3, catalogComplete = true)
        assertEquals(listOf(1, 2, 5), first.items)
        assertEquals(listOf(9, 20, 45), second.items)
    }

    @Test
    fun unknownCatalogDoesNotDisableNextEvenWhenCurrentPageIsEmpty() {
        val records = (1..31).toList()
        val page = EpisodeRecordPagination.page(records, 60, 30, catalogComplete = false)
        assertTrue(page.items.isEmpty())
        assertTrue(page.hasNext)
        assertTrue(page.hasPrevious)
    }

    @Test
    fun completeCatalogDisablesNextOnlyOnItsFinalKnownPage() {
        val records = (1..75).toList()
        assertTrue(EpisodeRecordPagination.page(records, 30, 30, catalogComplete = true).hasNext)
        assertFalse(EpisodeRecordPagination.page(records, 60, 30, catalogComplete = true).hasNext)
    }

    @Test
    fun eachSeasonCanBePagedIndependently() {
        val records = listOf(episode("s1-e1", 1, 1), episode("s2-e1", 2, 1), episode("s2-e2", 2, 2))
        val seasonTwo = records.filter { it.seasonNumber == 2 }
        val page = EpisodeRecordPagination.page(seasonTwo, 0, 30, catalogComplete = true)
        assertEquals(listOf("s2-e1", "s2-e2"), page.items.map { it.id })
    }
}
