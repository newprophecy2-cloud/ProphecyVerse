package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "anime_entries",
    indices = [
        Index(value = ["aniListId"]),
        Index(value = ["backendAnimeId"]),
        Index(value = ["isTrending"]),
        Index(value = ["isSeasonal"]),
        Index(value = ["isPopular"]),
        Index(value = ["isPlayable"])
    ]
)
data class AnimeEntry(
    @PrimaryKey val id: String, // backendAnimeId or "anilist_$aniListId"
    val aniListId: Int? = null,
    val backendAnimeId: String? = null,
    val titleRomaji: String? = null,
    val titleEnglish: String? = null,
    val titleNative: String? = null,
    val preferredTitle: String,
    val synopsis: String? = null,
    val year: String? = null,
    val season: String? = null,
    val format: String? = null,
    val status: String? = null,
    val genres: String = "", // Comma-separated
    val score: Double? = null,
    val episodeCount: Int? = null,
    val duration: Int? = null,
    val studios: String? = null,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val isPlayable: Boolean = false,
    val isTrending: Boolean = false,
    val isSeasonal: Boolean = false,
    val isPopular: Boolean = false,
    @ColumnInfo(defaultValue = "'ANIME'")
    val mediaCategory: String = "ANIME", // "ANIME", "KDRAMA", "MOVIE", "SERIES"
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "anime_aliases",
    indices = [
        Index(value = ["animeId"]),
        Index(value = ["alias"])
    ]
)
data class AnimeAlias(
    @PrimaryKey(autoGenerate = true) val aliasId: Long = 0,
    val animeId: String,
    val alias: String, // Normalized lowercase
    val originalAlias: String
)

@Entity(
    tableName = "catalog_mappings",
    indices = [
        Index(value = ["backendAnimeId"])
    ]
)
data class CatalogMapping(
    @PrimaryKey val aniListId: Int,
    val backendAnimeId: String,
    val confidence: Float,
    val mappedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "season_entries",
    indices = [
        Index(value = ["animeId"])
    ]
)
data class SeasonEntry(
    @PrimaryKey val id: String, // "${animeId}_s${seasonNumber}"
    val animeId: String,
    val seasonNumber: Int,
    val backendSeasonId: String? = null,
    val title: String? = null
)

@Entity(
    tableName = "episode_entries",
    indices = [
        Index(value = ["animeId"]),
        Index(value = ["animeId", "seasonNumber"])
    ]
)
data class EpisodeEntry(
    @PrimaryKey val id: String, // backend episode ID
    val animeId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String? = null,
    val overview: String? = null,
    val isAvailable: Boolean = true
)

@Entity(tableName = "search_queries")
data class SearchQuery(
    @PrimaryKey val query: String,
    val totalResults: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "search_result_refs",
    indices = [
        Index(value = ["normalizedQuery"]),
        Index(value = ["animeId"])
    ]
)
data class SearchResultRef(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val normalizedQuery: String,
    val animeId: String,
    val rankIndex: Int,
    val fetchTime: Long = System.currentTimeMillis()
)

@Entity(tableName = "watchlist_entries")
data class WatchlistEntry(
    @PrimaryKey val animeId: String,
    val status: String, // "WATCHING", "PLANNING", "COMPLETED", "DROPPED"
    val isFavorite: Boolean = false,
    val userScore: Int? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playback_history",
    indices = [
        Index(value = ["animeId"]),
        Index(value = ["lastPlayedAt"]),
        Index(value = ["animeId", "episodeId"], unique = true)
    ]
)
data class PlaybackHistory(
    @PrimaryKey val id: String, // stable history key for one anime episode
    val animeId: String,
    val episodeId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val completed: Boolean = false,
    @ColumnInfo(defaultValue = "NULL")
    val animeTitle: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val posterUrl: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val mediaType: String? = null,
    val lastPlayedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_preferences")
data class UserPreference(
    @PrimaryKey val prefKey: String,
    val prefValue: String
)

@Entity(
    tableName = "download_entries",
    indices = [
        Index(value = ["animeId"]),
        Index(value = ["downloadStatus"])
    ]
)
data class DownloadEntry(
    @PrimaryKey val id: String, // episodeId
    val animeId: String,
    val animeTitle: String,
    val posterUrl: String? = null,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String? = null,
    val localFilePath: String? = null,
    val downloadStatus: String, // "DOWNLOADING", "PAUSED", "COMPLETED", "FAILED"
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val downloadUrl: String? = null,
    val mediaType: String? = null, // "anime", "movie", "kdrama", "series"
    val errorMessage: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val quality: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val audioLanguage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isCompleted: Boolean get() = downloadStatus == "COMPLETED"
    val isDownloading: Boolean get() = downloadStatus == "DOWNLOADING"
    val isPaused: Boolean get() = downloadStatus == "PAUSED"
    val episodeId: String get() = id
    val progressPercent: Int get() = if (totalBytes > 0L) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
    val formattedSpeed: String get() = "Active"
}

@Entity(tableName = "local_profiles")
data class LocalProfile(
    @PrimaryKey val id: String = "default_user",
    val name: String = "Anime Explorer",
    val avatarId: String = "avatar_pirate",
    val bio: String = "Prophecy Verse Streamer",
    val updatedAt: Long = System.currentTimeMillis()
)
