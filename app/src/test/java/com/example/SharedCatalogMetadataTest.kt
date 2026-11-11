package com.example

import com.example.data.firebase.SharedCatalogMetadata
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecySearchResultItem
import com.example.data.remote.ProphecySeasonDto
import org.junit.Assert.*
import org.junit.Test

class SharedCatalogMetadataTest {
    @Test fun animeAndMovieUseDistinctDeterministicIds() {
        val anime = SharedCatalogMetadata.fromSearch(ProphecySearchResultItem("b-1", "c-1", "Title", "anime"))!!
        val movie = SharedCatalogMetadata.fromSearch(ProphecySearchResultItem("b-1", "c-1", "Title", "movie"))!!
        assertEquals("ANIME", anime.contentType)
        assertEquals("MOVIE", movie.contentType)
        assertNotEquals(anime.documentId, movie.documentId)
        assertEquals(anime.documentId, SharedCatalogMetadata.fromSearch(ProphecySearchResultItem("b-1", "c-1", "Title", "anime"))!!.documentId)
    }
    @Test fun unsupportedMediaAndBlankIdsAreRejected() {
        assertNull(SharedCatalogMetadata.fromSearch(ProphecySearchResultItem("id", title="Title", mediaType="unknown")))
        assertNull(SharedCatalogMetadata.fromSearch(ProphecySearchResultItem("", title="Title", mediaType="anime")))
    }
    @Test fun changedBackendMetadataChangesHash() {
        val a = SharedCatalogMetadata.fromSearch(ProphecySearchResultItem("id", title="A", mediaType="anime"))!!
        val b = SharedCatalogMetadata.fromSearch(ProphecySearchResultItem("id", title="B", mediaType="anime"))!!
        assertNotEquals(a.metadataHash, b.metadataHash)
    }
    @Test fun searchMetadataHasNormalizedTitleAndStableDocumentId() {
        val item = SharedCatalogMetadata.fromSearch(
            ProphecySearchResultItem("naruto-1", title = "Naruto: Shippuden", mediaType = "anime")
        )!!
        assertEquals("naruto shippuden", item.normalizedTitle)
        assertTrue(item.searchKeywords.containsAll(listOf("naruto shippuden", "naruto", "shippuden")))
        assertEquals(2, item.schemaVersion)
        assertEquals(item.documentId, SharedCatalogMetadata.documentIdFor("ANIME", "naruto-1"))
    }
    @Test fun realSeasonAndEpisodeNumbersAndParentAreRequired() {
        assertNull(SharedCatalogMetadata.fromSeason("parent", null, ProphecySeasonDto(number=0)))
        assertNull(SharedCatalogMetadata.fromEpisode("parent", null, 1, ProphecyEpisodeDto(id="", number=1)))
        val episode = SharedCatalogMetadata.fromEpisode("parent", "canonical-parent", 3,
            ProphecyEpisodeDto(id="episode-backend", canonicalId="episode-canonical", number=8, title="Ep"))!!
        assertEquals("episode-backend", episode.backendId)
        assertEquals(3, episode.seasonNumber)
        assertEquals(8, episode.episodeNumber)
    }
    @Test fun detailRetainsOptionalDataAsOptionalAndGenresCanonicalized() {
        val detail = com.example.data.remote.ProphecyAnimeDetailsResponse(
            id="backend", canonicalId="canonical", title="Title", mediaType="anime", genres=listOf("Drama", "Action", "Drama"))
        val mapped = SharedCatalogMetadata.fromDetail(detail)!!
        assertEquals(listOf("Action", "Drama"), mapped.genres)
        assertNull(mapped.description)
    }
}
