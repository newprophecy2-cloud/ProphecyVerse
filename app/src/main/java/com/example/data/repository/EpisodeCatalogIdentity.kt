package com.example.data.repository

import com.example.data.local.EpisodeEntry

/** Provenance exists only during refresh; it is deliberately not persisted in Room. */
enum class EpisodeRecordSource {
    CACHED,
    DETAILS,
    EPISODE_LIST
}

data class EpisodeRecordCandidate(
    val episode: EpisodeEntry,
    val canonicalId: String?,
    val source: EpisodeRecordSource
)

/**
 * Resolves duplicate representations only when anime/season/episode identity agrees and available
 * canonical IDs do not conflict. The dedicated episode-list endpoint wins ID authority over nested
 * detail records; metadata is filled from either representation without changing the selected ID.
 */
object EpisodeCatalogIdentity {
    fun merge(candidates: List<EpisodeRecordCandidate>): List<EpisodeEntry> {
        val selected = mutableListOf<EpisodeRecordCandidate>()
        for (candidate in candidates) {
            val index = selected.indexOfFirst { existing ->
                    existing.episode.animeId == candidate.episode.animeId &&
                existing.episode.seasonNumber == candidate.episode.seasonNumber &&
                existing.episode.episodeNumber == candidate.episode.episodeNumber &&
                (existing.episode.id == candidate.episode.id ||
                    canonicalIdsMatch(existing.canonicalId, candidate.canonicalId) ||
                    (existing.source != candidate.source &&
                        canonicalIdsCompatible(existing.canonicalId, candidate.canonicalId))) &&
                    (existing.source != EpisodeRecordSource.CACHED || candidate.source != EpisodeRecordSource.CACHED)
            }
            if (index < 0) {
                selected += candidate
                continue
            }

            val existing = selected[index]
            val preferred = when {
                existing.episode.id.isBlank() && candidate.episode.id.isNotBlank() -> candidate
                candidate.episode.id.isBlank() && existing.episode.id.isNotBlank() -> existing
                sourcePriority(candidate.source) > sourcePriority(existing.source) -> candidate
                else -> existing
            }
            val other = if (preferred === candidate) existing else candidate
            selected[index] = preferred.copy(
                canonicalId = preferred.canonicalId?.takeIf(String::isNotBlank)
                    ?: other.canonicalId?.takeIf(String::isNotBlank),
                episode = preferred.episode.copy(
                    title = preferred.episode.title?.takeIf(String::isNotBlank)
                        ?: other.episode.title?.takeIf(String::isNotBlank),
                    overview = preferred.episode.overview?.takeIf(String::isNotBlank)
                        ?: other.episode.overview?.takeIf(String::isNotBlank)
                )
            )
        }
        // Room's existing primary key is the actual backend ID. If endpoints disagree about the
        // coordinates of that same ID, retain the dedicated episode-list endpoint's record rather
        // than persisting two rows or silently letting detail ordering win.
        val byBackendId = LinkedHashMap<String, EpisodeRecordCandidate>()
        val recordsWithoutId = mutableListOf<EpisodeRecordCandidate>()
        for (candidate in selected) {
            val id = candidate.episode.id
            if (id.isBlank()) {
                recordsWithoutId += candidate
                continue
            }
            val existing = byBackendId[id]
            if (existing == null || sourcePriority(candidate.source) > sourcePriority(existing.source)) {
                byBackendId[id] = candidate
            }
        }
        return (byBackendId.values + recordsWithoutId).map { it.episode }
    }

    private fun canonicalIdsCompatible(first: String?, second: String?): Boolean {
        val a = first?.takeIf(String::isNotBlank)
        val b = second?.takeIf(String::isNotBlank)
        return a == null || b == null || a == b
    }

    private fun canonicalIdsMatch(first: String?, second: String?): Boolean {
        val a = first?.takeIf(String::isNotBlank)
        val b = second?.takeIf(String::isNotBlank)
        return a != null && a == b
    }

    private fun sourcePriority(source: EpisodeRecordSource): Int = when (source) {
        EpisodeRecordSource.CACHED -> 0
        EpisodeRecordSource.DETAILS -> 1
        EpisodeRecordSource.EPISODE_LIST -> 2
    }
}

data class EpisodeRecordPage<T>(
    val items: List<T>,
    val offset: Int,
    val pageSize: Int,
    val totalKnownRecords: Int,
    val catalogComplete: Boolean
) {
    val hasPrevious: Boolean get() = offset > 0
    val hasNext: Boolean get() = !catalogComplete || offset + pageSize < totalKnownRecords
}

/** Pages actual ordered records, not assumed-consecutive episode numbers. */
object EpisodeRecordPagination {
    fun <T> page(
        orderedRecords: List<T>,
        offset: Int,
        pageSize: Int = 30,
        catalogComplete: Boolean
    ): EpisodeRecordPage<T> {
        require(pageSize > 0) { "Page size must be positive" }
        val safeOffset = offset.coerceAtLeast(0)
        return EpisodeRecordPage(
            items = orderedRecords.drop(safeOffset).take(pageSize),
            offset = safeOffset,
            pageSize = pageSize,
            totalKnownRecords = orderedRecords.size,
            catalogComplete = catalogComplete
        )
    }
}
