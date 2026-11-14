package com.example.data.identity

import com.example.data.local.AnimeEntry

/**
 * A resolved anime route. A null canonicalAnimeId means the title is unresolved and must
 * remain on its original identity; callers must never infer a canonical title from text alone.
 */
data class CanonicalAnimeRoute(
    val animeId: String,
    val seasonNumber: Int? = null,
    val resolved: Boolean = false
)

object CanonicalAnimeIdentity {
    fun canonicalAnimeId(entry: AnimeEntry?): Int? =
        entry?.canonicalAnimeId?.takeIf { it > 0 }

    fun canonicalEntryId(entry: AnimeEntry?, fallbackId: String): String {
        val id = canonicalAnimeId(entry)
        return if (id == null) fallbackId else "anilist_$id"
    }

    fun routeFor(entry: AnimeEntry?, fallbackId: String, requestedSeason: Int? = null): CanonicalAnimeRoute {
        val canonicalId = canonicalAnimeId(entry) ?: return CanonicalAnimeRoute(fallbackId, requestedSeason, false)
        val target = "anilist_$canonicalId"
        if (target == fallbackId) return CanonicalAnimeRoute(fallbackId, requestedSeason, false)
        val season = requestedSeason ?: entry?.canonicalSeasonNumber?.takeIf { it > 1 }
        return CanonicalAnimeRoute(target, season, true)
    }
}
