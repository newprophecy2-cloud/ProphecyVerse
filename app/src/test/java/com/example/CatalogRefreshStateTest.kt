package com.example

import com.example.data.repository.EpisodeCatalogState
import com.example.data.repository.resolveEpisodeCatalogState
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogRefreshStateTest {
    @Test
    fun authoritativeCompleteSnapshotIsCompleteOrConfirmedEmpty() {
        assertEquals(
            EpisodeCatalogState.COMPLETE,
            resolveEpisodeCatalogState(true, 75, true, true, false)
        )
        assertEquals(
            EpisodeCatalogState.CONFIRMED_EMPTY,
            resolveEpisodeCatalogState(true, 0, true, true, false)
        )
    }

    @Test
    fun partialSuccessDoesNotClaimTheCatalogIsComplete() {
        assertEquals(
            EpisodeCatalogState.PARTIAL,
            resolveEpisodeCatalogState(false, 12, true, true, false)
        )
        assertEquals(
            EpisodeCatalogState.PARTIAL,
            resolveEpisodeCatalogState(false, 12, true, false, true)
        )
    }

    @Test
    fun bothEndpointsFailEvenWithRetainedCachedRows() {
        assertEquals(
            EpisodeCatalogState.FAILED,
            resolveEpisodeCatalogState(false, 42, true, false, false)
        )
    }

    @Test
    fun oneSuccessfulButIncompleteEndpointIsUnknownWhenNoRecordsExist() {
        assertEquals(
            EpisodeCatalogState.UNKNOWN,
            resolveEpisodeCatalogState(false, 0, true, true, false)
        )
        assertEquals(
            EpisodeCatalogState.UNKNOWN,
            resolveEpisodeCatalogState(false, 0, true, false, true)
        )
    }

    @Test
    fun noBackendIdentityIsUnknownRatherThanFailed() {
        assertEquals(
            EpisodeCatalogState.UNKNOWN,
            resolveEpisodeCatalogState(false, 0, false, false, false)
        )
    }
}
