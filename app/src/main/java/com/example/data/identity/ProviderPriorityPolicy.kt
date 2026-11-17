package com.example.data.identity

import com.example.data.local.EpisodeEntry
import com.example.data.local.SeasonEntry
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecySearchResultItem
import com.example.data.remote.ProphecySeasonDto
import com.example.data.remote.ProphecySourceDto
import com.example.data.repository.TitleMatcher
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Defines provider mapping authority and playback fallback priority.
 *
 * 4KHDHUB is the sole authority for canonical season structure, season numbering,
 * episode numbering, canonical episode ordering, and canonical episode count.
 * Provider playback fallback priority is configurable (default: 4KHDHUB -> Dramachi -> future providers)
 * without ever transferring canonical mapping authority away from 4KHDHUB.
 */
class ProviderPriorityPolicy(
    priorityOrder: List<String> = DEFAULT_PRIORITY_ORDER
) {
    val priorityOrder: List<String> = priorityOrder
        .map { normalizeProviderName(it) }
        .filter { it.isNotBlank() && it != PROVIDER_UNKNOWN }
        .distinct()
        .ifEmpty { DEFAULT_PRIORITY_ORDER }

    fun tierRank(rawProvider: String?): Int {
        val normalized = normalizeProviderName(rawProvider)
        if (normalized == PROVIDER_UNKNOWN) return UNKNOWN_PROVIDER_TIER
        val idx = priorityOrder.indexOf(normalized)
        return if (idx >= 0) idx else priorityOrder.size + 10
    }

    fun tierRankForSource(dto: ProphecySourceDto): Int {
        val identified = identifyProviderFromSource(dto)
        return tierRank(identified)
    }

    private fun identifyConfiguredProvider(raw: String?): String? {
        identifyProviderFromToken(raw)?.let { return it }
        identifyProviderFromText(raw)?.let { return it }
        val compact = raw?.trim()?.lowercase(Locale.ROOT)
            ?.replace(Regex("""[\s_-]+"""), "")
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return priorityOrder.firstOrNull { configured ->
            configured.length >= 3 && compact.contains(configured.replace("_", ""))
        }
    }

    fun identifyProviderFromSource(dto: ProphecySourceDto): String {
        identifyConfiguredProvider(dto.id)?.let { return it }
        identifyConfiguredProvider(dto.label)?.let { return it }
        return PROVIDER_UNKNOWN
    }

    fun identifyProviderFromSearchItem(item: ProphecySearchResultItem): String {
        val mapped = item.providerMappings.orEmpty()
            .mapNotNull { mapping ->
                normalizeProviderName(mapping.provider).takeIf { it != PROVIDER_UNKNOWN }
                    ?: identifyConfiguredProvider(mapping.id)
                    ?: identifyConfiguredProvider(mapping.value)
            }
        if (mapped.isNotEmpty()) {
            return mapped.minBy { tierRank(it) }
        }
        return identifyConfiguredProvider(item.id)
            ?: PROVIDER_UNKNOWN
    }

    fun allProvidersForSearchItem(item: ProphecySearchResultItem): Set<String> {
        val result = linkedSetOf<String>()
        item.providerMappings.orEmpty().forEach { mapping ->
            normalizeProviderName(mapping.provider).takeIf { it != PROVIDER_UNKNOWN }?.let(result::add)
            identifyConfiguredProvider(mapping.id)?.let(result::add)
            identifyConfiguredProvider(mapping.value)?.let(result::add)
        }
        identifyConfiguredProvider(item.id)?.let(result::add)
        return result
    }

    fun identifyProviderFromEpisode(
        ep: ProphecyEpisodeDto,
        fallbackProvider: String? = null
    ): String {
        val fromMappings = ep.providerMappings.orEmpty()
            .mapNotNull { mapping ->
                normalizeProviderName(mapping.provider).takeIf { it != PROVIDER_UNKNOWN }
                    ?: identifyConfiguredProvider(mapping.id)
                    ?: identifyConfiguredProvider(mapping.value)
            }
            .minByOrNull { tierRank(it) }
        if (fromMappings != null) return fromMappings
        identifyProviderFromToken(ep.id)?.let { return it }
        identifyProviderFromPrefixOrMarker(ep.id)?.let { return it }
        identifyConfiguredProvider(ep.id)?.let { return it }
        return fallbackProvider?.let { normalizeProviderName(it).takeIf { p -> p != PROVIDER_UNKNOWN } }
            ?: PROVIDER_UNKNOWN
    }

    companion object {
        const val PROVIDER_4KHDHUB = "4khdhub"
        const val PROVIDER_DRAMACHI = "dramachi"
        const val PROVIDER_ADDONS = "addons"
        const val PROVIDER_UNKNOWN = "unknown"
        const val UNKNOWN_PROVIDER_TIER = 50

        val DEFAULT_PRIORITY_ORDER: List<String> = listOf(
            PROVIDER_4KHDHUB,
            PROVIDER_DRAMACHI,
            PROVIDER_ADDONS
        )

        fun normalizeProviderName(raw: String?): String {
            val clean = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return PROVIDER_UNKNOWN
            val compact = clean.replace(Regex("""[\s_-]+"""), "")
            return when {
                compact == "4khdhub" ||
                    compact == "fourkhdhub" ||
                    compact == "4khd" ||
                    compact.startsWith("4khdhub") ||
                    compact.startsWith("fourkhdhub") -> PROVIDER_4KHDHUB
                compact == "dramachi" ||
                    compact.startsWith("dramachi") -> PROVIDER_DRAMACHI
                compact == "addons" ||
                    compact == "addon" ||
                    compact == "stremio" ||
                    compact.startsWith("addons") ||
                    compact.startsWith("stremio") -> PROVIDER_ADDONS
                clean == "unknown provider" || clean == "prov_unknown" -> PROVIDER_UNKNOWN
                else -> clean.replace(Regex("""\s+"""), "_")
            }
        }

        fun identifyProviderFromText(raw: String?): String? {
            val clean = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            val compact = clean.replace(Regex("""[\s_-]+"""), "")
            return when {
                compact.contains("4khdhub") || compact.contains("fourkhdhub") || clean.startsWith("4khd:") -> PROVIDER_4KHDHUB
                compact.contains("dramachi") || clean.startsWith("dramachi:") -> PROVIDER_DRAMACHI
                compact.contains("stremio") || compact.contains("addons") || clean.startsWith("addon:") -> PROVIDER_ADDONS
                else -> null
            }
        }

        fun identifyProviderFromPrefixOrMarker(rawId: String?): String? {
            val clean = rawId?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                clean.startsWith("4khd:") || clean.startsWith("4khdhub:") || clean.startsWith("fourkhdhub:") ||
                    clean.startsWith("4khd_") || clean.startsWith("4khdhub_") ||
                    clean.contains("_4khd_") || clean.contains("_4khdhub_") -> PROVIDER_4KHDHUB
                clean.startsWith("dramachi:") || clean.startsWith("dramachi_") ||
                    clean.contains("_dramachi_") || clean.contains("-dramachi-") -> PROVIDER_DRAMACHI
                clean.startsWith("addons:") || clean.startsWith("addon:") || clean.startsWith("stremio:") ||
                    clean.contains("_addons_") -> PROVIDER_ADDONS
                else -> null
            }
        }

        fun identifyProviderFromToken(rawId: String?): String? {
            val norm = rawId?.trim().orEmpty()
            if (norm.length < 8 || !norm.startsWith("eyJ")) return null
            return runCatching {
                val bytes = decodeUrlSafeBase64(norm)
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val p = json.optString("p", "").trim()
                p.takeIf { it.isNotEmpty() }?.let(::normalizeProviderName)
            }.getOrNull()
        }

        private fun decodeUrlSafeBase64(input: String): ByteArray {
            val clean = input.replace('-', '+').replace('_', '/')
            val table = IntArray(128) { -1 }
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            for (idx in alphabet.indices) {
                table[alphabet[idx].code] = idx
            }
            val out = ByteArrayOutputStream((clean.length * 3) / 4 + 3)
            var buffer = 0
            var bitsLeft = 0
            for (ch in clean) {
                if (ch == '=') break
                if (ch.isWhitespace()) continue
                val code = ch.code
                val value = if (code < table.size) table[code] else -1
                if (value < 0) continue
                buffer = (buffer shl 6) or value
                bitsLeft += 6
                if (bitsLeft >= 8) {
                    bitsLeft -= 8
                    out.write((buffer shr bitsLeft) and 0xFF)
                }
            }
            return out.toByteArray()
        }
    }
}

