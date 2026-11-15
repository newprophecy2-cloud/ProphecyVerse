package com.example.data.d1

import com.example.data.local.AnimeAlias
import com.example.data.local.AnimeEntry
import com.example.data.local.CatalogMapping
import com.example.data.local.EpisodeEntry
import com.example.data.local.SeasonEntry
import com.example.data.local.StreamContainerEntity
import com.example.data.local.StreamEpisodeEntity
import com.example.data.local.StreamProviderEntity
import com.example.data.local.StreamSeasonEntity
import com.example.data.local.StreamSourceEntity
import com.example.data.streaming.ContentIdentityHelper
import com.example.data.streaming.StreamRecordStatus
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonQualifier
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi

@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER
)
@Retention(AnnotationRetention.RUNTIME)
@JsonQualifier
annotation class D1Boolean

/**
 * Handles SQLite boolean storage in Cloudflare D1 where flags come back as 0/1 integers,
 * true/false JSON booleans, "0"/"1" strings, or null, supporting both non-null Boolean and Boolean?.
 */
object D1BooleanAdapterFactory : JsonAdapter.Factory {
    override fun create(
        type: java.lang.reflect.Type,
        annotations: MutableSet<out Annotation>,
        moshi: Moshi
    ): JsonAdapter<*>? {
        if (annotations.none { it is D1Boolean }) return null
        val raw = com.squareup.moshi.Types.getRawType(type)
        if (raw != Boolean::class.javaPrimitiveType && raw != java.lang.Boolean::class.java) {
            return null
        }
        val isPrimitive = (type == Boolean::class.javaPrimitiveType)
        return object : JsonAdapter<Boolean?>() {
            override fun fromJson(reader: JsonReader): Boolean? {
                return when (reader.peek()) {
                    JsonReader.Token.NULL -> {
                        reader.nextNull<Unit>()
                        if (isPrimitive) false else null
                    }
                    JsonReader.Token.BOOLEAN -> reader.nextBoolean()
                    JsonReader.Token.NUMBER -> reader.nextDouble() != 0.0
                    JsonReader.Token.STRING -> {
                        when (reader.nextString().trim().lowercase()) {
                            "1", "true", "yes" -> true
                            "0", "false", "no", "" -> false
                            else -> if (isPrimitive) false else null
                        }
                    }
                    else -> {
                        reader.skipValue()
                        if (isPrimitive) false else null
                    }
                }
            }

            override fun toJson(writer: JsonWriter, value: Boolean?) {
                if (value == null) {
                    if (isPrimitive) writer.value(0) else writer.nullValue()
                } else {
                    writer.value(if (value) 1 else 0)
                }
            }
        }
    }
}

data class D1TablesResponse(
    val success: Boolean = false,
    val count: Int = 0,
    val tables: List<String> = emptyList()
)

data class D1ListResponse<T>(
    val success: Boolean = false,
    val table: String? = null,
    val total: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val count: Int? = null,
    val data: List<T>? = null
)

data class D1RecordResponse<T>(
    val success: Boolean = false,
    val message: String? = null,
    val table: String? = null,
    val data: T? = null,
    val changes: Int? = null
)

data class D1ErrorResponse(
    val success: Boolean? = null,
    val error: String? = null
)

