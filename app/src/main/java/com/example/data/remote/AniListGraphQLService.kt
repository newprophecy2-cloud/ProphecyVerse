package com.example.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface AniListGraphQLService {
    @Headers(
        "Content-Type: application/json",
        "Accept: application/json",
        "User-Agent: ProphecyVerse/1.0 (Android)"
    )
    @POST("/")
    suspend fun postQuery(
        @Body request: AniListGraphQLRequest
    ): Response<AniListGraphQLResponse>
}

object AniListQueries {
    const val SEARCH_ANIME = """
        query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
          Page(page: ${'$'}page, perPage: ${'$'}perPage) {
            media(search: ${'$'}search, type: ANIME, isAdult: false) {
              id
              title {
                romaji
                english
                native
              }
              synonyms
              coverImage {
                large
                medium
              }
              bannerImage
              description(asHtml: false)
              format
              status
              season
              seasonYear
              startDate {
                year
              }
              episodes
              duration
              genres
              averageScore
              studios(isMain: true) {
                nodes {
                  name
                }
              }
            }
          }
        }
    """

    const val DISCOVERY_FEEDS = """
        query (${'$'}season: MediaSeason, ${'$'}seasonYear: Int) {
          trending: Page(page: 1, perPage: 15) {
            media(sort: TRENDING_DESC, type: ANIME, isAdult: false) {
              id
              title {
                romaji
                english
                native
              }
              synonyms
              coverImage {
                large
                medium
              }
              bannerImage
              description(asHtml: false)
              format
              status
              season
              seasonYear
              startDate {
                year
              }
              episodes
              duration
              genres
              averageScore
              studios(isMain: true) {
                nodes {
                  name
                }
              }
            }
          }
          seasonal: Page(page: 1, perPage: 15) {
            media(season: ${'$'}season, seasonYear: ${'$'}seasonYear, sort: POPULARITY_DESC, type: ANIME, isAdult: false) {
              id
              title {
                romaji
                english
                native
              }
              synonyms
              coverImage {
                large
                medium
              }
              bannerImage
              description(asHtml: false)
              format
              status
              season
              seasonYear
              startDate {
                year
              }
              episodes
              duration
              genres
              averageScore
              studios(isMain: true) {
                nodes {
                  name
                }
              }
            }
          }
          popular: Page(page: 1, perPage: 15) {
            media(sort: POPULARITY_DESC, type: ANIME, isAdult: false) {
              id
              title {
                romaji
                english
                native
              }
              synonyms
              coverImage {
                large
                medium
              }
              bannerImage
              description(asHtml: false)
              format
              status
              season
              seasonYear
              startDate {
                year
              }
              episodes
              duration
              genres
              averageScore
              studios(isMain: true) {
                nodes {
                  name
                }
              }
            }
          }
        }
    """

    const val MEDIA_DETAILS = """
        query (${'$'}id: Int) {
          Media(id: ${'$'}id, type: ANIME) {
            id
            title {
              romaji
              english
              native
            }
            synonyms
            coverImage {
              large
              medium
            }
            bannerImage
            description(asHtml: false)
            format
            status
            season
            seasonYear
            startDate {
              year
            }
            episodes
            duration
            genres
            averageScore
            studios(isMain: true) {
              nodes {
                name
              }
            }
          }
        }
    """
}
