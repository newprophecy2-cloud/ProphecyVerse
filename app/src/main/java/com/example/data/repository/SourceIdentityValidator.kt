package com.example.data.repository

import android.util.Base64
import android.util.Log
import com.example.data.d1.D1StreamUrlRecordDto
import com.example.data.local.AnimeEntry
import com.example.data.remote.DirectPlayData
import com.example.data.remote.ProphecyEpisodeFullResponse
import com.example.data.remote.ProphecySearchResultItem
import com.example.data.remote.ProphecySourceDto
import com.example.data.streaming.ContentIdentityHelper
import com.example.data.streaming.SafePlaybackHeaders
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

enum class SourceDiagnosticCode {
    ANIME_ID_MISMATCH,
    EPISODE_ID_MISMATCH,
    SEASON_MISMATCH,
    PROVIDER_ID_MISMATCH,
    SOURCE_EXPIRED,
    SOURCE_NOT_PLAYABLE,
    RESOLUTION_TIMEOUT,
    PROVIDER_UNAVAILABLE
}

sealed class SourceValidationResult {
    data object Valid : SourceValidationResult()
    data class Invalid(
        val code: SourceDiagnosticCode,
        val reason: String
    ) : SourceValidationResult()

    val isValid: Boolean get() = this is Valid
}

class SourceResolutionDiagnosticException(
    val diagnosticCode: SourceDiagnosticCode,
    message: String,
    val providerLabel: String? = null
) : IllegalStateException(message)

data class SourceDiagnosticEntry(
    val timestampMs: Long,
    val code: SourceDiagnosticCode,
    val scopeKey: String,
    val sourceId: String? = null,
    val providerLabel: String? = null,
    val detail: String
)

/**
 * Canonical source & playback identity normalizer and validator.
 * Enforces strict cross-anime / cross-episode boundaries while preserving
 * valid provider-specific source IDs, missing optional metadata, sequel mappings,
 * and Movie S1E1 compatibility.
 */
object SourceIdentityValidator {
    private const val TAG = "SourceIdentity"
    private const val MAX_DIAGNOSTICS = 64
    private val recentDiagnosticsBuffer = CopyOnWriteArrayList<SourceDiagnosticEntry>()

    fun normalizeId(raw: String?): String = raw?.trim().orEmpty()

    fun normalizedIdsEqual(a: String?, b: String?): Boolean {
        val na = normalizeId(a)
        val nb = normalizeId(b)
        return na.isNotEmpty() && na == nb
    }

    fun recordDiagnostic(
        code: SourceDiagnosticCode,
        scopeKey: String,
        sourceId: String? = null,
        providerLabel: String? = null,
        detail: String = ""
    ) {
        val sanitizedDetail = detail
            .replace(Regex("""https?://[^\s"']+"""), "[redacted-url]")
            .replace(Regex("""[\r\n\t]+"""), " ")
            .take(180)
        val safeProvider = providerLabel?.trim()?.take(48)
        val safeSource = sourceId?.trim()?.take(64)
        val entry = SourceDiagnosticEntry(
            timestampMs = System.currentTimeMillis(),
            code = code,
            scopeKey = scopeKey,
            sourceId = safeSource,
            providerLabel = safeProvider,
            detail = sanitizedDetail
        )
        recentDiagnosticsBuffer.add(entry)
        while (recentDiagnosticsBuffer.size > MAX_DIAGNOSTICS) {
            runCatching { recentDiagnosticsBuffer.removeAt(0) }
        }
        Log.w(
            TAG,
            "source_resolution_diag code=${code.name} scope=$scopeKey source=${safeSource ?: "none"} provider=${safeProvider ?: "unknown"} detail=$sanitizedDetail"
        )
    }

    fun recentDiagnostics(): List<SourceDiagnosticEntry> = recentDiagnosticsBuffer.toList()

    fun clearDiagnosticsForTests() {
        recentDiagnosticsBuffer.clear()
    }

