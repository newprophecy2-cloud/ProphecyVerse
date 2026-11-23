package com.example.data.mapper

import com.example.data.remote.DirectPlayData
import com.example.data.remote.ProphecyAnimeDetailsResponse
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecyProviderMappingDto
import com.example.data.remote.ProphecySearchResponse
import com.example.data.remote.ProphecySearchResultItem
import com.example.data.remote.ProphecySeasonDto
import com.example.data.remote.ProphecySourceDto
import com.example.data.remote.V3AnimeDetailsResponse
import com.example.data.remote.V3AudioTrackDto
import com.example.data.remote.V3EpisodeDto
import com.example.data.remote.V3ResolveResponse
import com.example.data.remote.V3SearchResponse
import com.example.data.remote.V3SeasonDto
import com.example.data.remote.V3SourceItemDto
import com.example.data.remote.V3SourcesResponse
import com.example.domain.model.AudioTrackInfo
import com.example.domain.model.CanonicalAnime
import com.example.domain.model.CanonicalEpisode
import com.example.domain.model.CanonicalSeason
import com.example.domain.model.CanonicalSource
import com.example.domain.model.CanonicalVerification
import com.example.domain.model.ResolvedStream

object V3Mappers {

    /**
     * Global Episode Integrity Rule:
     * Only explicit "verified" maps to CanonicalVerification.Verified.
     * Unknown, missing, or "SyntheticFallback" never upgrades to Verified.
     */
    fun parseCanonicalVerification(raw: String?): CanonicalVerification =
        if (raw?.trim()?.equals("verified", ignoreCase = true) == true) {
            CanonicalVerification.Verified
        } else {
            CanonicalVerification.Unverified
        }

    fun toDomainAnime(dto: V3AnimeDetailsResponse): CanonicalAnime = CanonicalAnime(
        animeId = dto.animeId,
        title = dto.title,
        alternativeTitles = dto.alternativeTitles.orEmpty(),
        mediaType = dto.mediaType,
        year = dto.year,
        posterUrl = dto.posterUrl,
        description = dto.description,
        genres = dto.genres.orEmpty(),
        totalSeasons = dto.totalSeasons ?: 0,
        verifiedEpisodes = (dto.verifiedEpisodes ?: 0).coerceAtLeast(0),
        seasonStructureStatus = dto.seasonStructureStatus,
        providerCoverage = dto.providerCoverage.orEmpty()
    )

    fun toDomainSeason(animeId: String, dto: V3SeasonDto): CanonicalSeason = CanonicalSeason(
        seasonId = dto.seasonId,
        animeId = animeId,
        seasonNumber = dto.seasonNumber,
        verifiedEpisodes = (dto.verifiedEpisodes ?: dto.episodeCount ?: 0).coerceAtLeast(0),
        advertisedEpisodeCount = dto.advertisedEpisodeCount,
        structureStatus = dto.structureStatus
    )

    /**
     * Maps V3 episodes and defensively filters out any non-Verified episodes
     * so unverified/synthetic records never enter user-facing episode lists.
     */
    fun toVerifiedDomainEpisodes(
        animeId: String,
        seasonId: String,
        seasonNumber: Int,
        dtos: List<V3EpisodeDto>
    ): List<CanonicalEpisode> = dtos
        .asSequence()
        .filter { it.episodeId.isNotBlank() && it.episodeNumber > 0 }
        .filter { parseCanonicalVerification(it.verification) == CanonicalVerification.Verified }
        .sortedBy { it.episodeNumber }
        .map { dto ->
            CanonicalEpisode(
                episodeId = dto.episodeId,
                animeId = animeId,
                seasonId = seasonId,
                seasonNumber = seasonNumber,
                episodeNumber = dto.episodeNumber,
                title = dto.title?.takeIf { it.isNotBlank() } ?: "Episode ${dto.episodeNumber}",
                metadataStatus = dto.metadataStatus,
                provenance = dto.provenance,
                verification = CanonicalVerification.Verified
            )
        }
        .toList()

    fun toDomainSources(resp: V3SourcesResponse): List<CanonicalSource> =
        resp.providers.flatMap { group ->
            group.sources.map { item -> toDomainSource(group.provider, item) }
        }

    fun toDomainSource(groupProvider: String, item: V3SourceItemDto): CanonicalSource = CanonicalSource(
        sourceId = item.sourceId,
        provider = item.provider?.takeIf { it.isNotBlank() } ?: groupProvider,
        streamUrl = item.streamUrl,
        quality = item.quality,
        codec = item.codec,
        audioCodec = item.audioCodec,
        channels = item.channels,
        language = item.language,
        languageCode = item.languageCode,
        declaredLanguage = item.declaredLanguage,
        playabilityStatus = item.playabilityStatus ?: item.mediaMetadata?.playabilityStatus,
        verificationStatus = item.verificationStatus ?: "unverified",
        available = item.available == true,
        headers = item.headers.orEmpty(),
        audioTracks = item.audioTracks.orEmpty().map(::toAudioTrackInfo)
    )

    fun toAudioTrackInfo(dto: V3AudioTrackDto): AudioTrackInfo = AudioTrackInfo(
        trackId = dto.trackId,
        trackIndex = dto.trackIndex,
        language = dto.language ?: dto.declaredLanguage,
        languageCode = dto.languageCode,
        codec = dto.codec,
        channels = dto.channels,
        source = dto.source,
        verificationStatus = dto.verificationStatus ?: "unverified"
    )