data class D1AnimeEntryDto(
    val id: String,
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
    val genres: String = "",
    val score: Double? = null,
    val episodeCount: Int? = null,
    val duration: Int? = null,
    val studios: String? = null,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    @D1Boolean val isPlayable: Boolean = false,
    @D1Boolean val isTrending: Boolean = false,
    @D1Boolean val isSeasonal: Boolean = false,
    @D1Boolean val isPopular: Boolean = false,
    val mediaCategory: String = "ANIME",
    val metadataCompleteness: String = "UNKNOWN",
    val canonicalAnimeId: Int? = null,
    val canonicalTitle: String? = null,
    val canonicalSeasonNumber: Int? = null,
    val cumulativeEpisodeOffset: Int? = null,
    val updatedAt: Long
) {
    fun stableDigest(): String = listOf(
        id, aniListId, backendAnimeId, titleRomaji, titleEnglish, titleNative,
        preferredTitle, synopsis, year, season, format, status, genres, score,
        episodeCount, duration, studios, posterUrl, bannerUrl, isPlayable,
        isTrending, isSeasonal, isPopular, mediaCategory, metadataCompleteness,
        canonicalAnimeId, canonicalTitle, canonicalSeasonNumber, cumulativeEpisodeOffset
    ).joinToString("|") { it?.toString().orEmpty() }

    fun toRoomEntity(existingLocal: AnimeEntry? = null): AnimeEntry {
        return AnimeEntry(
            id = id,
            aniListId = aniListId ?: existingLocal?.aniListId,
            backendAnimeId = backendAnimeId ?: existingLocal?.backendAnimeId,
            canonicalAnimeId = canonicalAnimeId ?: existingLocal?.canonicalAnimeId,
            canonicalTitle = canonicalTitle ?: existingLocal?.canonicalTitle,
            canonicalSeasonNumber = canonicalSeasonNumber ?: existingLocal?.canonicalSeasonNumber,
            cumulativeEpisodeOffset = cumulativeEpisodeOffset ?: existingLocal?.cumulativeEpisodeOffset,
            titleRomaji = titleRomaji ?: existingLocal?.titleRomaji,
            titleEnglish = titleEnglish ?: existingLocal?.titleEnglish,
            titleNative = titleNative ?: existingLocal?.titleNative,
            preferredTitle = preferredTitle,
            synopsis = synopsis ?: existingLocal?.synopsis,
            year = year ?: existingLocal?.year,
            season = season ?: existingLocal?.season,
            format = format ?: existingLocal?.format,
            status = status ?: existingLocal?.status,
            genres = genres.ifBlank { existingLocal?.genres.orEmpty() },
            score = score ?: existingLocal?.score,
            episodeCount = episodeCount ?: existingLocal?.episodeCount,
            duration = duration ?: existingLocal?.duration,
            studios = studios ?: existingLocal?.studios,
            posterUrl = posterUrl ?: existingLocal?.posterUrl,
            bannerUrl = bannerUrl ?: existingLocal?.bannerUrl,
            isPlayable = isPlayable || (existingLocal?.isPlayable == true),
            isTrending = isTrending,
            isSeasonal = isSeasonal,
            isPopular = isPopular,
            mediaCategory = mediaCategory.ifBlank { existingLocal?.mediaCategory ?: "ANIME" },
            metadataCompleteness = metadataCompleteness.ifBlank { existingLocal?.metadataCompleteness ?: "UNKNOWN" },
            updatedAt = maxOf(updatedAt, existingLocal?.updatedAt ?: 0L)
        )
    }
}

data class D1AnimeAliasDto(
    val aliasId: Long? = null,
    val animeId: String,
    val alias: String,
    val originalAlias: String
)

data class D1AnimeAliasInsertDto(
    val animeId: String,
    val alias: String,
    val originalAlias: String
)

data class D1CatalogMappingDto(
    val aniListId: Int,
    val backendAnimeId: String,
    val confidence: Float,
    val mappedAt: Long
) {
    fun stableDigest(): String = "$aniListId|$backendAnimeId|$confidence"
    fun toRoomEntity(): CatalogMapping = CatalogMapping(
        aniListId = aniListId,
        backendAnimeId = backendAnimeId,
        confidence = confidence,
        mappedAt = mappedAt
    )
}

data class D1SeasonEntryDto(
    val id: String,
    val animeId: String,
    val seasonNumber: Int,
    val backendSeasonId: String? = null,
    val title: String? = null
) {
    fun stableDigest(): String = "$id|$animeId|$seasonNumber|${backendSeasonId.orEmpty()}|${title.orEmpty()}"
    fun toRoomEntity(): SeasonEntry = SeasonEntry(
        id = id,
        animeId = animeId,
        seasonNumber = seasonNumber,
        backendSeasonId = backendSeasonId,
        title = title
    )
}

data class D1EpisodeEntryDto(
    val id: String,
    val animeId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String? = null,
    val overview: String? = null,
    @D1Boolean val isAvailable: Boolean = true,
    val sourceEpisodeIds: String? = null
) {
    fun stableDigest(): String =
        "$id|$animeId|$seasonNumber|$episodeNumber|${title.orEmpty()}|${overview.orEmpty()}|$isAvailable|${sourceEpisodeIds.orEmpty()}"

    fun toRoomEntity(): EpisodeEntry = EpisodeEntry(
        id = id,
        animeId = animeId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        title = title,
        overview = overview,
        isAvailable = isAvailable,
        sourceEpisodeIds = sourceEpisodeIds
    )
}

