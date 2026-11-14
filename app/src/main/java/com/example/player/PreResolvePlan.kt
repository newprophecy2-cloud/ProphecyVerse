package com.example.player

import com.example.data.local.EpisodeEntry
import com.example.data.identity.EpisodeCanonicalizer

/** Pure queue planning. Windows never cross an unrelated season boundary. */
object PreResolvePlan {
    const val UPCOMING_WINDOW_SIZE = 17
    const val FAST_WINDOW_SIZE = 7
    const val PREVIOUS_WINDOW_SIZE = 6

    fun sameSeasonEpisodes(current: EpisodeEntry, allEpisodes: List<EpisodeEntry>): List<EpisodeEntry> =
        EpisodeCanonicalizer.canonicalize(emptyList(), allEpisodes.filter {
            it.animeId == current.animeId && it.seasonNumber == current.seasonNumber
        })

    fun upcoming(current: EpisodeEntry, allEpisodes: List<EpisodeEntry>): List<EpisodeEntry> {
        val ordered = sameSeasonEpisodes(current, allEpisodes)
        val index = ordered.indexOfFirst { it.id == current.id && it.seasonNumber == current.seasonNumber && it.animeId == current.animeId }
        return if (index < 0) emptyList() else ordered.drop(index + 1).take(UPCOMING_WINDOW_SIZE)
    }

    fun fastUpcoming(current: EpisodeEntry, allEpisodes: List<EpisodeEntry>) = upcoming(current, allEpisodes).take(FAST_WINDOW_SIZE)
    fun backgroundUpcoming(current: EpisodeEntry, allEpisodes: List<EpisodeEntry>) = upcoming(current, allEpisodes).drop(FAST_WINDOW_SIZE)

    fun previous(current: EpisodeEntry, allEpisodes: List<EpisodeEntry>): List<EpisodeEntry> {
        val ordered = sameSeasonEpisodes(current, allEpisodes)
        val index = ordered.indexOfFirst { it.id == current.id && it.seasonNumber == current.seasonNumber && it.animeId == current.animeId }
        return if (index < 0) emptyList() else ordered.subList(maxOf(0, index - PREVIOUS_WINDOW_SIZE), index)
    }

    /** Compatibility helper retained for callers/tests; batches are deliberately bounded. */
    fun <T> batches(items: List<T>): List<List<T>> = items.chunked(UPCOMING_WINDOW_SIZE)
}