data class ProviderCatalogPayload(
    val providerId: String,
    val animeIdentity: String? = null,
    val animeTitle: String? = null,
    val seasons: List<ProphecySeasonDto> = emptyList(),
    val episodes: List<ProphecyEpisodeDto> = emptyList(),
    val isAvailable: Boolean = true,
    val cumulativeEpisodeOffset: Int = 0
)

data class ProviderEpisodeBinding(
    val canonicalEpisodeId: String,
    val providerId: String,
    val backendEpisodeId: String,
    val seasonNumber: Int,
    val episodeNumber: Int
)

data class CanonicalProviderCatalogResult(
    val seasons: List<SeasonEntry>,
    val episodes: List<EpisodeEntry>,
    val bindingsByCanonicalId: Map<String, Map<String, ProviderEpisodeBinding>>,
    val primaryAuthorityAvailable: Boolean,
    val preservedPriorCatalog: Boolean,
    val skippedUnmatchedFallbackCount: Int,
    val skippedDuplicateFallbackCount: Int
)

/**
 * Builds and reconciles the single canonical episode catalog with 4KHDHUB as sole mapping authority.
 *
 * Guarantees:
 * - 4KHDHUB defines canonical season structure, episode numbering, ordering, and count.
 * - Fallback providers (Dramachi, future providers) only attach to verified existing canonical
 *   episodes and retain their original backendEpisodeId separately from canonical episode IDs.
 * - Never merges episodes solely because episode numbers match when title/identity/season conflicts
 *   or metadata is unverified.
 * - Extra fallback episodes never create canonical episodes; missing fallback episodes never delete them.
 * - Duplicate fallback mappings never create duplicate canonical rows.
 * - When 4KHDHUB is unavailable, the last valid Room canonical catalog is preserved intact.
 */
