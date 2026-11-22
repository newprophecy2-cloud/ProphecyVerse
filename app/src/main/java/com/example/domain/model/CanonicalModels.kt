package com.example.domain.model

/**
 * Strictly two canonical verification states per Global Episode Integrity Rules.
 * SyntheticFallback is never represented as a canonical verification status.
 */
enum class CanonicalVerification {
    Verified,
    Unverified
}

data class CanonicalAnime(
    val animeId: String,
    val title: String,
    val alternativeTitles: List<String> = emptyList(),
    val mediaType: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val totalSeasons: Int = 0,
    val verifiedEpisodes: Int = 0,
    val seasonStructureStatus: String? = null,
    val providerCoverage: Map<String, String> = emptyMap()
)

data class CanonicalSeason(
    val seasonId: String,
    val animeId: String,
    val seasonNumber: Int,
    val verifiedEpisodes: Int,
    val advertisedEpisodeCount: Int? = null,
    val structureStatus: String? = null
)

data class CanonicalEpisode(
    val episodeId: String,
    val animeId: String,
    val seasonId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val metadataStatus: String? = null,
    val provenance: String? = null,
    val verification: CanonicalVerification
) {
    val isUserVisible: Boolean
        get() = verification == CanonicalVerification.Verified
}

data class AudioTrackInfo(
    val trackId: String? = null,
    val trackIndex: Int? = null,
    val language: String? = null,
    val languageCode: String? = null,
    val codec: String? = null,
    val channels: String? = null,
    val source: String? = null,
    val verificationStatus: String = "unverified"
)

data class CanonicalSource(
    val sourceId: String,
    val provider: String,
    val streamUrl: String? = null,
    val quality: String? = null,
    val codec: String? = null,
    val audioCodec: String? = null,
    val channels: String? = null,
    val language: String? = null,
    val languageCode: String? = null,
    val declaredLanguage: String? = null,
    val playabilityStatus: String? = null,
    val verificationStatus: String = "unverified",
    val available: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val audioTracks: List<AudioTrackInfo> = emptyList()
)

data class ResolvedStream(
    val episodeId: String,
    val sourceId: String,
    val provider: String,
    val streamUrl: String,
    val quality: String? = null,
    val codec: String? = null,
    val audioCodec: String? = null,
    val language: String? = null,
    val languageCode: String? = null,
    val verificationStatus: String = "unverified",
    val playabilityStatus: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val audioTracks: List<AudioTrackInfo> = emptyList()
)
