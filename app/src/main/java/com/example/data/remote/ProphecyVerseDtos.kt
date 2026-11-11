package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ProphecySearchResponse(
    @Json(name = "results") val results: List<ProphecySearchResultItem> = emptyList(),
    @Json(name = "next_cursor") val nextCursor: String? = null,
    @Json(name = "partial") val partial: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class ProphecySearchResultItem(
    @Json(name = "id") val id: String,
    @Json(name = "canonical_id") val canonicalId: String? = null,
    @Json(name = "title") val title: String,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "year") val year: String? = null,
    @Json(name = "poster_url") val posterUrl: String? = null,
    @Json(name = "season_count") val seasonCount: Int? = null,
    @Json(name = "provider_mappings") val providerMappings: List<ProphecyProviderMappingDto>? = null,
    @Json(name = "title_english") val titleEnglish: String? = null,
    @Json(name = "title_romaji") val titleRomaji: String? = null,
    @Json(name = "title_native") val titleNative: String? = null,
    @Json(name = "alternative_titles") val alternativeTitles: List<String>? = null,
    @Json(name = "banner_url") val bannerUrl: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "episode_count") val episodeCount: Int? = null
)

/** Anime/Episode catalog mapping only; this is not an Episode source-resolution result. */
@JsonClass(generateAdapter = true)
data class ProphecyProviderMappingDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "provider") val provider: String? = null,
    @Json(name = "value") val value: String? = null
)

@JsonClass(generateAdapter = true)
data class ProphecyAnimeDetailsResponse(
    @Json(name = "id") val id: String,
    @Json(name = "canonical_id") val canonicalId: String? = null,
    @Json(name = "title") val title: String,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "year") val year: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "poster_url") val posterUrl: String? = null,
    @Json(name = "genres") val genres: List<String>? = null,
    @Json(name = "seasons") val seasons: List<ProphecySeasonDto>? = null,
    @Json(name = "partial") val partial: Boolean? = null,
    @Json(name = "provider_mappings") val providerMappings: List<ProphecyProviderMappingDto>? = null,
    @Json(name = "title_english") val titleEnglish: String? = null,
    @Json(name = "title_romaji") val titleRomaji: String? = null,
    @Json(name = "title_native") val titleNative: String? = null,
    @Json(name = "alternative_titles") val alternativeTitles: List<String>? = null,
    @Json(name = "banner_url") val bannerUrl: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "duration_minutes") val durationMinutes: Int? = null,
    @Json(name = "season_count") val seasonCount: Int? = null,
    @Json(name = "episode_count") val episodeCount: Int? = null
)

@JsonClass(generateAdapter = true)
data class ProphecySeasonDto(
    @Json(name = "canonical_id") val canonicalId: String? = null,
    @Json(name = "number") val number: Int,
    @Json(name = "episodes") val episodes: List<ProphecyEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class ProphecyEpisodeDto(
    @Json(name = "id") val id: String,
    @Json(name = "canonical_id") val canonicalId: String? = null,
    @Json(name = "provider_mappings") val providerMappings: List<ProphecyProviderMappingDto>? = null,
    // Some nested detail responses omit season; the containing season is authoritative there.
    @Json(name = "season") val season: Int? = null,
    @Json(name = "number") val number: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "overview") val overview: String? = null
)

@JsonClass(generateAdapter = true)
data class ProphecyEpisodeFullResponse(
    @Json(name = "anime") val anime: ProphecyAnimeDetailsResponse? = null,
    @Json(name = "selected_episode") val selectedEpisode: ProphecyEpisodeDto? = null,
    @Json(name = "sources") val sources: List<ProphecySourceDto>? = null,
    @Json(name = "sources_status") val sourcesStatus: String? = null,
    @Json(name = "sources_partial") val sourcesPartial: Boolean? = null,
    @Json(name = "source_error") val sourceError: String? = null
)

@JsonClass(generateAdapter = true)
data class ProphecySourceDto(
    // Some partial responses omit the ID. Preserve the candidate for accurate display, but
    // callers must not send a blank ID to the direct-play endpoint.
    @Json(name = "id") val id: String = "",
    @Json(name = "label") val label: String? = null,
    @Json(name = "quality") val quality: String? = null,
    @Json(name = "codec") val codec: String? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "size_bytes") val sizeBytes: Long? = null,
    @Json(name = "downloadable") val downloadable: Boolean? = null,
    @Json(name = "expires_at") val expiresAt: String? = null
) {
    val hasUsableSourceId: Boolean
        get() = id.isNotBlank()

    /** Stable key for Compose rows that distinguishes same-ID candidates with different metadata. */
    val stableIdentity: String
        get() = listOf(id, label, quality, codec, language, sizeBytes, downloadable, expiresAt)
            .joinToString("") { value ->
                val part = value?.toString().orEmpty()
                "${part.length}:$part"
            }

    /** The backend's existing label is the only provider-like display field in this contract. */
    val displayProviderName: String
        get() = label?.takeIf { it.isNotBlank() } ?: "Unknown provider"

    val displayAudioLanguage: String
        get() = language?.takeIf { it.isNotBlank() } ?: "Unknown"
}

@JsonClass(generateAdapter = true)
data class DirectPlayRequest(
    @Json(name = "source_id") val sourceId: String
)

@JsonClass(generateAdapter = true)
data class DirectPlayResponse(
    @Json(name = "success") val success: Boolean = false,
    @Json(name = "data") val data: DirectPlayData? = null,
    @Json(name = "meta") val meta: DirectPlayMeta? = null
)

@JsonClass(generateAdapter = true)
data class DirectPlayData(
    @Json(name = "mode") val mode: String = "direct",
    @Json(name = "source_id") val sourceId: String,
    @Json(name = "playback_url") val playbackUrl: String,
    @Json(name = "url_type") val urlType: String = "direct",
    @Json(name = "compatibility") val compatibility: String = "verified",
    @Json(name = "requires_headers") val requiresHeaders: Boolean = false,
    @Json(name = "headers") val headers: List<List<String>> = emptyList(),
    @Json(name = "content_type") val contentType: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "message") val message: String? = null
)

@JsonClass(generateAdapter = true)
data class DirectPlayMeta(
    @Json(name = "request_id") val requestId: String? = null,
    @Json(name = "resolution_latency_ms") val resolutionLatencyMs: Long? = null
)
