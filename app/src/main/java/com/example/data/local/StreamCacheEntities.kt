package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stream_containers",
    indices = [
        Index(value = ["contentKey"]),
        Index(value = ["anilistId"]),
        Index(value = ["backendId"]),
        Index(value = ["contentType"])
    ]
)
data class StreamContainerEntity(
    @PrimaryKey val scopeKey: String, // "anime:{contentKey}" or "movie:{contentKey}"
    val contentKey: String,           // AniList ID string when present, else local anime ID
    val anilistId: Int? = null,
    val contentType: String,          // "ANIME" or "MOVIE"
    val backendId: String? = null,
    val canonicalTitle: String? = null,
    val seasonCount: Int? = null,     // Must stay null for MOVIE
    val totalEpisodeCount: Int? = null, // Must stay null for MOVIE
    val providerCount: Int = 0,
    val sourceCount: Int = 0,
    val discoveryStatus: String = "NOT_DISCOVERED",
    val version: Long = 1L,
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
)

@Entity(
    tableName = "stream_seasons",
    indices = [
        Index(value = ["contentKey"]),
        Index(value = ["contentKey", "seasonNumber"])
    ]
)
data class StreamSeasonEntity(
    @PrimaryKey val seasonDocKey: String, // "{contentKey}:s{seasonNumber}"
    val contentKey: String,
    val seasonId: String,                 // "season_{seasonNumber}"
    val seasonNumber: Int,
    val backendSeasonId: String? = null,
    val episodeCount: Int = 0,
    val discoveryStatus: String = "NOT_DISCOVERED",
    val version: Long = 1L,
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
)

@Entity(
    tableName = "stream_episodes",
    indices = [
        Index(value = ["contentKey"]),
        Index(value = ["contentKey", "seasonNumber", "episodeNumber"]),
        Index(value = ["catalogEpisodeId"])
    ]
)
data class StreamEpisodeEntity(
    @PrimaryKey val episodeDocKey: String, // "{contentKey}:s{seasonNumber}:e{episodeNumber}"
    val contentKey: String,
    val seasonId: String,
    val episodeId: String,                 // "ep_{episodeNumber}"
    val catalogEpisodeId: String? = null,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val backendEpisodeId: String? = null,
    val backendSourceEpisodeIds: String? = null,
    val providerCount: Int = 0,
    val sourceCount: Int = 0,
    val discoveryStatus: String = "NOT_DISCOVERED",
    val version: Long = 1L,
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
)

@Entity(
    tableName = "stream_providers",
    indices = [
        Index(value = ["scopeKey"]),
        Index(value = ["providerId"])
    ]
)
data class StreamProviderEntity(
    @PrimaryKey val providerDocKey: String, // "{scopeKey}|p|{providerId}"
    val scopeKey: String,                   // "anime:{contentKey}:s{s}:e{e}" or "movie:{contentKey}"
    val providerId: String,
    val providerName: String,
    val status: String = "AVAILABLE",       // AVAILABLE, TEMPORARY_FAILURE, PERMANENTLY_UNAVAILABLE, REMOVED
    val sourceCount: Int = 0,
    val version: Long = 1L,
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
)

@Entity(
    tableName = "stream_sources",
    indices = [
        Index(value = ["scopeKey"]),
        Index(value = ["providerId"]),
        Index(value = ["sourceId"]),
        Index(value = ["status"])
    ]
)
data class StreamSourceEntity(
    @PrimaryKey val sourceDocKey: String,   // "{scopeKey}|p|{providerId}|s|{sourceId}"
    val scopeKey: String,
    val providerId: String,
    val sourceId: String,
    val sourceName: String,
    val backendEpisodeId: String? = null,   // Internal backend compatibility (e.g. Movie S1E1 id)
    val streamUrl: String? = null,
    val urlType: String = "direct",
    val contentType: String? = null,
    val quality: String? = null,
    val codec: String? = null,
    val audioLanguage: String? = null,
    val downloadable: Boolean? = null,
    val sizeBytes: Long? = null,
    val safeHeadersJson: String? = null,
    val expiresAt: String? = null,
    val status: String = "AVAILABLE",       // AVAILABLE, MISSING_URL, EXPIRED, FAILED, TEMPORARILY_UNAVAILABLE, REMOVED
    val version: Long = 1L,
    val lastVerifiedAt: Long? = null,
    val lastPlaybackSuccessAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
)
