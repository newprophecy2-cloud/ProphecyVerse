package com.example.player

import com.example.data.local.EpisodeEntry
import com.example.data.identity.EpisodeCanonicalizer

/** Pure queue planning: episode IDs are scoped by anime and season, and catalog order is retained. */
object PreResolvePlan {
    const val BATCH_SIZE = 17

    fun upcoming(current: EpisodeEntry, allEpisodes: List<EpisodeEntry>): List<EpisodeEntry> {
        val ordered = EpisodeCanonicalizer.canonicalize(
            emptyList(), allEpisodes.filter { it.animeId == current.animeId }
        )
        val index = ordered.indexOfFirst {
            it.id == current.id && it.seasonNumber == current.seasonNumber && it.animeId == current.animeId
        }
        return if (index < 0) emptyList() else ordered.drop(index + 1)
    }

    fun <T> batches(items: List<T>): List<List<T>> = items.chunked(BATCH_SIZE)
}
