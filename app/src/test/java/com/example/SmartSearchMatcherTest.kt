package com.example

import com.example.data.repository.TitleMatcher
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
}