data class D1StreamContainerDto(
    val scopeKey: String,
    val contentKey: String,
    val anilistId: Int? = null,
    val contentType: String,
    val backendId: String? = null,
    val canonicalTitle: String? = null,
    val seasonCount: Int? = null,
    val totalEpisodeCount: Int? = null,
    val providerCount: Int = 0,
    val sourceCount: Int = 0,
    val discoveryStatus: String = "NOT_DISCOVERED",
    val version: Long = 1L,
    val updatedAt: Long,
    val deletedAt: Long? = null
) {
    fun stableDigest(): String = listOf(
        scopeKey, contentKey, anilistId, contentType, backendId, canonicalTitle,
        seasonCount, totalEpisodeCount, providerCount, sourceCount, discoveryStatus, deletedAt
    ).joinToString("|") { it?.toString().orEmpty() }

    fun toRoomEntity(): StreamContainerEntity = StreamContainerEntity(
        scopeKey = scopeKey,
        contentKey = contentKey,
        anilistId = anilistId,
        contentType = contentType,
        backendId = backendId,
        canonicalTitle = canonicalTitle,
        seasonCount = seasonCount,
        totalEpisodeCount = totalEpisodeCount,
        providerCount = providerCount,
        sourceCount = sourceCount,
        discoveryStatus = discoveryStatus,
        version = version,
        updatedAt = updatedAt,
        deletedAt = deletedAt
    )
}

data class D1StreamSeasonDto(
    val seasonDocKey: String,
    val contentKey: String,
    val seasonId: String,
    val seasonNumber: Int,
    val backendSeasonId: String? = null,
    val episodeCount: Int = 0,
    val discoveryStatus: String = "NOT_DISCOVERED",
    val version: Long = 1L,
    val updatedAt: Long,
    val deletedAt: Long? = null
) {
    fun stableDigest(): String = listOf(
        seasonDocKey, contentKey, seasonId, seasonNumber, backendSeasonId,
        episodeCount, discoveryStatus, deletedAt
    ).joinToString("|") { it?.toString().orEmpty() }

    fun toRoomEntity(): StreamSeasonEntity = StreamSeasonEntity(
        seasonDocKey = seasonDocKey,
        contentKey = contentKey,
        seasonId = seasonId,
        seasonNumber = seasonNumber,
        backendSeasonId = backendSeasonId,
        episodeCount = episodeCount,
        discoveryStatus = discoveryStatus,
        version = version,
        updatedAt = updatedAt,
        deletedAt = deletedAt
    )
}

data class D1StreamEpisodeDto(
    val episodeDocKey: String,
    val contentKey: String,
    val seasonId: String,
    val episodeId: String,
    val catalogEpisodeId: String? = null,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val backendEpisodeId: String? = null,
    val backendSourceEpisodeIds: String? = null,
    val providerCount: Int = 0,
    val sourceCount: Int = 0,
    val discoveryStatus: String = "NOT_DISCOVERED",
    val version: Long = 1L,
    val updatedAt: Long,
    val deletedAt: Long? = null
) {
    fun stableDigest(): String = listOf(
        episodeDocKey, contentKey, seasonId, episodeId, catalogEpisodeId,
        seasonNumber, episodeNumber, backendEpisodeId, backendSourceEpisodeIds,
        providerCount, sourceCount, discoveryStatus, deletedAt
    ).joinToString("|") { it?.toString().orEmpty() }

    fun toRoomEntity(): StreamEpisodeEntity = StreamEpisodeEntity(
        episodeDocKey = episodeDocKey,
        contentKey = contentKey,
        seasonId = seasonId,
        episodeId = episodeId,
        catalogEpisodeId = catalogEpisodeId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        backendEpisodeId = backendEpisodeId,
        backendSourceEpisodeIds = backendSourceEpisodeIds,
        providerCount = providerCount,
        sourceCount = sourceCount,
        discoveryStatus = discoveryStatus,
        version = version,
        updatedAt = updatedAt,
        deletedAt = deletedAt
    )
}

data class D1StreamProviderDto(
    val providerDocKey: String,
    val scopeKey: String,
    val providerId: String,
    val providerName: String,
    val status: String = "AVAILABLE",
    val sourceCount: Int = 0,
    val version: Long = 1L,
    val updatedAt: Long,
    val deletedAt: Long? = null
) {
    fun stableDigest(): String = listOf(
        providerDocKey, scopeKey, providerId, providerName, status, sourceCount, deletedAt
    ).joinToString("|") { it?.toString().orEmpty() }

    fun toRoomEntity(): StreamProviderEntity = StreamProviderEntity(
        providerDocKey = providerDocKey,
        scopeKey = scopeKey,
        providerId = providerId,
        providerName = providerName,
        status = status,
        sourceCount = sourceCount,
        version = version,
        updatedAt = updatedAt,
        deletedAt = deletedAt
    )
}

