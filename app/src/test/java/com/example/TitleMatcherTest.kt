package com.example

import com.example.data.remote.AniListCoverImage
import com.example.data.remote.AniListFuzzyDate
import com.example.data.remote.AniListMedia
import com.example.data.remote.AniListTitle
import com.example.data.remote.ProphecySearchResultItem
import com.example.data.repository.TitleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleMatcherTest {

    @Test
    fun normalize_removesPunctuationAndDiacritics() {
        val input = "Shingeki no Kyojin: The Final Season - Kanketsu-hen!"
        val normalized = TitleMatcher.normalize(input)
        assertEquals("shingeki no kyojin the final season kanketsu hen", normalized)

        val withDiacritics = "Pokémon: Mezase Pokémon Master"
        val normDiacritics = TitleMatcher.normalize(withDiacritics)
        assertEquals("pokemon mezase pokemon master", normDiacritics)
    }

    @Test
    fun computeMatchConfidence_exactMatchReturnsHighConfidence() {
        val aniListMedia = AniListMedia(
            id = 20,
            title = AniListTitle(
                romaji = "NARUTO",
                english = "Naruto",
                native = "NARUTO -ナルト-"
            ),
            startDate = AniListFuzzyDate(year = 2002)
        )

        val prophecyItem = ProphecySearchResultItem(
            id = "backend_naruto_1",
            title = "Naruto",
            year = "2002"
        )

        val confidence = TitleMatcher.computeMatchConfidence(aniListMedia, prophecyItem)
        assertTrue("Confidence should be >= 0.90 for exact match with year", confidence >= 0.90f)
    }

    @Test
    fun computeMatchConfidence_synonymMatchSucceeds() {
        val aniListMedia = AniListMedia(
            id = 16498,
            title = AniListTitle(
                romaji = "Shingeki no Kyojin",
                english = "Attack on Titan"
            ),
            synonyms = listOf("SnK", "AOT"),
            startDate = AniListFuzzyDate(year = 2013)
        )

        val prophecyItem = ProphecySearchResultItem(
            id = "backend_aot_1",
            title = "Attack on Titan",
            year = "2013"
        )

        val confidence = TitleMatcher.computeMatchConfidence(aniListMedia, prophecyItem)
        assertTrue("Confidence should be high for English title match", confidence >= 0.85f)
    }

    @Test
    fun computeMatchConfidence_unrelatedTitlesReturnLowConfidence() {
        val aniListMedia = AniListMedia(
            id = 1,
            title = AniListTitle(english = "Cowboy Bebop"),
            startDate = AniListFuzzyDate(year = 1998)
        )

        val prophecyItem = ProphecySearchResultItem(
            id = "backend_naruto",
            title = "Naruto Shippuden",
            year = "2007"
        )

        val confidence = TitleMatcher.computeMatchConfidence(aniListMedia, prophecyItem)
        assertTrue("Unrelated titles must have low confidence", confidence < 0.50f)
    }
}
