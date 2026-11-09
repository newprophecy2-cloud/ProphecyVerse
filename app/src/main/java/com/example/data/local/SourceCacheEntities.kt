package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted source-discovery metadata only. This record deliberately has no fields for playback
 * URLs, request headers, cookies, tokens, or signatures.
 */
@Entity(
    tableName = "episode_source_snapshots",
    indices = [
        Index(value = ["animeId"]),
        Index(value = ["fetchedAtMs"])
    ]
)
data class EpisodeSourceSnapshotRecord(
    @PrimaryKey val cacheKey: String,
    val animeId: String,
    val seasonNumber: Int?,
    val episodeId: String,
    val episodeNumber: Int?,
    val state: String,
    val backendStatus: String?,
    val fetchedAtMs: Long,
    val apiCandidateCount: Int,
    val expiredCandidateCount: Int,
    val exactDuplicateCount: Int
)

/** One source candidate attached to exactly one Anime/Season/Episode snapshot. */
@Entity(
    tableName = "episode_source_candidates",
    indices = [Index(value = ["cacheKey"])]
)
data class EpisodeSourceCandidateRecord(
    @PrimaryKey val candidateKey: String,
    val cacheKey: String,
    val sourceId: String,
    val providerLabel: String?,
    val quality: String?,
    val codec: String?,
    val audioLanguage: String?,
    val sizeBytes: Long?,
    val downloadable: Boolean?,
    val expiresAt: String?
)
