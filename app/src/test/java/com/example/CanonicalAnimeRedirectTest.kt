package com.example

import com.example.data.identity.CanonicalAnimeIdentity
import com.example.data.local.AnimeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalAnimeRedirectTest {
    @Test
    fun sequelRouteResolvesToCanonicalRootAndPreservesRequestedSeason() {
        val entry = AnimeEntry(
            id = "anilist_195604",
            aniListId = 195604,
            preferredTitle = "Black Clover Season 2",
            canonicalAnimeId = 97940,
            canonicalTitle = "Black Clover",
            canonicalSeasonNumber = 2
        )

        val route = CanonicalAnimeIdentity.routeFor(entry, entry.id, requestedSeason = 2)

        assertTrue(route.resolved)
        assertEquals("anilist_97940", route.animeId)
        assertEquals(2, route.seasonNumber)
    }

    @Test
    fun metadataSeasonIsUsedWhenDeepLinkHasNoSeason() {
        val entry = AnimeEntry(
            id = "anilist_5002",
            aniListId = 5002,
            preferredTitle = "Chrono Blade II",
            canonicalAnimeId = 5001,
            canonicalTitle = "Chrono Blade",
            canonicalSeasonNumber = 2
        )

        val route = CanonicalAnimeIdentity.routeFor(entry, entry.id)

        assertEquals("anilist_5001", route.animeId)
        assertEquals(2, route.seasonNumber)
    }

    @Test
    fun unresolvedOrCanonicalEntriesAreNotGuessedOrRedirected() {
        val unresolved = AnimeEntry(id = "anilist_123", aniListId = 123, preferredTitle = "Similar Name")
        val canonical = AnimeEntry(id = "anilist_97940", aniListId = 97940, preferredTitle = "Black Clover")

        val unresolvedRoute = CanonicalAnimeIdentity.routeFor(unresolved, unresolved.id)
        val canonicalRoute = CanonicalAnimeIdentity.routeFor(canonical, canonical.id)

        assertFalse(unresolvedRoute.resolved)
        assertEquals(unresolved.id, unresolvedRoute.animeId)
        assertFalse(canonicalRoute.resolved)
        assertEquals(canonical.id, canonicalRoute.animeId)
    }
}
