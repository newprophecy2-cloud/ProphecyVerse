package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class V3SearchResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "session_id") val sessionId: String? = null,
    @Json(name = "query") val query: String? = null,
    @Json(name = "total") val total: Int? = null,
    @Json(name = "partial") val partial: Boolean? = null,
    @Json(name = "provider_coverage") val providerCoverage: Map<String, String>? = null,
    @Json(name = "results") val results: List<V3SearchResultItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class V3SearchResultItem(
    @Json(name = "anime_id") val animeId: String,
    @Json(name = "title") val title: String,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "poster_url") val posterUrl: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "genres") val genres: List<String>? = null,
    @Json(name = "total_seasons") val totalSeasons: Int? = null,
    @Json(name = "total_episodes") val totalEpisodes: Int? = null,
    @Json(name = "verified_episodes") val verifiedEpisodes: Int? = null,
    @Json(name = "unverified_episodes") val unverifiedEpisodes: Int? = null,
    @Json(name = "counts_status") val countsStatus: String? = null,
    @Json(name = "provider_ids") val providerIds: Map<String, String>? = null,
    @Json(name = "provider_variants") val providerVariants: List<V3ProviderVariantDto>? = null
)

@JsonClass(generateAdapter = true)
data class V3ProviderVariantDto(
    @Json(name = "provider_id") val providerId: String? = null,
    @Json(name = "provider_name") val providerName: String? = null,
    @Json(name = "provider_anime_id") val providerAnimeId: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "declared_language") val declaredLanguage: String? = null,
    @Json(name = "declared_language_code") val declaredLanguageCode: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "media_type") val mediaType: String? = null
)

@JsonClass(generateAdapter = true)
data class V3AnimeDetailsResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "anime_id") val animeId: String,
    @Json(name = "title") val title: String,
    @Json(name = "alternative_titles") val alternativeTitles: List<String>? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "poster_url") val posterUrl: String? = null,
    @Json(name = "genres") val genres: List<String>? = null,
    @Json(name = "total_seasons") val totalSeasons: Int? = null,
    @Json(name = "total_episodes") val totalEpisodes: Int? = null,
    @Json(name = "verified_episodes") val verifiedEpisodes: Int? = null,
    @Json(name = "unverified_episodes") val unverifiedEpisodes: Int? = null,
    @Json(name = "season_structure_status") val seasonStructureStatus: String? = null,
    @Json(name = "provider_coverage") val providerCoverage: Map<String, String>? = null,
    @Json(name = "provider_variants") val providerVariants: List<V3ProviderVariantDto>? = null
)

@JsonClass(generateAdapter = true)
data class V3SeasonsResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "anime_id") val animeId: String,
    @Json(name = "total_seasons") val totalSeasons: Int? = null,
    @Json(name = "total_episodes") val totalEpisodes: Int? = null,
    @Json(name = "verified_episodes") val verifiedEpisodes: Int? = null,
    @Json(name = "unverified_episodes") val unverifiedEpisodes: Int? = null,
    @Json(name = "season_structure_status") val seasonStructureStatus: String? = null,
    @Json(name = "seasons") val seasons: List<V3SeasonDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class V3SeasonDto(
    @Json(name = "season_id") val seasonId: String,
    @Json(name = "season_number") val seasonNumber: Int,
    @Json(name = "episode_count") val episodeCount: Int? = null,
    @Json(name = "verified_episodes") val verifiedEpisodes: Int? = null,
    @Json(name = "unverified_episodes") val unverifiedEpisodes: Int? = null,
    @Json(name = "advertised_episode_count") val advertisedEpisodeCount: Int? = null,
    @Json(name = "structure_status") val structureStatus: String? = null
)

@JsonClass(generateAdapter = true)
data class V3EpisodesResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "season_id") val seasonId: String,
    @Json(name = "total_episodes") val totalEpisodes: Int? = null,
    @Json(name = "verified_episodes") val verifiedEpisodes: Int? = null,
    @Json(name = "unverified_episodes") val unverifiedEpisodes: Int? = null,
    @Json(name = "structure_status") val structureStatus: String? = null,
    @Json(name = "episodes") val episodes: List<V3EpisodeDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class V3EpisodeDto(
    @Json(name = "episode_id") val episodeId: String,
    @Json(name = "episode_number") val episodeNumber: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "metadata_status") val metadataStatus: String? = null,
    @Json(name = "provenance") val provenance: String? = null,
    @Json(name = "verification") val verification: String? = null
)