    /**
     * Validates an episode-full backend payload against the requested anime and episode scope.
     * Optional `anime` and `selectedEpisode` fields may be omitted by backend providers;
     * when present, conflicting IDs or episode/season numbers reject only that response.
     */
    fun validateEpisodeFullResponse(
        body: ProphecyEpisodeFullResponse,
        animeEntry: AnimeEntry?,
        requestedBackendId: String,
        requestedBackendEpisodeId: String,
        isMovie: Boolean,
        requestedSeason: Int,
        effectiveBackendSeason: Int,
        allowedEpisodeNumbers: Set<Int>,
        cumulativeOffset: Int = 0
    ): SourceValidationResult {
        val normReqBackendId = normalizeId(requestedBackendId)
        val normReqEpId = normalizeId(requestedBackendEpisodeId)

        val respAnime = body.anime
        if (respAnime != null) {
            val respId = normalizeId(respAnime.id)
            val respCanonical = normalizeId(respAnime.canonicalId)
            val respProviderIds = respAnime.providerMappings.orEmpty()
                .flatMap { listOfNotNull(normalizeId(it.id), normalizeId(it.value)) }
                .filter { it.isNotEmpty() }
            val knownIds = listOfNotNull(
                normReqBackendId.takeIf { it.isNotEmpty() },
                normalizeId(animeEntry?.backendAnimeId).takeIf { it.isNotEmpty() },
                animeEntry?.canonicalAnimeId?.takeIf { it > 0 }?.let { "anilist_$it" }
            ).toSet()
            val directIdMatch = respId.isEmpty() ||
                respId in knownIds ||
                (respCanonical.isNotEmpty() && respCanonical in knownIds) ||
                respProviderIds.any { it in knownIds } ||
                sameTokenCore(respId, normReqBackendId) ||
                sameTokenCore(respId, animeEntry?.backendAnimeId)

            if (!directIdMatch) {
                val titleMatchesEntry = animeEntry != null && respAnime.title.isNotBlank() && run {
                    val synthetic = ProphecySearchResultItem(
                        id = respId,
                        canonicalId = respAnime.canonicalId,
                        title = respAnime.title,
                        mediaType = respAnime.mediaType,
                        year = respAnime.year,
                        titleEnglish = respAnime.titleEnglish,
                        titleRomaji = respAnime.titleRomaji,
                        titleNative = respAnime.titleNative,
                        alternativeTitles = respAnime.alternativeTitles,
                        seasonCount = respAnime.seasonCount
                    )
                    TitleMatcher.computeMatchConfidenceForEntry(animeEntry, synthetic) >= 0.45f
                }
                if (!titleMatchesEntry) {
                    return SourceValidationResult.Invalid(
                        SourceDiagnosticCode.ANIME_ID_MISMATCH,
                        "Response anime.id '$respId' did not match expected backend '$normReqBackendId'."
                    )
                }
            }
            val respMedia = respAnime.mediaType?.trim()?.lowercase()
            if (animeEntry != null && !animeEntry.format.isNullOrBlank() &&
                respMedia in setOf("movie", "film", "anime", "tv", "series", "kdrama")
            ) {
                val respIsMovie = respMedia == "movie" || respMedia == "film"
                if (respIsMovie != isMovie) {
                    return SourceValidationResult.Invalid(
                        SourceDiagnosticCode.ANIME_ID_MISMATCH,
                        "Response anime mediaType '$respMedia' conflicts with isMovie=$isMovie."
                    )
                }
            }
        }

        val selectedEp = body.selectedEpisode
        if (selectedEp != null) {
            val epId = normalizeId(selectedEp.id)
            val epCanonId = normalizeId(selectedEp.canonicalId)
            val epProviderValues = selectedEp.providerMappings.orEmpty()
                .flatMap { listOfNotNull(normalizeId(it.id), normalizeId(it.value)) }
                .filter { it.isNotEmpty() }
                .toSet()

            if (epId.isNotEmpty() && normReqEpId.isNotEmpty()) {
                val idMatches = epId == normReqEpId ||
                    (epCanonId.isNotEmpty() && epCanonId == normReqEpId) ||
                    normReqEpId in epProviderValues ||
                    sameTokenCore(epId, normReqEpId)
                if (!idMatches) {
                    return SourceValidationResult.Invalid(
                        SourceDiagnosticCode.EPISODE_ID_MISMATCH,
                        "Response selected_episode.id '$epId' did not match requested '$normReqEpId'."
                    )
                }
            }

            if (isMovie) {
                val epSeason = selectedEp.season
                if (epSeason != null && epSeason > 1) {
                    return SourceValidationResult.Invalid(
                        SourceDiagnosticCode.SEASON_MISMATCH,
                        "Movie response returned non-movie season=$epSeason."
                    )
                }
                if (selectedEp.number > 1) {
                    return SourceValidationResult.Invalid(
                        SourceDiagnosticCode.EPISODE_ID_MISMATCH,
                        "Movie response returned unexpected episode number=${selectedEp.number}."
                    )
                }
            } else {
                val epSeason = selectedEp.season
                if (epSeason != null && epSeason > 0) {
                    val allowedSeasons = buildSet {
                        add(requestedSeason)
                        add(effectiveBackendSeason)
                        if (cumulativeOffset > 0) add(1)
                    }
                    if (epSeason !in allowedSeasons) {
                        return SourceValidationResult.Invalid(
                            SourceDiagnosticCode.SEASON_MISMATCH,
                            "Response selected_episode.season=$epSeason not in expected $allowedSeasons."
                        )
                    }
                }
                if (selectedEp.number > 0 && allowedEpisodeNumbers.isNotEmpty() && selectedEp.number !in allowedEpisodeNumbers) {
                    return SourceValidationResult.Invalid(
                        SourceDiagnosticCode.EPISODE_ID_MISMATCH,
                        "Response selected_episode.number=${selectedEp.number} not in expected $allowedEpisodeNumbers."
                    )
                }
            }
        }

        return SourceValidationResult.Valid
    }

