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
     * Determines whether a title or mediaType denotes a Live Action production.
     */
    fun isLiveAction(title: String?, mediaType: String?): Boolean {
        val normTitle = normalize(title)
        val normMedia = mediaType?.lowercase(Locale.ROOT) ?: ""
        if (normMedia == "live_action" || normMedia == "live action") return true
        if (normTitle.contains("live action") || normTitle.contains("liveaction")) return true
        return false
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

        val aniIsLiveAction = isLiveAction(
            aniListMedia.title?.english ?: aniListMedia.title?.romaji,
            aniListMedia.format
        )
        val prophecyIsLiveAction = isLiveAction(prophecyItem.title, prophecyItem.mediaType)

        // Anime and Live Action must be treated as different media entities!
        // Never substitute an Anime for Live Action or vice versa.
        if (aniIsLiveAction != prophecyIsLiveAction) {
            return 0f
        }

        // Prevent anime TV series from matching movies
        val aniIsMovie = aniListMedia.format?.equals("movie", ignoreCase = true) == true
        val prophecyIsMovie = prophecyItem.mediaType?.equals("movie", ignoreCase = true) == true
        if (aniListMedia.format != null && prophecyItem.mediaType != null && aniIsMovie != prophecyIsMovie) {
            return 0f
        }

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

        if (maxSimilarity < 0.50f) {
            return maxSimilarity
        }

        // Year alignment check
        val aniYear = aniListMedia.startDate?.year ?: aniListMedia.seasonYear
        val prophecyYear = prophecyItem.year?.toIntOrNull()
        if (aniYear != null && prophecyYear != null) {
            val yearDiff = kotlin.math.abs(aniYear - prophecyYear)
            if (yearDiff == 0) {
                maxSimilarity = (maxSimilarity + 0.15f).coerceAtMost(1.0f)
            } else if (yearDiff in 1..2) {
                // Minor year discrepancy (airing window difference)
            } else if (yearDiff in 3..5) {
                maxSimilarity = (maxSimilarity - 0.25f).coerceAtLeast(0f)
            } else {
                // Major year difference (e.g. 1999 Anime vs 2023 Live Action)
                maxSimilarity = (maxSimilarity - 0.60f).coerceAtLeast(0f)
            }
        }

        // Season count check: if candidate has 1-2 seasons but AniList is a 100+ episode long-runner (e.g. One Piece anime with 1000+ episodes vs 8-episode LA series)
        val episodes = aniListMedia.episodes ?: 0
        val seasons = prophecyItem.seasonCount ?: 0
        if (episodes > 100 && seasons in 1..2 && (prophecyYear != null && aniYear != null && kotlin.math.abs(aniYear - prophecyYear) > 3)) {
            maxSimilarity = (maxSimilarity - 0.50f).coerceAtLeast(0f)
        }

        return maxSimilarity
    }

    /**
     * Match confidence between an existing AnimeEntry and a ProphecySearchResultItem.
     */
    fun computeMatchConfidenceForEntry(
        entry: com.example.data.local.AnimeEntry,
        prophecyItem: ProphecySearchResultItem
    ): Float {
        val normProphecy = normalize(prophecyItem.title)
        if (normProphecy.isBlank()) return 0f

        val entryIsLiveAction = isLiveAction(entry.preferredTitle, entry.format)
        val prophecyIsLiveAction = isLiveAction(prophecyItem.title, prophecyItem.mediaType)

        if (entryIsLiveAction != prophecyIsLiveAction) {
            return 0f
        }

        // Prevent anime TV series from matching movies
        val entryIsMovie = entry.format?.equals("movie", ignoreCase = true) == true
        val prophecyIsMovie = prophecyItem.mediaType?.equals("movie", ignoreCase = true) == true
        if (entry.format != null && prophecyItem.mediaType != null && entryIsMovie != prophecyIsMovie) {
            return 0f
        }

        val candidateTitles = buildList {
            add(normalize(entry.preferredTitle))
            entry.titleEnglish?.let { add(normalize(it)) }
            entry.titleRomaji?.let { add(normalize(it)) }
            entry.titleNative?.let { add(normalize(it)) }
        }.filter { it.isNotBlank() }

        var maxSimilarity = 0f
        for (candidate in candidateTitles) {
            val sim = titleSimilarity(candidate, normProphecy)
            if (sim > maxSimilarity) {
                maxSimilarity = sim
            }
        }

        if (maxSimilarity < 0.50f) return maxSimilarity

        val entryYear = entry.year?.toIntOrNull()
        val prophecyYear = prophecyItem.year?.toIntOrNull()
        if (entryYear != null && prophecyYear != null) {
            val yearDiff = kotlin.math.abs(entryYear - prophecyYear)
            if (yearDiff == 0) {
                maxSimilarity = (maxSimilarity + 0.15f).coerceAtMost(1.0f)
            } else if (yearDiff in 1..2) {
                // minor difference
            } else if (yearDiff in 3..5) {
                maxSimilarity = (maxSimilarity - 0.25f).coerceAtLeast(0f)
            } else {
                maxSimilarity = (maxSimilarity - 0.60f).coerceAtLeast(0f)
            }
        }

        val epCount = entry.episodeCount ?: 0
        val seasons = prophecyItem.seasonCount ?: 0
        if (epCount > 100 && seasons in 1..2 && (prophecyYear != null && entryYear != null && kotlin.math.abs(entryYear - prophecyYear) > 3)) {
            maxSimilarity = (maxSimilarity - 0.50f).coerceAtLeast(0f)
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
