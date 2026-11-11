package com.example

import com.example.data.repository.TitleMatcher
import com.example.data.repository.uniqueAniListMedia
import com.example.data.remote.AniListMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSearchMatcherTest {
    private val naruto = listOf("Naruto", "Naruto Shippuden", "ナルト")
    private val demonSlayer = listOf("Demon Slayer", "Kimetsu no Yaiba", "鬼滅の刃")

    @Test fun exactAndPrefixBeatFuzzyMatches() {
        assertTrue(TitleMatcher.rank("naruto", naruto) < TitleMatcher.rank("narutto", naruto))
        assertTrue(TitleMatcher.rank("nar", naruto) < TitleMatcher.rank("bleach", naruto))
    }

    @Test fun aliasesNativeAndMultiWordQueriesMatch() {
        assertTrue(TitleMatcher.rank("kimetsu no yaiba", demonSlayer) < Int.MAX_VALUE)
        assertTrue(TitleMatcher.rank("鬼滅", demonSlayer) < Int.MAX_VALUE)
        assertTrue(TitleMatcher.rank("demon sl", demonSlayer) < Int.MAX_VALUE)
    }

    @Test fun normalizationPreservesNonLatinLetters() {
        assertTrue(TitleMatcher.normalize(" 鬼滅の刃 ") == "鬼滅の刃")
    }

    @Test fun liveAniListResultsAreDeduplicatedByAniListMediaId() {
        val results = uniqueAniListMedia(listOf(AniListMedia(id = 23), AniListMedia(id = 23), AniListMedia(id = 24)))
        assertEquals(listOf(23, 24), results.map { it.id })
    }
}