object CanonicalEpisodeCatalogBuilder {
    private val GENERIC_EPISODE_TITLE_REGEX = Regex(
        """^(?:episode|ep\.?|e)?\s*0*\d+(?:\s*[-:]\s*part\s*\d+)?$""",
        RegexOption.IGNORE_CASE
    )
    private val NON_ALNUM_REGEX = Regex("""[^a-z0-9\s]""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    fun isSpecificEpisodeTitle(title: String?): Boolean {
        val clean = title?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return !GENERIC_EPISODE_TITLE_REGEX.matches(clean)
    }

    fun normalizeEpisodeTitle(title: String?): String {
        val base = TitleMatcher.normalize(title)
        if (base.isBlank()) return ""
        return MULTI_SPACE_REGEX.replace(NON_ALNUM_REGEX.replace(base, " "), " ").trim()
    }

    fun episodeTitleSimilarity(titleA: String?, titleB: String?): Float {
        val a = normalizeEpisodeTitle(titleA)
        val b = normalizeEpisodeTitle(titleB)
        if (a.isBlank() || b.isBlank()) return 0f
        if (a == b) return 1f
        if (a.length >= 4 && b.length >= 4 && (a.contains(b) || b.contains(a))) return 0.90f
        val tokensA = a.split(" ").filter { it.length > 1 }.toSet()
        val tokensB = b.split(" ").filter { it.length > 1 }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0f
        if (tokensA == tokensB) return 1f
        val intersection = tokensA.intersect(tokensB).size.toFloat()
        val union = tokensA.union(tokensB).size.toFloat()
        return intersection / union
    }

    /**
     * Checks if a fallback provider episode is verified to represent [canonical].
     * Never merges purely because `fallback.number == canonical.episodeNumber` when
     * metadata conflicts or verification signals are missing/contradictory.
     */
    fun isVerifiedCorrespondence(
        canonical: EpisodeEntry,
        canonicalDtoId: String?,
        canonicalAnimeIdentity: String?,
        fallback: ProphecyEpisodeDto,
        fallbackSeason: Int?,
        fallbackAnimeIdentity: String?,
        cumulativeOffset: Int = 0
    ): Boolean {
        val fallbackId = fallback.id.trim()
        if (fallbackId.isEmpty() || fallback.number <= 0) return false

        // 1. Anime identity check when both identities are supplied
        if (!canonicalAnimeIdentity.isNullOrBlank() && !fallbackAnimeIdentity.isNullOrBlank()) {
            val normCanonAnime = canonicalAnimeIdentity.trim().lowercase(Locale.ROOT)
            val normFallbackAnime = fallbackAnimeIdentity.trim().lowercase(Locale.ROOT)
            if (normCanonAnime != normFallbackAnime) {
                val titleSim = TitleMatcher.normalize(canonicalAnimeIdentity) == TitleMatcher.normalize(fallbackAnimeIdentity)
                val canonicalIdentityIsOpaque = normCanonAnime.all { it.isDigit() } ||
                    normCanonAnime.startsWith("anilist_")
                if (!titleSim && !canonicalIdentityIsOpaque) return false
            }
        }

        // 2. Canonical ID check: matching canonical ID verifies immediately; conflicting canonical ID rejects
        val cleanCanonDtoId = canonicalDtoId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanFallbackCanonId = fallback.canonicalId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanCanonDtoId != null && cleanFallbackCanonId != null) {
            if (cleanCanonDtoId != cleanFallbackCanonId) return false
            // If canonical ID matches AND both have specific conflicting titles, reject anyway
            if (isSpecificEpisodeTitle(canonical.title) && isSpecificEpisodeTitle(fallback.title)) {
                return episodeTitleSimilarity(canonical.title, fallback.title) >= 0.72f
            }
            return true
        }

        val resolvedFallbackSeason = fallback.season?.takeIf { it > 0 } ?: fallbackSeason
        val expectedDirectSeason = canonical.seasonNumber
        val exactSeasonAndNumber = resolvedFallbackSeason != null &&
            resolvedFallbackSeason == expectedDirectSeason &&
            fallback.number == canonical.episodeNumber
        val verifiedCumulativeNumber = cumulativeOffset > 0 &&
            expectedDirectSeason > 1 &&
            (resolvedFallbackSeason == null || resolvedFallbackSeason == 1) &&
            fallback.number == cumulativeOffset + canonical.episodeNumber &&
            fallback.number > cumulativeOffset

        val hasSpecificCanonTitle = isSpecificEpisodeTitle(canonical.title)
        val hasSpecificFallbackTitle = isSpecificEpisodeTitle(fallback.title)

        // 3. When both titles are specific, title verification is mandatory even if episode numbers match,
        // and high-confidence title match can verify shifted numbering within compatible anime identity.
        if (hasSpecificCanonTitle && hasSpecificFallbackTitle) {
            val sim = episodeTitleSimilarity(canonical.title, fallback.title)
            if (exactSeasonAndNumber || verifiedCumulativeNumber) {
                return sim >= 0.72f
            }
            // Different episode number or missing/shifted season: require strong exact/near-exact title correspondence
            // and non-conflicting season (or continuous S1 cumulative numbering).
            val compatibleSeason = resolvedFallbackSeason == null ||
                resolvedFallbackSeason == expectedDirectSeason ||
                (expectedDirectSeason > 1 && resolvedFallbackSeason == 1)
            return compatibleSeason && sim >= 0.85f
        }

        val animeIdentitiesVerified = !canonicalAnimeIdentity.isNullOrBlank() &&
            !fallbackAnimeIdentity.isNullOrBlank() &&
            (canonicalAnimeIdentity.trim().equals(fallbackAnimeIdentity.trim(), ignoreCase = true) ||
                TitleMatcher.normalize(canonicalAnimeIdentity) == TitleMatcher.normalize(fallbackAnimeIdentity))

        // 4. Without two specific matching titles, we require verified season + number correspondence
        // plus corroborating anime identity (never merge solely because episode numbers match).
        if (exactSeasonAndNumber) {
            return animeIdentitiesVerified && (resolvedFallbackSeason ?: 0) > 0
        }
        if (verifiedCumulativeNumber) {
            return animeIdentitiesVerified
        }
        return false
    }

    fun build(
        animeId: String,
        canonicalAnimeIdentity: String?,
        payloads: List<ProviderCatalogPayload>,
        existingCanonicalSeasons: List<SeasonEntry> = emptyList(),
        existingCanonicalEpisodes: List<EpisodeEntry> = emptyList(),
        targetCatalogSeasonOverride: Int? = null,
        policy: ProviderPriorityPolicy = ProviderPriorityPolicy()
    ): CanonicalProviderCatalogResult {
        val primaryPayload = payloads.firstOrNull {
            ProviderPriorityPolicy.normalizeProviderName(it.providerId) == ProviderPriorityPolicy.PROVIDER_4KHDHUB
        }
        val primaryEpisodesRaw = if (primaryPayload?.isAvailable == true) {
            flattenSeasonEpisodes(primaryPayload)
        } else {
            emptyList()
        }
        val primaryAvailable = primaryPayload?.isAvailable == true && primaryEpisodesRaw.isNotEmpty()

        val baseSeasons: List<SeasonEntry>
        val baseEpisodes: List<EpisodeEntry>
        val canonDtoIdByEpisodeId = HashMap<String, String>()
        val bindingsByCanonicalId = LinkedHashMap<String, MutableMap<String, ProviderEpisodeBinding>>()
        val preservedPrior: Boolean

        if (primaryAvailable) {
            preservedPrior = false
            val rawSeasons = primaryPayload!!.seasons.filter { it.number > 0 }
            val builtEpisodes = mutableListOf<EpisodeEntry>()
            val seenLogical = HashSet<Pair<Int, Int>>()

            for ((rawSeasonNum, epDto) in primaryEpisodesRaw) {
                val epId = epDto.id.trim()
                if (epId.isEmpty() || epDto.number <= 0) continue
                val effectiveSeason = if (targetCatalogSeasonOverride != null && rawSeasonNum == 1) {
                    targetCatalogSeasonOverride
                } else {
                    rawSeasonNum
                }
                if (!seenLogical.add(effectiveSeason to epDto.number)) continue

                val entry = EpisodeEntry(
                    id = epId,
                    animeId = animeId,
                    seasonNumber = effectiveSeason,
                    episodeNumber = epDto.number,
                    title = epDto.title?.trim()?.takeIf { it.isNotEmpty() },
                    overview = epDto.overview?.trim()?.takeIf { it.isNotEmpty() },
                    isAvailable = true,
                    sourceEpisodeIds = EpisodeSourceEpisodeIds.encode(listOf(epId))
                )
                builtEpisodes += entry
                epDto.canonicalId?.trim()?.takeIf { it.isNotEmpty() }?.let { cId ->
                    canonDtoIdByEpisodeId[entry.id] = cId
                }
                bindingsByCanonicalId.getOrPut(entry.id) { LinkedHashMap() }[ProviderPriorityPolicy.PROVIDER_4KHDHUB] =
                    ProviderEpisodeBinding(
                        canonicalEpisodeId = entry.id,
                        providerId = ProviderPriorityPolicy.PROVIDER_4KHDHUB,
                        backendEpisodeId = epId,
                        seasonNumber = effectiveSeason,
                        episodeNumber = epDto.number
                    )
            }

            val builtSeasonEntries = rawSeasons.map { seasonDto ->
                val effectiveNum = if (targetCatalogSeasonOverride != null && seasonDto.number == 1) {
                    targetCatalogSeasonOverride
                } else {
                    seasonDto.number
                }
                SeasonEntry(
                    id = "${animeId}_s$effectiveNum",
                    animeId = animeId,
                    seasonNumber = effectiveNum,
                    backendSeasonId = seasonDto.canonicalId?.takeIf { it.isNotBlank() }
                        ?: seasonDto.id?.takeIf { it.isNotBlank() },
                    title = seasonDto.title?.takeIf { it.isNotBlank() } ?: "Season $effectiveNum"
                )
            }.distinctBy { it.seasonNumber }.toMutableList()

            val knownSeasonNums = builtSeasonEntries.map { it.seasonNumber }.toSet()
            builtEpisodes.map { it.seasonNumber }.distinct()
                .filter { it !in knownSeasonNums }
                .forEach { sNum ->
                    builtSeasonEntries += SeasonEntry(
                        id = "${animeId}_s$sNum",
                        animeId = animeId,
                        seasonNumber = sNum,
                        backendSeasonId = null,
                        title = "Season $sNum"
                    )
                }
            baseSeasons = builtSeasonEntries.sortedBy { it.seasonNumber }
            baseEpisodes = builtEpisodes.sortedWith(compareBy<EpisodeEntry> { it.seasonNumber }.thenBy { it.episodeNumber })
        } else {
            // 4KHDHUB temporarily unavailable: preserve last valid canonical catalog strictly.
            // Never replace canonical catalog structure with Dramachi/fallback provider episodes.
            preservedPrior = existingCanonicalEpisodes.isNotEmpty()
            baseSeasons = existingCanonicalSeasons.sortedBy { it.seasonNumber }
            baseEpisodes = existingCanonicalEpisodes
                .sortedWith(compareBy<EpisodeEntry> { it.seasonNumber }.thenBy { it.episodeNumber })
        }

        if (baseEpisodes.isEmpty()) {
            return CanonicalProviderCatalogResult(
                seasons = baseSeasons,
                episodes = emptyList(),
                bindingsByCanonicalId = bindingsByCanonicalId,
                primaryAuthorityAvailable = primaryAvailable,
                preservedPriorCatalog = false,
                skippedUnmatchedFallbackCount = 0,
                skippedDuplicateFallbackCount = 0
            )
        }

        // Attach verified fallback providers (Dramachi, future providers) onto existing canonical episodes
        val fallbackPayloads = payloads
            .filter {
                ProviderPriorityPolicy.normalizeProviderName(it.providerId) != ProviderPriorityPolicy.PROVIDER_4KHDHUB &&
                    it.isAvailable
            }
            .sortedBy { policy.tierRank(it.providerId) }

        val episodeIdsByCanonical = LinkedHashMap<String, LinkedHashSet<String>>()
        for (ep in baseEpisodes) {
            val idSet = LinkedHashSet<String>()
            EpisodeSourceEpisodeIds.decode(ep.sourceEpisodeIds)
                .filter { it.isNotBlank() }
                .forEach(idSet::add)
            if (primaryAvailable && ep.id.isNotBlank()) {
                idSet.add(ep.id)
            }
            episodeIdsByCanonical[ep.id] = idSet
        }

        var skippedUnmatched = 0
        var skippedDuplicate = 0

        for (fallbackPayload in fallbackPayloads) {
            val provId = ProviderPriorityPolicy.normalizeProviderName(fallbackPayload.providerId)
            val fallbackItems = flattenSeasonEpisodes(fallbackPayload)
            val mappedCanonicalForThisProvider = HashSet<String>()
            val seenFallbackBackendIds = HashSet<String>()

            for ((rawSeason, fallbackDto) in fallbackItems) {
                val fbEpId = fallbackDto.id.trim()
                if (fbEpId.isEmpty() || fallbackDto.number <= 0) {
                    skippedUnmatched++
                    continue
                }
                if (!seenFallbackBackendIds.add(fbEpId)) {
                    skippedDuplicate++
                    continue
                }
                val candidateSeason = if (targetCatalogSeasonOverride != null && rawSeason == 1 && fallbackPayload.cumulativeEpisodeOffset <= 0) {
                    targetCatalogSeasonOverride
                } else {
                    rawSeason
                }

                // Prefer direct same (season, episodeNumber) canonical candidate first, else search all canonical episodes
                val sortedCanonCandidates = baseEpisodes.sortedBy { ep ->
                    when {
                        ep.seasonNumber == candidateSeason && ep.episodeNumber == fallbackDto.number -> 0
                        fallbackPayload.cumulativeEpisodeOffset > 0 &&
                            ep.seasonNumber > 1 &&
                            fallbackDto.number == fallbackPayload.cumulativeEpisodeOffset + ep.episodeNumber -> 1
                        else -> 2
                    }
                }

                val matchedCanonical = sortedCanonCandidates.firstOrNull { canon ->
                    canon.id !in mappedCanonicalForThisProvider &&
                        isVerifiedCorrespondence(
                            canonical = canon,
                            canonicalDtoId = canonDtoIdByEpisodeId[canon.id],
                            canonicalAnimeIdentity = canonicalAnimeIdentity,
                            fallback = fallbackDto,
                            fallbackSeason = candidateSeason,
                            fallbackAnimeIdentity = fallbackPayload.animeIdentity ?: fallbackPayload.animeTitle,
                            cumulativeOffset = fallbackPayload.cumulativeEpisodeOffset
                        )
                }

                if (matchedCanonical == null) {
                    // Check if it was a duplicate for an already-mapped canonical episode
                    val alreadyMappedMatch = sortedCanonCandidates.any { canon ->
                        canon.id in mappedCanonicalForThisProvider &&
                            isVerifiedCorrespondence(
                                canonical = canon,
                                canonicalDtoId = canonDtoIdByEpisodeId[canon.id],
                                canonicalAnimeIdentity = canonicalAnimeIdentity,
                                fallback = fallbackDto,
                                fallbackSeason = candidateSeason,
                                fallbackAnimeIdentity = fallbackPayload.animeIdentity ?: fallbackPayload.animeTitle,
                                cumulativeOffset = fallbackPayload.cumulativeEpisodeOffset
                            )
                    }
                    if (alreadyMappedMatch) skippedDuplicate++ else skippedUnmatched++
                    continue
                }

                mappedCanonicalForThisProvider += matchedCanonical.id
                bindingsByCanonicalId.getOrPut(matchedCanonical.id) { LinkedHashMap() }[provId] =
                    ProviderEpisodeBinding(
                        canonicalEpisodeId = matchedCanonical.id,
                        providerId = provId,
                        backendEpisodeId = fbEpId,
                        seasonNumber = candidateSeason,
                        episodeNumber = fallbackDto.number
                    )
                episodeIdsByCanonical.getOrPut(matchedCanonical.id) { LinkedHashSet() }.add(fbEpId)
            }
        }

        val finalEpisodes = baseEpisodes.map { canon ->
            val boundIds = episodeIdsByCanonical[canon.id]?.toList().orEmpty()
            val encoded = EpisodeSourceEpisodeIds.encode(boundIds).takeIf { boundIds.isNotEmpty() }
            canon.copy(sourceEpisodeIds = encoded ?: canon.sourceEpisodeIds)
        }

        return CanonicalProviderCatalogResult(
            seasons = baseSeasons,
            episodes = finalEpisodes,
            bindingsByCanonicalId = bindingsByCanonicalId,
            primaryAuthorityAvailable = primaryAvailable,
            preservedPriorCatalog = preservedPrior,
            skippedUnmatchedFallbackCount = skippedUnmatched,
            skippedDuplicateFallbackCount = skippedDuplicate
        )
    }

    private fun flattenSeasonEpisodes(payload: ProviderCatalogPayload): List<Pair<Int, ProphecyEpisodeDto>> {
        val out = mutableListOf<Pair<Int, ProphecyEpisodeDto>>()
        val seenIds = HashSet<String>()
        payload.seasons.filter { it.number > 0 }.forEach { s ->
            s.episodes.orEmpty().forEach { ep ->
                val epId = ep.id.trim()
                if (epId.isNotEmpty() && seenIds.add(epId)) {
                    val epSeason = ep.season?.takeIf { it > 0 } ?: s.number
                    out += epSeason to ep.copy(season = epSeason)
                }
            }
        }
        payload.episodes.forEach { ep ->
            val epId = ep.id.trim()
            if (epId.isNotEmpty() && seenIds.add(epId)) {
                val epSeason = ep.season?.takeIf { it > 0 } ?: 1
                out += epSeason to ep.copy(season = epSeason)
            }
        }
        return out
    }
}
