package com.example.data.firebase

import com.example.data.remote.ProphecyAnimeDetailsResponse
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecySearchResultItem
import com.example.data.remote.ProphecySeasonDto
import java.security.MessageDigest
import java.util.Locale

private fun normalizeSharedTitle(value: String): String = value.trim().lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

/** Backend-origin catalog metadata only; playback/source data is deliberately not represented. */
data class SharedCatalogMetadata(
    val documentId: String,
    val canonicalId: String?,
    val backendId: String?,
    val contentType: String,
    val schemaVersion: Int = SCHEMA_VERSION,
    val title: String? = null,
    val normalizedTitle: String? = title?.let(::normalizeSharedTitle),
    val searchKeywords: List<String> = listOfNotNull(normalizedTitle),
    val description: String? = null,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val mediaType: String? = null,
    val releaseYear: String? = null,
    val genres: List<String> = emptyList(),
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeDescription: String? = null,
    val parentCanonicalId: String? = null,
    val parentBackendId: String? = null,
    val metadataHash: String
) {
    fun firestoreFields(createdAt: Any, updatedAt: Any, syncedAt: Any): Map<String, Any?> = mapOf(
        "documentId" to documentId, "canonicalId" to canonicalId, "backendId" to backendId,
        "contentType" to contentType, "schemaVersion" to schemaVersion, "metadataHash" to metadataHash,
        "title" to title, "normalizedTitle" to normalizedTitle, "searchKeywords" to searchKeywords,
        "description" to description, "posterUrl" to posterUrl, "bannerUrl" to bannerUrl,
        "mediaType" to mediaType, "releaseYear" to releaseYear, "genres" to genres,
        "seasonNumber" to seasonNumber, "episodeNumber" to episodeNumber, "episodeTitle" to episodeTitle,
        "episodeDescription" to episodeDescription, "parentCanonicalId" to parentCanonicalId,
        "parentBackendId" to parentBackendId, "createdAt" to createdAt, "updatedAt" to updatedAt,
        "lastSyncedAt" to syncedAt, "source" to SOURCE
    ).filterValues { it != null }

    companion object {
        const val SCHEMA_VERSION = 1
        const val SOURCE = "prophecyverse_backend"
        fun normalizeTitle(value: String): String = normalizeSharedTitle(value)
        fun documentIdFor(contentType: String, backendId: String): String {
            val prefix = when (contentType) {
                "ANIME" -> "anime"
                "MOVIE" -> "movie"
                "SEASON" -> "season"
                "EPISODE" -> "episode"
                "GENRE" -> "genre"
                "CATEGORY" -> "category"
                else -> error("Unsupported shared catalog type")
            }
            return stableId(prefix, backendId)
        }
        private fun stableId(type: String, backendId: String): String {
            require(backendId.isNotBlank()) { "Backend ID must not be blank" }
            val bytes = MessageDigest.getInstance("SHA-256").digest(backendId.toByteArray(Charsets.UTF_8))
            return type.lowercase(Locale.ROOT) + "_" + bytes.take(16).joinToString("") { "%02x".format(it) }
        }
        fun hasStableIdentity(contentType: String, backendId: String?, documentId: String): Boolean {
            val id = backendId?.takeIf { it.isNotBlank() } ?: return false
            val prefix = when (contentType) {
                "ANIME" -> "anime"
                "MOVIE" -> "movie"
                "SEASON" -> "season"
                "EPISODE" -> "episode"
                "GENRE" -> "genre"
                "CATEGORY" -> "category"
                else -> return false
            }
            return stableId(prefix, id) == documentId
        }
        private fun hash(vararg values: Any?): String {
            val canonical = values.joinToString("\u001f") { v -> when (v) {
                is List<*> -> v.joinToString("\u001e") { it?.toString().orEmpty() }
                else -> v?.toString().orEmpty()
            } }
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        fun fromSearch(item: ProphecySearchResultItem): SharedCatalogMetadata? {
            val media = item.mediaType?.trim()?.lowercase(Locale.ROOT)
            val kind = when (media) { "anime" -> "anime"; "movie" -> "movie"; else -> return null }
            val type = if (kind == "anime") "ANIME" else "MOVIE"
            val id = stableId(kind, item.id)
            return SharedCatalogMetadata(id, item.canonicalId, item.id, type, title = item.title,
                posterUrl = item.posterUrl, mediaType = media, releaseYear = item.year,
                metadataHash = hash(item.canonicalId, item.id, type, item.title, item.posterUrl, item.year, media))
        }
        fun fromDetail(item: ProphecyAnimeDetailsResponse): SharedCatalogMetadata? {
            val media = item.mediaType?.trim()?.lowercase(Locale.ROOT)
            val kind = when (media) { "anime" -> "anime"; "movie" -> "movie"; else -> return null }
            val type = if (kind == "anime") "ANIME" else "MOVIE"
            val genres = item.genres.orEmpty().distinct().sorted()
            val id = stableId(kind, item.id)
            return SharedCatalogMetadata(id, item.canonicalId, item.id, type, title = item.title,
                description = item.description, posterUrl = item.posterUrl, mediaType = media,
                releaseYear = item.year, genres = genres,
                metadataHash = hash(item.canonicalId, item.id, type, item.title, item.description, item.posterUrl, media, item.year, genres))
        }
        fun fromSeason(parentBackendId: String, parentCanonicalId: String?, season: ProphecySeasonDto): SharedCatalogMetadata? {
            if (parentBackendId.isBlank() || season.number <= 0) return null
            val sourceId = season.canonicalId?.takeIf { it.isNotBlank() } ?: "$parentBackendId:${season.number}"
            val id = stableId("season", sourceId)
            return SharedCatalogMetadata(id, season.canonicalId, sourceId, "SEASON", title = null,
                mediaType = "anime", seasonNumber = season.number, parentCanonicalId = parentCanonicalId,
                parentBackendId = parentBackendId,
                metadataHash = hash(parentCanonicalId, parentBackendId, season.canonicalId, season.number))
        }
        fun fromEpisode(parentBackendId: String, parentCanonicalId: String?, seasonNumber: Int, episode: ProphecyEpisodeDto): SharedCatalogMetadata? {
            if (parentBackendId.isBlank() || episode.id.isBlank() || seasonNumber <= 0 || episode.number <= 0) return null
            val id = stableId("episode", episode.id)
            return SharedCatalogMetadata(id, episode.canonicalId, episode.id, "EPISODE", mediaType = "anime",
                seasonNumber = seasonNumber, episodeNumber = episode.number, episodeTitle = episode.title,
                episodeDescription = episode.overview, parentCanonicalId = parentCanonicalId,
                parentBackendId = parentBackendId,
                metadataHash = hash(parentCanonicalId, parentBackendId, episode.canonicalId, episode.id,
                    seasonNumber, episode.number, episode.title, episode.overview))
        }
        fun fromGenre(backendGenre: String?): SharedCatalogMetadata? {
            val title = backendGenre?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val normalized = title.lowercase(Locale.ROOT)
            val id = stableId("genre", normalized)
            return SharedCatalogMetadata(id, null, normalized, "GENRE", title = title,
                metadataHash = hash(normalized, title))
        }
    }
}
