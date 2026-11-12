package com.example.data.identity

import com.example.data.local.EpisodeEntry

/**
 * Canonicalizes episode rows at the anime + season + episode-number boundary.
 *
 * Backend episode IDs remain the selectable identity. When a refresh supplies a
 * row for a logical episode, that refreshed row wins over a legacy cached row;
 * if a refresh itself contains multiple representations, the lexicographically
 * stable ID is selected so the result is deterministic. Metadata is merged
 * without fabricating values.
 */
object EpisodeCanonicalizer {
    fun canonicalize(
        existing: List<EpisodeEntry>,
        incoming: List<EpisodeEntry> = emptyList()
    ): List<EpisodeEntry> {
        val refreshedKeys = incoming.mapTo(mutableSetOf(), ::logicalKey)
        val candidates = existing.filter { logicalKey(it) !in refreshedKeys } + incoming
        val priorByKey = existing.groupBy(::logicalKey)
        return candidates
            .groupBy(::logicalKey)
            .map { (key, group) -> key to group }
            .mapNotNull { (key, group) ->
                group.minWithOrNull(
                    compareBy<EpisodeEntry> { it.id.isBlank() }
                        .thenBy { it.id }
                )?.let { preferred ->
                    preferred.copy(
                        title = preferred.title?.takeIf(String::isNotBlank)
                            ?: group.firstNotNullOfOrNull { it.title?.takeIf(String::isNotBlank) },
                        overview = preferred.overview?.takeIf(String::isNotBlank)
                            ?: group.firstNotNullOfOrNull { it.overview?.takeIf(String::isNotBlank) },
                        sourceEpisodeIds = EpisodeSourceEpisodeIds.merge(*(priorByKey[key].orEmpty() + group).toTypedArray())
                    )
                }
            }
            .sortedWith(
                compareBy<EpisodeEntry> { it.animeId }
                    .thenBy { it.seasonNumber }
                    .thenBy { it.episodeNumber }
                    .thenBy { it.id }
            )
    }

    fun logicalKey(episode: EpisodeEntry): String =
        "${episode.animeId}\u0000${episode.seasonNumber}\u0000${episode.episodeNumber}"
}
