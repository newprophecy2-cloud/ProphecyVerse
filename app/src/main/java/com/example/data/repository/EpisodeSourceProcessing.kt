package com.example.data.repository

import com.example.data.local.EpisodeEntry
import com.example.data.local.SeasonEntry
import com.example.data.remote.ProphecySourceDto
import java.time.Instant

/** States supported by the API response and local freshness information. */
enum class EpisodeSourceResponseState {
    AVAILABLE, EMPTY, PARTIAL, FAILED, TIMEOUT, UNKNOWN, STALE
}

data class EpisodeSourceAggregationResult(
    val sources: List<ProphecySourceDto>,
    val state: EpisodeSourceResponseState,
    val backendStatus: String?,
    val errorMessage: String?,
    val apiCandidateCount: Int,
    val parsedCandidateCount: Int,
    val expiredCandidateCount: Int,
    val exactDuplicateCount: Int
) {
    val playableCandidateCount: Int get() = sources.count { it.hasUsableSourceId }
}

object EpisodeSourceCacheIdentity {
    fun key(animeId: String, seasonNumber: Int?, episodeId: String): String =
        "${animeId.length}:$animeId:${seasonNumber?.toString() ?: "?"}:${episodeId.length}:$episodeId"
}

object EpisodeSourceRetryBackoff {
    fun delayMs(consecutiveFailures: Int, jitterMs: Long = 0L): Long {
        val attempts = consecutiveFailures.coerceAtLeast(1)
        val base = (2_000L * (1L shl (attempts - 1).coerceAtMost(6))).coerceAtMost(120_000L)
        return base + jitterMs.coerceIn(0L, base / 4L)
    }
}

object EpisodeSourceCachePolicy {
    fun mergeRefreshCandidates(
        existingCandidates: List<ProphecySourceDto>,
        incomingCandidates: List<ProphecySourceDto>,
        authoritativeComplete: Boolean,
        nowMs: Long
    ): List<ProphecySourceDto> {
        val candidates = if (authoritativeComplete) incomingCandidates else existingCandidates + incomingCandidates
        return candidates.filterNot { candidate ->
            val expiry = candidate.expiresAt?.takeIf(String::isNotBlank)
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            expiry != null && expiry <= nowMs
        }.distinct()
    }

    fun staleAfterRefreshFailure(
        snapshot: AnimeRepository.EpisodeSourceSnapshot,
        message: String,
        nowMs: Long
    ): AnimeRepository.EpisodeSourceSnapshot {
        val unexpired = snapshot.sources.filterNot { candidate ->
            val expiry = candidate.expiresAt?.takeIf(String::isNotBlank)
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            expiry != null && expiry <= nowMs
        }
        return snapshot.copy(
            sources = unexpired,
            state = EpisodeSourceResponseState.STALE,
            errorMessage = message,
            expiredCandidateCount = snapshot.expiredCandidateCount + snapshot.sources.size - unexpired.size
        )
    }
}

/**
 * Applies only evidence-based filtering: expired candidates and exact structural duplicates.
 * A missing ID or optional metadata never hides a candidate, and quality/provider are not keys.
 */
object EpisodeSourceAggregator {
    fun aggregate(
        apiCandidates: List<ProphecySourceDto>?,
        rawStatus: String?,
        partial: Boolean?,
        errorMessage: String?,
        nowMs: Long
    ): EpisodeSourceAggregationResult {
        val raw = apiCandidates.orEmpty()
        val nonExpired = raw.filterNot { candidate ->
            val expiry = candidate.expiresAt?.takeIf(String::isNotBlank)
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            expiry != null && expiry <= nowMs
        }
        val candidates = nonExpired.distinct()
        val status = rawStatus?.trim()?.takeIf(String::isNotEmpty)
        val explicitTimeout = errorMessage?.contains("timed out", ignoreCase = true) == true ||
            errorMessage?.contains("timeout", ignoreCase = true) == true
        val state = when {
            explicitTimeout -> EpisodeSourceResponseState.TIMEOUT
            partial == true -> EpisodeSourceResponseState.PARTIAL
            !errorMessage.isNullOrBlank() -> EpisodeSourceResponseState.FAILED
            candidates.isNotEmpty() -> EpisodeSourceResponseState.AVAILABLE
            else -> EpisodeSourceResponseState.UNKNOWN
        }
        return EpisodeSourceAggregationResult(
            sources = candidates,
            state = state,
            backendStatus = status,
            errorMessage = errorMessage,
            apiCandidateCount = raw.size,
            parsedCandidateCount = raw.size,
            expiredCandidateCount = raw.size - nonExpired.size,
            exactDuplicateCount = nonExpired.size - candidates.size
        )
    }
}

data class EpisodeCatalogSnapshot(
    val seasons: List<SeasonEntry>,
    val episodes: List<EpisodeEntry>
)

/** Merge partial catalog results without deleting any previously cached season or Episode. */
object EpisodeCatalogSnapshotMerger {
    fun merge(
        existing: EpisodeCatalogSnapshot,
        incoming: EpisodeCatalogSnapshot,
        authoritative: Boolean
    ): EpisodeCatalogSnapshot {
        if (authoritative) return EpisodeCatalogSnapshot(
            seasons = incoming.seasons.distinctBy { it.id },
            episodes = incoming.episodes.distinctBy { it.id }
        )
        return EpisodeCatalogSnapshot(
            seasons = (existing.seasons + incoming.seasons)
                .associateBy { it.id }
                .values
                .sortedBy { it.seasonNumber },
            episodes = (existing.episodes + incoming.episodes)
                .associateBy { it.id }
                .values
                .sortedWith(compareBy<EpisodeEntry> { it.seasonNumber }.thenBy { it.episodeNumber }.thenBy { it.id })
        )
    }
}