data class D1StreamSourceDto(
    val sourceDocKey: String,
    val scopeKey: String,
    val providerId: String,
    val sourceId: String,
    val sourceName: String,
    val backendEpisodeId: String? = null,
    val streamUrl: String? = null,
    val urlType: String = "direct",
    val contentType: String? = null,
    val quality: String? = null,
    val codec: String? = null,
    val audioLanguage: String? = null,
    @D1Boolean val downloadable: Boolean? = null,
    val sizeBytes: Long? = null,
    val safeHeadersJson: String? = null,
    val expiresAt: String? = null,
    val status: String = "AVAILABLE",
    val version: Long = 1L,
    val lastVerifiedAt: Long? = null,
    val lastPlaybackSuccessAt: Long? = null,
    val updatedAt: Long,
    val deletedAt: Long? = null
) {
    fun stableDigest(): String = listOf(
        sourceDocKey, scopeKey, providerId, sourceId, sourceName, backendEpisodeId,
        urlType, contentType, quality, codec, audioLanguage, downloadable, sizeBytes,
        status, deletedAt
    ).joinToString("|") { it?.toString().orEmpty() }

    /**
     * Converts shared D1 metadata into a Room StreamSourceEntity.
     * Note: temporary playback URLs and headers from shared D1 are never trusted as live playable
     * URLs unless verified locally; D1 primarily supplies source discovery metadata.
     */
    fun toRoomEntity(existingLocal: StreamSourceEntity? = null): StreamSourceEntity {
        val localValidUrl = existingLocal?.streamUrl
        val localHeaders = existingLocal?.safeHeadersJson
        val localStatus = when {
            deletedAt != null || status == StreamRecordStatus.REMOVED.name -> StreamRecordStatus.REMOVED.name
            !localValidUrl.isNullOrBlank() && existingLocal.status == StreamRecordStatus.AVAILABLE.name -> StreamRecordStatus.AVAILABLE.name
            !localValidUrl.isNullOrBlank() && existingLocal.status == StreamRecordStatus.EXPIRED.name -> StreamRecordStatus.EXPIRED.name
            else -> StreamRecordStatus.MISSING_URL.name
        }
        return StreamSourceEntity(
            sourceDocKey = sourceDocKey,
            scopeKey = scopeKey,
            providerId = providerId,
            sourceId = sourceId,
            sourceName = sourceName,
            backendEpisodeId = backendEpisodeId ?: existingLocal?.backendEpisodeId,
            streamUrl = localValidUrl,
            urlType = urlType.ifBlank { existingLocal?.urlType ?: "direct" },
            contentType = contentType ?: existingLocal?.contentType,
            quality = quality ?: existingLocal?.quality,
            codec = codec ?: existingLocal?.codec,
            audioLanguage = audioLanguage ?: existingLocal?.audioLanguage,
            downloadable = downloadable ?: existingLocal?.downloadable,
            sizeBytes = sizeBytes ?: existingLocal?.sizeBytes,
            safeHeadersJson = localHeaders,
            expiresAt = existingLocal?.expiresAt ?: expiresAt,
            status = localStatus,
            version = maxOf(version, existingLocal?.version ?: 1L),
            lastVerifiedAt = lastVerifiedAt ?: existingLocal?.lastVerifiedAt,
            lastPlaybackSuccessAt = lastPlaybackSuccessAt ?: existingLocal?.lastPlaybackSuccessAt,
            updatedAt = maxOf(updatedAt, existingLocal?.updatedAt ?: 0L),
            deletedAt = deletedAt
        )
    }
}

fun AnimeEntry.toD1Dto(): D1AnimeEntryDto = D1AnimeEntryDto(
    id = id,
    aniListId = aniListId,
    backendAnimeId = backendAnimeId,
    titleRomaji = titleRomaji,
    titleEnglish = titleEnglish,
    titleNative = titleNative,
    preferredTitle = preferredTitle,
    synopsis = synopsis,
    year = year,
    season = season,
    format = format,
    status = status,
    genres = genres,
    score = score,
    episodeCount = episodeCount,
    duration = duration,
    studios = studios,
    posterUrl = posterUrl,
    bannerUrl = bannerUrl,
    isPlayable = isPlayable,
    isTrending = isTrending,
    isSeasonal = isSeasonal,
    isPopular = isPopular,
    mediaCategory = mediaCategory,
    metadataCompleteness = metadataCompleteness,
    canonicalAnimeId = canonicalAnimeId,
    canonicalTitle = canonicalTitle,
    canonicalSeasonNumber = canonicalSeasonNumber,
    cumulativeEpisodeOffset = cumulativeEpisodeOffset,
    updatedAt = updatedAt
)

