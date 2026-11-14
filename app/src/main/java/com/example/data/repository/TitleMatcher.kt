package com.example.data.repository

import com.example.data.remote.AniListMedia
import com.example.data.remote.ProphecySearchResultItem
import java.text.Normalizer
import java.util.Locale

object TitleMatcher {
    private val DIACRITICS_REGEX = "\\p{InCombiningDiacriticalMarks}+".toRegex()
    private val PUNCTUATION_REGEX = "[\\p{Punct}&&[^_]]".toRegex()
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

    /** Lower-is-better rank: exact/prefix/token matches always beat fuzzy matches. */
    fun rank(query: String, aliases: List<String>): Int {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return Int.MAX_VALUE
        val normalizedAliases = aliases.map(::normalize).filter { it.isNotBlank() }.distinct()
        if (normalizedAliases.any { it == normalizedQuery }) return 0
        if (normalizedAliases.any { it.startsWith(normalizedQuery) }) return 10
        val tokens = normalizedQuery.split(" ").filter { it.isNotBlank() }
        if (normalizedAliases.any { alias -> tokens.all { token -> alias.split(" ").any { it.startsWith(token) } } }) return 20
        if (normalizedAliases.any { it.contains(normalizedQuery) }) return 30
        val similarity = normalizedAliases.maxOfOrNull { titleSimilarity(it, normalizedQuery) } ?: 0f
        return if (similarity >= if (normalizedQuery.length <= 3) 0.75f else if (normalizedQuery.length <= 6) 0.62f else 0.55f) {
            100 + ((1f - similarity) * 100).toInt()
        } else Int.MAX_VALUE
    }

    /**
     * Determines whether a title, mediaType, or year denotes a Live Action production.
     */
    fun isLiveAction(
        title: String?,
        mediaType: String?,
        year: String? = null,
        seasonCount: Int? = null
    ): Boolean {
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
            title = aniListMedia.title?.english ?: aniListMedia.title?.romaji,
            mediaType = aniListMedia.format,
            year = (aniListMedia.startDate?.year ?: aniListMedia.seasonYear)?.toString()
        )
        val prophecyIsLiveAction = isLiveAction(
            title = prophecyItem.title,
            mediaType = prophecyItem.mediaType,
            year = prophecyItem.year,
            seasonCount = prophecyItem.seasonCount
        )

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

        // Season count check: if candidate has 1-2 seasons but AniList is a long-runner or releasing anime
        val isOngoing = aniListMedia.status.equals("RELEASING", ignoreCase = true)
        val episodes = aniListMedia.episodes ?: if (isOngoing) 1000 else 0
        val seasons = prophecyItem.seasonCount ?: 0
        if (episodes > 100 && seasons in 1..2 && (prophecyYear != null && aniYear != null && kotlin.math.abs(aniYear - prophecyYear) > 3)) {
            maxSimilarity = (maxSimilarity - 0.50f).coerceAtLeast(0f)
        }