    fun toResolvedStream(episodeId: String, resp: V3ResolveResponse): ResolvedStream = ResolvedStream(
        episodeId = resp.episodeId ?: episodeId,
        sourceId = resp.sourceId,
        provider = resp.provider ?: "ProphecyVerse",
        streamUrl = resp.streamUrl,
        quality = resp.quality,
        codec = resp.codec,
        audioCodec = resp.audioCodec,
        language = resp.language,
        languageCode = resp.languageCode,
        verificationStatus = resp.verificationStatus ?: "unverified",
        playabilityStatus = resp.playabilityStatus,
        headers = resp.headers.orEmpty(),
        audioTracks = resp.audioTracks.orEmpty().map(::toAudioTrackInfo)
    )

    // --- Repository Compatibility Adapters ---

    fun toCompatSearchResponse(v3: V3SearchResponse): ProphecySearchResponse = ProphecySearchResponse(
        results = v3.results.map { item ->
            ProphecySearchResultItem(
                id = item.animeId,
                canonicalId = item.animeId,
                title = item.title,
                mediaType = item.mediaType,
                year = item.year?.toString(),
                posterUrl = item.posterUrl,
                seasonCount = item.totalSeasons,
                providerMappings = item.providerIds?.entries?.map { (k, v) ->
                    ProphecyProviderMappingDto(id = v, provider = k, value = v)
                },
                episodeCount = item.verifiedEpisodes
            )
        },
        nextCursor = null,
        partial = v3.partial
    )

    fun toCompatDetailsResponse(
        details: V3AnimeDetailsResponse,
        seasonsWithEpisodes: List<Pair<V3SeasonDto, List<V3EpisodeDto>>>
    ): ProphecyAnimeDetailsResponse = ProphecyAnimeDetailsResponse(
        id = details.animeId,
        canonicalId = details.animeId,
        title = details.title,
        mediaType = details.mediaType,
        year = details.year?.toString(),
        description = details.description,
        posterUrl = details.posterUrl,
        genres = details.genres,
        seasons = if (seasonsWithEpisodes.isEmpty() && !details.primaryEpisodeId.isNullOrBlank()) {
            listOf(
                ProphecySeasonDto(
                    id = "${details.animeId}_movie",
                    canonicalId = "${details.animeId}_movie",
                    number = 1,
                    title = "Movie",
                    episodes = listOf(
                        ProphecyEpisodeDto(
                            id = details.primaryEpisodeId,
                            canonicalId = details.primaryEpisodeId,
                            season = 1,
                            number = 1,
                            title = "Movie",
                            overview = null
                        )
                    )
                )
            )
        } else {
            seasonsWithEpisodes.map { (season, eps) ->
                val verifiedEps = eps.filter {
                    it.episodeId.isNotBlank() &&
                        it.episodeNumber > 0 &&
                        parseCanonicalVerification(it.verification) == CanonicalVerification.Verified
                }
                ProphecySeasonDto(
                    id = season.seasonId,
                    canonicalId = season.seasonId,
                    number = season.seasonNumber,
                    title = "Season ${season.seasonNumber}",
                    episodes = verifiedEps.map { ep ->
                        ProphecyEpisodeDto(
                            id = ep.episodeId,
                            canonicalId = ep.episodeId,
                            season = season.seasonNumber,
                            number = ep.episodeNumber,
                            title = ep.title,
                            overview = null
                        )
                    }
                )
            }
        },
        partial = details.seasonStructureStatus?.equals("partial", ignoreCase = true) ?: false,
        alternativeTitles = details.alternativeTitles,
        seasonCount = details.totalSeasons,
        episodeCount = details.verifiedEpisodes
    )

    fun toCompatSourceDtos(resp: V3SourcesResponse): List<ProphecySourceDto> =
        resp.providers.flatMap { group ->
            group.sources.filter { it.available == true }.map { item ->
                val providerName = item.provider?.takeIf { it.isNotBlank() } ?: group.provider
                val lang = item.language ?: item.declaredLanguage
                ProphecySourceDto(
                    id = item.sourceId,
                    label = providerName,
                    quality = item.quality,
                    codec = item.codec,
                    language = lang,
                    sizeBytes = item.providerMetadata?.sizeBytes,
                    downloadable = item.streamUrl?.let { !it.contains(".m3u8") && !it.contains(".mpd") },
                    expiresAt = null
                )
            }
        }

    fun toCompatDirectPlayData(resp: V3ResolveResponse): DirectPlayData {
        val lower = resp.streamUrl.lowercase()
        val urlType = when {
            lower.contains(".mpd") -> "dash"
            lower.contains(".m3u8") -> "hls"
            else -> "direct"
        }
        val headerList = resp.headers.orEmpty().entries.map { (k, v) -> listOf(k, v) }
        return DirectPlayData(
            mode = "direct",
            sourceId = resp.sourceId,
            playbackUrl = resp.streamUrl,
            urlType = urlType,
            compatibility = resp.playabilityStatus ?: "manifest_validated",
            requiresHeaders = headerList.isNotEmpty(),
            headers = headerList,
            contentType = resp.contentType ?: when {
                urlType == "dash" -> "application/dash+xml"
                urlType == "hls" -> "application/x-mpegURL"
                lower.contains(".mkv") -> "video/x-matroska"
                lower.contains(".webm") -> "video/webm"
                else -> "video/mp4"
            },
            expiresAt = null,
            message = resp.verificationStatus
        )
    }
}
