package com.example

import com.example.data.remote.ProphecySourceDto
import com.example.data.repository.EpisodeNumberWindow
import com.example.data.repository.EpisodeSourceAggregator
import com.example.data.repository.EpisodeSourceResponseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeWindowAndSourcesTest {
    @Test
    fun initialWindowPreservesOriginalEpisodeNumbersOneThroughThirty() {
        val window = EpisodeNumberWindow.initial()
        assertEquals(1, window.firstEpisode)
        assertEquals(30, window.lastEpisodeInclusive)
        assertTrue(window.contains(1))
        assertTrue(window.contains(30))
        assertFalse(window.contains(31))
    }

    @Test
    fun selectedEpisodeNinetyNineLoadsNinetyNineThroughOneTwenty() {
        val window = EpisodeNumberWindow.aroundSelected(99)
        assertEquals(99, window.firstEpisode)
        assertEquals(120, window.lastEpisodeInclusive)
        assertTrue(window.contains(99))
        assertTrue(window.contains(120))
        assertFalse(window.contains(98))
        assertFalse(window.contains(121))
    }

    @Test
    fun episodeWindowKeepsGapsWithoutRenumbering() {
        val window = EpisodeNumberWindow.aroundSelected(1000)
        val cachedEpisodeNumbers = listOf(1000, 1002, 1007, 1021, 1022)
        assertEquals(listOf(1000, 1002, 1007, 1021), cachedEpisodeNumbers.filter(window::contains))
    }

    @Test
    fun sameSourceIdUpdatesMetadataWhileDifferentIdsPreserveProviderIdentity() {
        val sourceA = ProphecySourceDto(id = "same", label = "Provider A", quality = "1080p")
        val sourceB = ProphecySourceDto(id = "same", label = "Provider B", quality = "1080p")
        val updated = EpisodeSourceAggregator.aggregate(
            apiCandidates = listOf(sourceA, sourceB),
            rawStatus = "available",
            partial = false,
            errorMessage = null,
            nowMs = 0L
        )
        assertEquals(1, updated.sources.size)
        assertEquals("Provider B", updated.sources.single().label)

        val separateProviders = EpisodeSourceAggregator.aggregate(
            apiCandidates = listOf(sourceA.copy(id = "provider-a"), sourceB.copy(id = "provider-b")),
            rawStatus = "available",
            partial = false,
            errorMessage = null,
            nowMs = 0L
        )
        assertEquals(2, separateProviders.sources.size)
        assertFalse(
            ProphecySourceDto(id = "s", label = "A\u001fB", quality = "C").stableIdentity ==
                ProphecySourceDto(id = "s", label = "A", quality = "B\u001fC").stableIdentity
        )
    }

    @Test
    fun declaredSuccessfulEmptyResponseIsNotTreatedAsNetworkFailure() {
        val confirmedEmpty = EpisodeSourceAggregator.aggregate(emptyList(), "empty", false, null, 0L)
        val unknownEmpty = EpisodeSourceAggregator.aggregate(null, null, false, null, 0L)
        assertEquals(EpisodeSourceResponseState.EMPTY, confirmedEmpty.state)
        assertEquals(EpisodeSourceResponseState.UNKNOWN, unknownEmpty.state)
    }

    @Test
    fun sourceWithoutOptionalIdIsPreservedButHasNoPlayableIdentity() {
        val source = ProphecySourceDto(label = "New provider", language = null)
        assertEquals("", source.id)
        assertEquals("New provider", source.displayProviderName)
        assertFalse(source.hasUsableSourceId)
    }

    @Test
    fun absentAudioLanguageIsNotGuessed() {
        val source = ProphecySourceDto(id = "s-1", label = null, language = null)
        assertEquals("Unknown provider", source.displayProviderName)
        assertEquals(null, source.language)
    }
}