        return maxSimilarity
    }

    private val EXPLICIT_SEASON_REGEX = Regex("""\b(?:season|series|s)\s*0?([1-9]\d?)\b|\b([1-9]\d?)(?:st|nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE)
    private val ROMAN_SEASON_REGEX = Regex("""(?:^|\s)(ii|iii|iv|v|vi)$""", RegexOption.IGNORE_CASE)
    private val TRAILING_MARKER_REGEX = Regex(
        """\s+(?:season\s*\d+|s\d+|\d+(?:st|nd|rd|th)\s+season|(?:the\s+)?final\s+season|part\s*\d+|cour\s*\d+|ii|iii|iv|v|vi)$""",
        RegexOption.IGNORE_CASE
    )

    fun stripSequelMarkers(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var current = raw.trim()
        val beforeColonOrDash = current
            .replace(Regex("""\s+[-–]\s*[^-–]+[-–]\s*$"""), "")
            .replace(TRAILING_MARKER_REGEX, "")
            .trim()
        if (beforeColonOrDash.length >= 3) current = beforeColonOrDash
        val withoutArc = current.replace(Regex("""\s+[\w']+(?:\s+[\w']+){0,3}\s+arc$""", RegexOption.IGNORE_CASE), "").trim()
        if (withoutArc.length >= 3) current = withoutArc
        val beforeColon = current.substringBefore(':').trim()
        val finalCandidate = if (beforeColon.length >= 4 && current.contains(':')) {
            // Keep longer prefix unless the part after ':' looks like a season/arc subtitle
            val afterColon = current.substringAfter(':').trim()
            if (afterColon.contains("arc", true) || afterColon.contains("season", true) ||
                afterColon.contains("part", true) || afterColon.contains("chapter", true) ||
                beforeColon.split(" ").size >= 2
            ) beforeColon else current
        } else {
            current
        }
        return normalize(finalCandidate)
    }

    fun buildSearchQueryVariants(
        titles: List<String>,
        aliases: List<String> = emptyList(),
        isMovie: Boolean = false,
        canonicalTitle: String? = null
    ): List<String> {
        val out = LinkedHashSet<String>()
        val cleanCanonical = canonicalTitle?.trim()?.takeIf { it.isNotBlank() }
        val all = (titles + aliases).map { it.trim() }.filter { it.isNotBlank() }

        if (!isMovie && cleanCanonical != null) {
            out += cleanCanonical
        }
        all.take(4).forEach { out += it }
        if (!isMovie) {
            for (t in all.take(4)) {
                val stripped = t
                    .replace(Regex("""\s*-\s*[^-]+-\s*$"""), "")
                    .replace(TRAILING_MARKER_REGEX, "")
                    .trim()
                if (stripped.length >= 3 && stripped.lowercase(Locale.ROOT) != t.lowercase(Locale.ROOT)) {
                    out += stripped
                }
                if (t.contains(':')) {
                    val prefix = t.substringBefore(':').trim()
                    if (prefix.length >= 4 && prefix.split(" ").size >= 2) {
                        out += prefix
                    }
                }
            }
        }
        return out.toList().take(6)
    }

    fun extractSeasonHint(titles: List<String>): Int? {
        for (raw in titles) {
            val match = EXPLICIT_SEASON_REGEX.find(raw)
            if (match != null) {
                val num = (match.groupValues[1].takeIf { it.isNotBlank() }
                    ?: match.groupValues[2].takeIf { it.isNotBlank() })?.toIntOrNull()
                if (num != null && num in 1..20) return num
            }
            val norm = normalize(raw)
            ROMAN_SEASON_REGEX.find(norm)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)?.let { roman ->
                return when (roman) {
                    "ii" -> 2
                    "iii" -> 3
                    "iv" -> 4
                    "v" -> 5
                    "vi" -> 6
                    else -> null
                }
            }
        }
        return null
    }

    fun hasSequelMarker(titles: List<String>): Boolean {
        if (extractSeasonHint(titles) != null) return true
        return titles.any { raw ->
            val lower = raw.lowercase(Locale.ROOT)
            lower.contains("final season") || lower.contains("part 2") || lower.contains("cour 2") ||
                lower.contains(" arc") || Regex("""-[^-]+-\s*$""").containsMatchIn(raw)
        }
    }

    /**
     * Match confidence between an existing AnimeEntry and a ProphecySearchResultItem.
     */
    fun computeMatchConfidenceForEntry(
        entry: com.example.data.local.AnimeEntry,
        prophecyItem: ProphecySearchResultItem,
        aliases: List<String> = emptyList()
    ): Float {
        val prophecyTitles = buildList {
            add(normalize(prophecyItem.title))
            prophecyItem.titleEnglish?.let { add(normalize(it)) }
            prophecyItem.titleRomaji?.let { add(normalize(it)) }
            prophecyItem.titleNative?.let { add(normalize(it)) }
            prophecyItem.alternativeTitles?.forEach { add(normalize(it)) }
        }.filter { it.isNotBlank() }.distinct()
        if (prophecyTitles.isEmpty()) return 0f

        val entryIsLiveAction = isLiveAction(entry.preferredTitle, entry.format, entry.year)
        val prophecyIsLiveAction = isLiveAction(
            title = prophecyItem.title,
            mediaType = prophecyItem.mediaType,
            year = prophecyItem.year,
            seasonCount = prophecyItem.seasonCount
        )

        if (entryIsLiveAction != prophecyIsLiveAction) {
            return 0f
        }

        // Prevent anime TV series from matching movies
        val entryIsMovie = entry.mediaCategory.equals("MOVIE", ignoreCase = true) ||
            entry.format?.equals("movie", ignoreCase = true) == true
        val prophecyIsMovie = prophecyItem.mediaType?.equals("movie", ignoreCase = true) == true
        if ((entryIsMovie || entry.format != null) && prophecyItem.mediaType != null && entryIsMovie != prophecyIsMovie) {
            return 0f
        }

        val rawEntryTitles = buildList {
            entry.canonicalTitle?.let { add(it) }
            add(entry.preferredTitle)
            entry.titleEnglish?.let { add(it) }
            entry.titleRomaji?.let { add(it) }
            entry.titleNative?.let { add(it) }
            aliases.forEach { add(it) }
        }.filter { it.isNotBlank() }.distinct()

        val candidateTitles = rawEntryTitles.map(::normalize).filter { it.isNotBlank() }.distinct()
        val entryYear = entry.year?.toIntOrNull()
        val prophecyYear = prophecyItem.year?.toIntOrNull()
        val hasCanonicalSequel = !entryIsMovie &&
            (entry.canonicalAnimeId != null || (entry.canonicalSeasonNumber ?: 1) > 1)
        val isUnifiedSeriesMatch = !entryIsMovie && !prophecyIsMovie &&
            (hasCanonicalSequel || (prophecyItem.seasonCount ?: 0) >= 2 || hasSequelMarker(rawEntryTitles)) &&
            (entryYear == null || prophecyYear == null || entryYear >= prophecyYear || hasCanonicalSequel)

        var maxSimilarity = 0f
        for (candidate in candidateTitles) {
            for (pTitle in prophecyTitles) {
                val sim = titleSimilarity(candidate, pTitle)
                if (sim > maxSimilarity) {
                    maxSimilarity = sim
                }
            }
        }

        if (isUnifiedSeriesMatch && maxSimilarity < 0.90f) {
            val strippedEntryTitles = rawEntryTitles.map(::stripSequelMarkers).filter { it.isNotBlank() }.distinct()
            val strippedProphecyTitles = buildList {
                add(prophecyItem.title)
                prophecyItem.titleEnglish?.let { add(it) }
                prophecyItem.titleRomaji?.let { add(it) }
            }.map(::stripSequelMarkers).filter { it.isNotBlank() }.distinct()

            for (sEntry in strippedEntryTitles) {
                for (pTitle in prophecyTitles + strippedProphecyTitles) {
                    val sim = if (sEntry == pTitle) 0.92f else titleSimilarity(sEntry, pTitle) * 0.92f
                    if (sim > maxSimilarity) {
                        maxSimilarity = sim
                    }
                }
            }
        }

        if (maxSimilarity < 0.50f) return maxSimilarity

        if (entryYear != null && prophecyYear != null) {
            val yearDiff = kotlin.math.abs(entryYear - prophecyYear)
            if (yearDiff == 0) {
                maxSimilarity = (maxSimilarity + 0.15f).coerceAtMost(1.0f)
            } else if (isUnifiedSeriesMatch && (entryYear > prophecyYear || hasCanonicalSequel)) {
                // Multi-season unified backend entries carry the Season 1 premiere year
                maxSimilarity = (maxSimilarity + 0.05f).coerceAtMost(1.0f)
            } else if (yearDiff in 1..2) {
                // minor difference
            } else if (yearDiff in 3..5) {
                maxSimilarity = (maxSimilarity - 0.25f).coerceAtLeast(0f)
            } else {
                maxSimilarity = (maxSimilarity - 0.60f).coerceAtLeast(0f)
            }
        }

        val isReleasing = entry.status.equals("RELEASING", ignoreCase = true)
        val epCount = entry.episodeCount ?: if (isReleasing) 1000 else 0
        val seasons = prophecyItem.seasonCount ?: 0
        if (!isUnifiedSeriesMatch && epCount > 100 && seasons in 1..2 &&
            (prophecyYear != null && entryYear != null && kotlin.math.abs(entryYear - prophecyYear) > 3)
        ) {
            maxSimilarity = (maxSimilarity - 0.50f).coerceAtLeast(0f)
        }

        return maxSimilarity
    }

    /**
     * Ranks valid backend search candidates (confidence >= 0.75) so unified multi-season
     * and multi-provider entries decisively outrank single-provider addon duplicates.
     */
    fun scoreBackendCandidateForEntry(
        entry: com.example.data.local.AnimeEntry,
        prophecyItem: ProphecySearchResultItem,
        aliases: List<String> = emptyList()
    ): Pair<Float, Float> {
        val baseConfidence = computeMatchConfidenceForEntry(entry, prophecyItem, aliases)
        if (baseConfidence < 0.75f) return baseConfidence to 0f

        var selectionScore = baseConfidence
        val isMovie = entry.mediaCategory.equals("MOVIE", ignoreCase = true) ||
            entry.format.equals("MOVIE", ignoreCase = true)
        val seasonCount = prophecyItem.seasonCount ?: 0
        val providerCount = prophecyItem.providerMappings?.size ?: 0
        val canonicalAnilist = entry.canonicalAnimeId ?: entry.aniListId
        if (canonicalAnilist != null && prophecyItem.canonicalId == "anilist_$canonicalAnilist") {
            selectionScore += 0.08f
        }
        if (!isMovie) {
            if (seasonCount >= 2) {
                selectionScore += 0.12f + (seasonCount.coerceAtMost(10) * 0.005f)
            } else if (seasonCount == 1 && (entry.canonicalSeasonNumber ?: 1) <= 1) {
                selectionScore += 0.02f
            }
        }
        if (providerCount > 1) {
            selectionScore += 0.05f + (providerCount.coerceAtMost(5) * 0.005f)
        }
        return baseConfidence to selectionScore
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

        val distance = levenshtein(s1, s2)
        val editSimilarity = 1f - distance.toFloat() / maxOf(s1.length, s2.length)
        return maxOf(jaccard, editSimilarity)
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            for (j in right.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (left[i] == right[j]) 0 else 1
                )
            }
            previous = current
        }
        return previous[right.length]
    }
}