    /**
     * Validates a Cloudflare D1 Stream URL record keyed by (anime_id, episode_id, source_id).
     */
    fun validateD1StreamUrlRecord(
        record: D1StreamUrlRecordDto,
        expectedAnimeId: String,
        expectedEpisodeId: String,
        expectedSourceId: String,
        nowMs: Long = System.currentTimeMillis()
    ): SourceValidationResult {
        val normExpectedAnime = normalizeId(expectedAnimeId)
        val normExpectedEp = normalizeId(expectedEpisodeId)
        val normExpectedSrc = normalizeId(expectedSourceId)
        if (normExpectedAnime.isEmpty() || normExpectedEp.isEmpty() || normExpectedSrc.isEmpty()) {
            return SourceValidationResult.Invalid(
                SourceDiagnosticCode.SOURCE_NOT_PLAYABLE,
                "Expected D1 stream URL identity key is incomplete."
            )
        }
        if (normalizeId(record.animeId) != normExpectedAnime) {
            return SourceValidationResult.Invalid(
                SourceDiagnosticCode.ANIME_ID_MISMATCH,
                "D1 anime_id '${record.animeId}' did not match '$normExpectedAnime'."
            )
        }
        if (normalizeId(record.episodeId) != normExpectedEp) {
            return SourceValidationResult.Invalid(
                SourceDiagnosticCode.EPISODE_ID_MISMATCH,
                "D1 episode_id '${record.episodeId}' did not match '$normExpectedEp'."
            )
        }
        if (normalizeId(record.sourceId) != normExpectedSrc) {
            return SourceValidationResult.Invalid(
                SourceDiagnosticCode.PROVIDER_ID_MISMATCH,
                "D1 source_id '${record.sourceId}' did not match '$normExpectedSrc'."
            )
        }
        if (!SafePlaybackHeaders.isValidStreamUrl(record.streamUrl)) {
            return SourceValidationResult.Invalid(
                SourceDiagnosticCode.SOURCE_NOT_PLAYABLE,
                "D1 cached stream_url failed scheme/validity verification."
            )
        }
        if (SourceExpiryTime.isExpiredOrInvalid(record.expiresAt, nowMs)) {
            return SourceValidationResult.Invalid(
                SourceDiagnosticCode.SOURCE_EXPIRED,
                "D1 cached stream_url for '$normExpectedSrc' is expired or has invalid expiry."
            )
        }
        return SourceValidationResult.Valid
    }

    /**
     * Validates a resolved [DirectPlayData] returned from cache or `/api/v1/play/direct`.
     * Allows legitimate provider-specific source IDs distinct from canonical episode IDs,
     * and treats blank/null `expiresAt` as valid while enforcing exact normalized `sourceId` identity.
     */
    fun validateDirectPlayData(
        data: DirectPlayData,
        expectedSourceId: String,
        nowMs: Long = System.currentTimeMillis()
    ): SourceValidationResult {
        val normExpected = normalizeId(expectedSourceId)
        val normActual = normalizeId(data.sourceId)
        if (normExpected.isEmpty()) {
            return SourceValidationResult.Invalid(
                SourceDiagnosticCode.SOURCE_NOT_PLAYABLE,
                "Expected source ID is empty."
            )
        }
        val exactMatch = normActual.isNotEmpty() && normActual == normExpected
        val fallbackMatch = !exactMatch && normActual.isNotEmpty() && isValidFallbackTokenMatch(
            data = data,
            expectedSourceId = normExpected,
            actualSourceId = normActual
        )
        if (!exactMatch && !fallbackMatch) {
            return SourceValidationResult.Invalid(
                SourceDiagnosticCode.PROVIDER_ID_MISMATCH,
                "Direct-play response sourceId '$normActual' did not match requested '$normExpected'."
            )
        }
        if (!SafePlaybackHeaders.isValidStreamUrl(data.playbackUrl)) {
            return SourceValidationResult.Invalid(
                SourceDiagnosticCode.SOURCE_NOT_PLAYABLE,
                "Direct-play URL failed scheme/validity verification."
            )
        }
        if (SourceExpiryTime.isExpiredOrInvalid(data.expiresAt, nowMs)) {
            return SourceValidationResult.Invalid(
                SourceDiagnosticCode.SOURCE_EXPIRED,
                "Direct-play URL for '$normExpected' is already expired."
            )
        }
        return SourceValidationResult.Valid
    }