fun AnimeAlias.toD1InsertDto(): D1AnimeAliasInsertDto = D1AnimeAliasInsertDto(
    animeId = animeId,
    alias = alias,
    originalAlias = originalAlias
)

fun CatalogMapping.toD1Dto(): D1CatalogMappingDto = D1CatalogMappingDto(
    aniListId = aniListId,
    backendAnimeId = backendAnimeId,
    confidence = confidence,
    mappedAt = mappedAt
)

fun SeasonEntry.toD1Dto(): D1SeasonEntryDto = D1SeasonEntryDto(
    id = id,
    animeId = animeId,
    seasonNumber = seasonNumber,
    backendSeasonId = backendSeasonId,
    title = title
)

fun EpisodeEntry.toD1Dto(): D1EpisodeEntryDto = D1EpisodeEntryDto(
    id = id,
    animeId = animeId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    title = title,
    overview = overview,
    isAvailable = isAvailable,
    sourceEpisodeIds = sourceEpisodeIds
)

fun StreamContainerEntity.toD1Dto(): D1StreamContainerDto = D1StreamContainerDto(
    scopeKey = scopeKey,
    contentKey = contentKey,
    anilistId = anilistId,
    contentType = contentType,
    backendId = backendId,
    canonicalTitle = canonicalTitle,
    seasonCount = seasonCount,
    totalEpisodeCount = totalEpisodeCount,
    providerCount = providerCount,
    sourceCount = sourceCount,
    discoveryStatus = discoveryStatus,
    version = version,
    updatedAt = updatedAt,
    deletedAt = deletedAt
)

fun StreamSeasonEntity.toD1Dto(): D1StreamSeasonDto = D1StreamSeasonDto(
    seasonDocKey = seasonDocKey,
    contentKey = contentKey,
    seasonId = seasonId,
    seasonNumber = seasonNumber,
    backendSeasonId = backendSeasonId,
    episodeCount = episodeCount,
    discoveryStatus = discoveryStatus,
    version = version,
    updatedAt = updatedAt,
    deletedAt = deletedAt
)

fun StreamEpisodeEntity.toD1Dto(): D1StreamEpisodeDto = D1StreamEpisodeDto(
    episodeDocKey = episodeDocKey,
    contentKey = contentKey,
    seasonId = seasonId,
    episodeId = episodeId,
    catalogEpisodeId = catalogEpisodeId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    backendEpisodeId = backendEpisodeId,
    backendSourceEpisodeIds = backendSourceEpisodeIds,
    providerCount = providerCount,
    sourceCount = sourceCount,
    discoveryStatus = discoveryStatus,
    version = version,
    updatedAt = updatedAt,
    deletedAt = deletedAt
)

fun StreamProviderEntity.toD1Dto(): D1StreamProviderDto = D1StreamProviderDto(
    providerDocKey = providerDocKey,
    scopeKey = scopeKey,
    providerId = providerId,
    providerName = providerName,
    status = status,
    sourceCount = sourceCount,
    version = version,
    updatedAt = updatedAt,
    deletedAt = deletedAt
)

/**
 * Strips temporary signed playback URLs, volatile expiry timestamps, and playback headers when serializing
 * shared source discovery metadata for D1. D1 stores source capability/discovery metadata only.
 */
fun StreamSourceEntity.toD1MetadataDto(): D1StreamSourceDto = D1StreamSourceDto(
    sourceDocKey = sourceDocKey,
    scopeKey = scopeKey,
    providerId = providerId,
    sourceId = sourceId,
    sourceName = sourceName,
    backendEpisodeId = backendEpisodeId,
    streamUrl = null,
    urlType = urlType,
    contentType = contentType,
    quality = quality,
    codec = codec,
    audioLanguage = audioLanguage,
    downloadable = downloadable,
    sizeBytes = sizeBytes,
    safeHeadersJson = null,
    expiresAt = null,
    status = when {
        deletedAt != null || status == StreamRecordStatus.REMOVED.name -> StreamRecordStatus.REMOVED.name
        status.isNotBlank() -> status
        else -> StreamRecordStatus.MISSING_URL.name
    },
    version = version,
    lastVerifiedAt = lastVerifiedAt,
    lastPlaybackSuccessAt = lastPlaybackSuccessAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt
)

/** Builds canonical container D1 scopeKey helper matching ContentIdentityHelper. */
fun buildContainerD1ScopeKey(contentKey: String, isMovie: Boolean): String =
    ContentIdentityHelper.containerScopeKey(contentKey, isMovie)