@JsonClass(generateAdapter = true)
data class V3SourcesResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "episode_id") val episodeId: String,
    @Json(name = "total_sources") val totalSources: Int? = null,
    @Json(name = "verified_sources") val verifiedSources: Int? = null,
    @Json(name = "excluded_sources") val excludedSources: Int? = null,
    @Json(name = "available_languages") val availableLanguages: List<String>? = null,
    @Json(name = "declared_languages") val declaredLanguages: List<String>? = null,
    @Json(name = "language_verification_status") val languageVerificationStatus: String? = null,
    @Json(name = "provider_coverage") val providerCoverage: Map<String, String>? = null,
    @Json(name = "provider_details") val providerDetails: List<V3ProviderCoverageDetailDto>? = null,
    @Json(name = "providers") val providers: List<V3ProviderSourceGroupDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class V3ProviderCoverageDetailDto(
    @Json(name = "provider") val provider: String,
    @Json(name = "request_status") val requestStatus: String? = null,
    @Json(name = "source_count") val sourceCount: Int? = null,
    @Json(name = "validated_source_count") val validatedSourceCount: Int? = null,
    @Json(name = "fallback_attempted") val fallbackAttempted: Boolean? = null,
    @Json(name = "fallback_status") val fallbackStatus: String? = null
)

@JsonClass(generateAdapter = true)
data class V3ProviderSourceGroupDto(
    @Json(name = "provider") val provider: String,
    @Json(name = "sources") val sources: List<V3SourceItemDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class V3AudioTrackDto(
    @Json(name = "track_id") val trackId: String? = null,
    @Json(name = "track_index") val trackIndex: Int? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "language_code") val languageCode: String? = null,
    @Json(name = "declared_language") val declaredLanguage: String? = null,
    @Json(name = "codec") val codec: String? = null,
    @Json(name = "channels") val channels: String? = null,
    @Json(name = "source") val source: String? = null,
    @Json(name = "verification_status") val verificationStatus: String? = null
)

@JsonClass(generateAdapter = true)
data class V3ProviderMetadataDto(
    @Json(name = "provider_id") val providerId: String? = null,
    @Json(name = "provider_source_id") val providerSourceId: String? = null,
    @Json(name = "size_bytes") val sizeBytes: Long? = null
)

@JsonClass(generateAdapter = true)
data class V3MediaMetadataDto(
    @Json(name = "http_reachable") val httpReachable: Boolean? = null,
    @Json(name = "http_status_code") val httpStatusCode: Int? = null,
    @Json(name = "manifest_validated") val manifestValidated: Boolean? = null,
    @Json(name = "media_probe_validated") val mediaProbeValidated: Boolean? = null,
    @Json(name = "playability_status") val playabilityStatus: String? = null,
    @Json(name = "device_playback_status") val devicePlaybackStatus: String? = null
)

@JsonClass(generateAdapter = true)
data class V3SourceItemDto(
    @Json(name = "source_id") val sourceId: String,
    @Json(name = "provider") val provider: String? = null,
    @Json(name = "stream_url") val streamUrl: String? = null,
    @Json(name = "quality") val quality: String? = null,
    @Json(name = "codec") val codec: String? = null,
    @Json(name = "audio_codec") val audioCodec: String? = null,
    @Json(name = "channels") val channels: String? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "language_code") val languageCode: String? = null,
    @Json(name = "declared_language") val declaredLanguage: String? = null,
    @Json(name = "verification_status") val verificationStatus: String? = null,
    @Json(name = "playability_status") val playabilityStatus: String? = null,
    @Json(name = "content_type") val contentType: String? = null,
    @Json(name = "audio_tracks") val audioTracks: List<V3AudioTrackDto>? = null,
    @Json(name = "provider_metadata") val providerMetadata: V3ProviderMetadataDto? = null,
    @Json(name = "media_metadata") val mediaMetadata: V3MediaMetadataDto? = null,
    @Json(name = "headers") val headers: Map<String, String>? = null,
    @Json(name = "availability_status") val availabilityStatus: String? = null,
    @Json(name = "available") val available: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class V3ResolveRequest(
    @Json(name = "source_id") val sourceId: String,
    @Json(name = "track_index") val trackIndex: Int? = null,
    @Json(name = "language") val language: String? = null
)

@JsonClass(generateAdapter = true)
data class V3ResolveResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "episode_id") val episodeId: String? = null,
    @Json(name = "source_id") val sourceId: String,
    @Json(name = "provider") val provider: String? = null,
    @Json(name = "stream_url") val streamUrl: String,
    @Json(name = "quality") val quality: String? = null,
    @Json(name = "codec") val codec: String? = null,
    @Json(name = "audio_codec") val audioCodec: String? = null,
    @Json(name = "channels") val channels: String? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "language_code") val languageCode: String? = null,
    @Json(name = "verification_status") val verificationStatus: String? = null,
    @Json(name = "playability_status") val playabilityStatus: String? = null,
    @Json(name = "content_type") val contentType: String? = null,
    @Json(name = "audio_tracks") val audioTracks: List<V3AudioTrackDto>? = null,
    @Json(name = "headers") val headers: Map<String, String>? = null
)