    private fun isValidFallbackTokenMatch(
        data: DirectPlayData,
        expectedSourceId: String,
        actualSourceId: String
    ): Boolean {
        val expectedToken = decodeTokenCore(expectedSourceId) ?: return false
        val actualToken = decodeTokenCore(actualSourceId) ?: return false
        if (expectedToken.season != null && actualToken.season != null && expectedToken.season != actualToken.season) {
            return false
        }
        if (expectedToken.episode != null && actualToken.episode != null && expectedToken.episode != actualToken.episode) {
            return false
        }
        val isFallbackDeclared = data.message?.contains("fallback provider", ignoreCase = true) == true
        val sameCore = (expectedToken.canonicalId != null && expectedToken.canonicalId == actualToken.canonicalId) ||
            (expectedToken.provider == actualToken.provider && expectedToken.value == actualToken.value)
        return isFallbackDeclared || sameCore
    }

    fun normalizeDto(dto: ProphecySourceDto): ProphecySourceDto {
        val trimmedId = normalizeId(dto.id)
        return if (trimmedId == dto.id) dto else dto.copy(id = trimmedId)
    }

    fun scopeForEpisode(
        animeId: String,
        anime: AnimeEntry?,
        seasonNumber: Int,
        episodeNumber: Int
    ): String {
        val identity = ContentIdentityHelper.resolveCanonicalIdentity(animeId, anime, seasonNumber, episodeNumber)
        return ContentIdentityHelper.scopeKey(
            contentKey = identity.contentKey,
            isMovie = identity.isMovie,
            seasonNumber = identity.seasonNumber,
            episodeNumber = identity.episodeNumber
        )
    }

    private fun extractSeasonToken(scopeKey: String): String? =
        Regex(""":s(\d+):""").find(scopeKey)?.groupValues?.getOrNull(1)

    private data class DecodedTokenCore(
        val provider: String,
        val value: String,
        val canonicalId: String?,
        val season: Int? = null,
        val episode: Int? = null
    )

    private fun decodeTokenCore(rawId: String?): DecodedTokenCore? {
        val norm = normalizeId(rawId)
        if (norm.length < 8 || !norm.startsWith("eyJ")) return null
        return runCatching {
            val bytes = try {
                Base64.decode(norm, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            } catch (_: Throwable) {
                decodeUrlSafeBase64Fallback(norm)
            }
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val p = json.optString("p", "").trim()
            val v = json.optString("v", "").trim()
            val c = json.optString("canonical_id", "").trim().takeIf { it.isNotEmpty() }
            val s = if (json.has("s") && !json.isNull("s")) json.optInt("s", -1).takeIf { it >= 0 } else null
            val e = if (json.has("e") && !json.isNull("e")) json.optInt("e", -1).takeIf { it >= 0 } else null
            if (p.isNotEmpty() && v.isNotEmpty()) DecodedTokenCore(p, v, c, s, e) else null
        }.getOrNull()
    }

    private fun sameTokenCore(a: String?, b: String?): Boolean {
        val ca = decodeTokenCore(a) ?: return false
        val cb = decodeTokenCore(b) ?: return false
        if (ca.canonicalId != null && cb.canonicalId != null && ca.canonicalId == cb.canonicalId) return true
        return ca.provider == cb.provider && ca.value == cb.value
    }

    private fun decodeUrlSafeBase64Fallback(input: String): ByteArray {
        val clean = input.replace('-', '+').replace('_', '/')
        val table = IntArray(128) { -1 }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (idx in alphabet.indices) {
            table[alphabet[idx].code] = idx
        }
        val out = java.io.ByteArrayOutputStream((clean.length * 3) / 4 + 3)
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
