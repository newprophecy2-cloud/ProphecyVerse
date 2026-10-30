package com.example.data.repository

import com.example.data.remote.AniListMedia
import com.example.data.remote.ProphecySearchResultItem
import java.text.Normalizer
import java.util.Locale

object TitleMatcher {
    private val DIACRITICS_REGEX = "\\p{InCombiningDiacriticalMarks}+".toRegex()
    private val PUNCTUATION_REGEX = "[^a-zA-Z0-9\\s]".toRegex()
    private val WHITESPACE_REGEX = "\\s+".toRegex()

    fun normalize(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val withoutDiacritics = DIACRITICS_REGEX.replace(decomposed, "")
        val withoutPunctuation = PUNCTUATION_REGEX.replace(withoutDiacritics, " ")
        return WHITESPACE_REGEX.replace(withoutPunctuation, " ")
            .trim()
            .lowercase(Locale.ROOT)
    }

    /**
     * Computes match confidence between an AniList entry and a ProphecyVerse catalog item.
     * Returns confidence in [0.0, 1.0].
     */
    fun computeMatchConfidence(
        aniListMedia: AniListMedia,
        prophecyItem: ProphecySearchResultItem
    ): Float {
        val normProphecy = normalize(prophecyItem.title)
        if (normProphecy.isBlank()) return 0f

        val candidateTitles = buildList {
            aniListMedia.title?.english?.let { add(normalize(it)) }
            aniListMedia.title?.romaji?.let { add(normalize(it)) }
            aniListMedia.title?.native?.let { add(normalize(it)) }
            aniListMedia.synonyms?.forEach { add(normalize(it)) }
        }.filter { it.isNotBlank() }

        var maxSimilarity = 0f
        for (candidate in candidateTitles) {
            val sim = titleSimilarity(candidate, normProphecy)
            if (sim > maxSimilarity) {
                maxSimilarity = sim
            }
        }

        // Year alignment check
        val aniYear = aniListMedia.startDate?.year ?: aniListMedia.seasonYear
        val prophecyYear = prophecyItem.year?.toIntOrNull()
        if (aniYear != null && prophecyYear != null) {
            val yearDiff = kotlin.math.abs(aniYear - prophecyYear)
            if (yearDiff == 0) {
                maxSimilarity = (maxSimilarity + 0.1f).coerceAtMost(1.0f)
            } else if (yearDiff > 2 && maxSimilarity < 0.95f) {
                maxSimilarity = (maxSimilarity - 0.2f).coerceAtLeast(0f)
            }
        }

        return maxSimilarity
    }

    private fun titleSimilarity(s1: String, s2: String): Float {
        if (s1 == s2) return 1.0f
        if (s1.isEmpty() || s2.isEmpty()) return 0f

        // Token Jaccard similarity
        val words1 = s1.split(" ").filter { it.length > 1 }.toSet()
        val words2 = s2.split(" ").filter { it.length > 1 }.toSet()

        if (words1.isEmpty() || words2.isEmpty()) {
            return if (s1.contains(s2) || s2.contains(s1)) 0.7f else 0f
        }

        val intersection = words1.intersect(words2).size.toFloat()
        val union = words1.union(words2).size.toFloat()
        val jaccard = intersection / union

        if (words1 == words2) return 1.0f
        if (s1.contains(s2) || s2.contains(s1)) {
            return maxOf(jaccard, 0.85f)
        }

        return jaccard
    }
}
