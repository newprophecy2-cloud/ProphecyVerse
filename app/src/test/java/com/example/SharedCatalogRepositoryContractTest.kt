package com.example

import com.example.data.firebase.SharedCatalogMetadata
import com.example.data.firebase.SharedCatalogRepository
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecySeasonDto
import org.junit.Assert.*
import org.junit.Test

class SharedCatalogRepositoryContractTest {
    @Test fun mapsSupportedTypesOnlyToApprovedCollections() {
        assertEquals("shared_catalog_anime", SharedCatalogRepository.collectionFor("ANIME"))
        assertEquals("shared_catalog_movies", SharedCatalogRepository.collectionFor("MOVIE"))
        assertEquals("shared_catalog_seasons", SharedCatalogRepository.collectionFor("SEASON"))
        assertEquals("shared_catalog_episodes", SharedCatalogRepository.collectionFor("EPISODE"))
        assertEquals("shared_catalog_genres", SharedCatalogRepository.collectionFor("GENRE"))
        assertEquals("shared_catalog_categories", SharedCatalogRepository.collectionFor("CATEGORY"))
        assertNull(SharedCatalogRepository.collectionFor("USERS"))
    }

    @Test fun seasonAndEpisodeKeepRealParentIdentity() {
        val season = SharedCatalogMetadata.fromSeason("backend-anime", "canonical-anime",
            ProphecySeasonDto(canonicalId = "canonical-season-2", number = 2))!!
        assertEquals("canonical-anime", season.parentCanonicalId)
        assertEquals("backend-anime", season.parentBackendId)
        assertEquals(2, season.seasonNumber)
        val episode = SharedCatalogMetadata.fromEpisode("backend-anime", "canonical-anime", 2,
            ProphecyEpisodeDto(id = "backend-episode", canonicalId = "canonical-episode", number = 7))!!
        assertEquals("backend-anime", episode.parentBackendId)
        assertEquals("canonical-anime", episode.parentCanonicalId)
        assertEquals("backend-episode", episode.backendId)
        assertEquals(7, episode.episodeNumber)
    }

    @Test fun genreMappingRequiresActualBackendValueAndIsStable() {
        assertNull(SharedCatalogMetadata.fromGenre(null))
        assertNull(SharedCatalogMetadata.fromGenre("   "))
        val first = SharedCatalogMetadata.fromGenre("Drama")!!
        val repeated = SharedCatalogMetadata.fromGenre("Drama")!!
        assertEquals(first.documentId, repeated.documentId)
        assertEquals(first.metadataHash, repeated.metadataHash)
    }

    @Test fun sharedDocumentIdentityMustMatchDeterministicBackendKey() {
        val anime = SharedCatalogMetadata.fromSearch(
            com.example.data.remote.ProphecySearchResultItem("backend-42", "canonical-42", "Title", "anime")
        )!!
        assertTrue(SharedCatalogMetadata.hasStableIdentity("ANIME", "backend-42", anime.documentId))
        assertFalse(SharedCatalogMetadata.hasStableIdentity("MOVIE", "backend-42", anime.documentId))
        assertFalse(SharedCatalogMetadata.hasStableIdentity("ANIME", "other-backend", anime.documentId))
    }
}
