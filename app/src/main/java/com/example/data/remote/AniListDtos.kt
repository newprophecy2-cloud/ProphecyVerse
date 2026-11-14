package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AniListGraphQLRequest(
    @Json(name = "query") val query: String,
    @Json(name = "variables") val variables: Map<String, Any?> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class AniListGraphQLResponse(
    @Json(name = "data") val data: AniListData? = null,
    @Json(name = "errors") val errors: List<AniListGraphQLError>? = null
)

@JsonClass(generateAdapter = true)
data class AniListGraphQLError(
    @Json(name = "message") val message: String? = null,
    @Json(name = "status") val status: Int? = null
)

@JsonClass(generateAdapter = true)
data class AniListData(
    @Json(name = "Page") val page: AniListPage? = null,
    @Json(name = "trending") val trending: AniListPage? = null,
    @Json(name = "seasonal") val seasonal: AniListPage? = null,
    @Json(name = "popular") val popular: AniListPage? = null,
    @Json(name = "movies") val movies: AniListPage? = null,
    @Json(name = "Media") val media: AniListMedia? = null
)

@JsonClass(generateAdapter = true)
data class AniListPage(
    @Json(name = "pageInfo") val pageInfo: AniListPageInfo? = null,
    @Json(name = "media") val media: List<AniListMedia> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AniListPageInfo(
    @Json(name = "total") val total: Int? = null,
    @Json(name = "perPage") val perPage: Int? = null,
    @Json(name = "currentPage") val currentPage: Int? = null,
    @Json(name = "lastPage") val lastPage: Int? = null,
    @Json(name = "hasNextPage") val hasNextPage: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class AniListMedia(
    @Json(name = "id") val id: Int,
    @Json(name = "idMal") val idMal: Int? = null,
    @Json(name = "title") val title: AniListTitle? = null,
    @Json(name = "synonyms") val synonyms: List<String>? = null,
    @Json(name = "coverImage") val coverImage: AniListCoverImage? = null,
    @Json(name = "bannerImage") val bannerImage: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "format") val format: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "season") val season: String? = null,
    @Json(name = "seasonYear") val seasonYear: Int? = null,
    @Json(name = "startDate") val startDate: AniListFuzzyDate? = null,
    @Json(name = "episodes") val episodes: Int? = null,
    @Json(name = "duration") val duration: Int? = null,
    @Json(name = "genres") val genres: List<String>? = null,
    @Json(name = "averageScore") val averageScore: Int? = null,
    @Json(name = "studios") val studios: AniListStudiosConnection? = null,
    @Json(name = "nextAiringEpisode") val nextAiringEpisode: AniListAiringScheduleNode? = null,
    @Json(name = "streamingEpisodes") val streamingEpisodes: List<AniListStreamingEpisode>? = null,
    @Json(name = "relations") val relations: AniListRelationsConnection? = null
)

@JsonClass(generateAdapter = true)
data class AniListRelationsConnection(
    @Json(name = "edges") val edges: List<AniListRelationEdge>? = null
)

@JsonClass(generateAdapter = true)
data class AniListRelationEdge(
    @Json(name = "relationType") val relationType: String? = null,
    @Json(name = "node") val node: AniListRelatedMediaNode? = null
)

@JsonClass(generateAdapter = true)
data class AniListRelatedMediaNode(
    @Json(name = "id") val id: Int,
    @Json(name = "type") val type: String? = null,
    @Json(name = "title") val title: AniListTitle? = null,
    @Json(name = "format") val format: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "seasonYear") val seasonYear: Int? = null,
    @Json(name = "startDate") val startDate: AniListFuzzyDate? = null,
    @Json(name = "episodes") val episodes: Int? = null,
    @Json(name = "nextAiringEpisode") val nextAiringEpisode: AniListAiringScheduleNode? = null
)

@JsonClass(generateAdapter = true)
data class AniListAiringScheduleNode(
    @Json(name = "episode") val episode: Int? = null,
    @Json(name = "airingAt") val airingAt: Long? = null,
    @Json(name = "timeUntilAiring") val timeUntilAiring: Long? = null
)

@JsonClass(generateAdapter = true)
data class AniListStreamingEpisode(
    @Json(name = "title") val title: String? = null,
    @Json(name = "thumbnail") val thumbnail: String? = null,
    @Json(name = "url") val url: String? = null,
    @Json(name = "site") val site: String? = null
)

@JsonClass(generateAdapter = true)
data class AniListTitle(
    @Json(name = "romaji") val romaji: String? = null,
    @Json(name = "english") val english: String? = null,
    @Json(name = "native") val native: String? = null
)

@JsonClass(generateAdapter = true)
data class AniListCoverImage(
    @Json(name = "large") val large: String? = null,
    @Json(name = "medium") val medium: String? = null
)

@JsonClass(generateAdapter = true)
data class AniListFuzzyDate(
    @Json(name = "year") val year: Int? = null,
    @Json(name = "month") val month: Int? = null,
    @Json(name = "day") val day: Int? = null
)

@JsonClass(generateAdapter = true)
data class AniListStudiosConnection(
    @Json(name = "nodes") val nodes: List<AniListStudioNode>? = null
)

@JsonClass(generateAdapter = true)
data class AniListStudioNode(
    @Json(name = "name") val name: String? = null
)
