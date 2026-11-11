package com.example

import com.example.data.firebase.FirestoreFailureCategory
import com.example.data.firebase.FirestoreQuotaCooldown
import com.example.data.firebase.FirestoreRequestGuard
import com.example.data.local.EpisodeEntry
import com.example.data.local.SeasonEntry
import com.example.data.local.EpisodeSourceCandidateRecord
import com.example.data.remote.ProphecyEpisodeFullResponse
import com.example.data.remote.ProphecySourceDto
import com.example.data.remote.DirectPlayResponse
import com.example.player.PreResolveQueueManager
import com.example.data.repository.AnimeRepository
import com.example.data.repository.EpisodeCatalogSnapshot
import com.example.data.repository.EpisodeCatalogSnapshotMerger
import com.example.data.repository.EpisodeSourceCacheIdentity
import com.example.data.repository.EpisodeSourceCachePolicy
import com.example.data.repository.EpisodeSourceRetryBackoff
import com.example.data.repository.EpisodeSourceAggregator
import com.example.data.repository.EpisodeSourceResponseState
import com.example.data.repository.SourceExpiryTime
import com.example.data.repository.isValidPlaybackUrl
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SourceCacheAndQuotaGuardTest {
    private val now = Instant.parse("2026-11-09T00:00:00Z").toEpochMilli()

    @Test
    fun directPlaybackAcceptsOnlyWellFormedHttpOrFileUrls() {
        assertTrue(isValidPlaybackUrl("https://media.example.invalid/video.m3u8"))
        assertTrue(isValidPlaybackUrl("http://media.example.invalid/video.mp4"))
        assertTrue(isValidPlaybackUrl("file:///data/user/0/app/cache/video.mp4"))
        assertFalse(isValidPlaybackUrl(""))
        assertFalse(isValidPlaybackUrl("https://"))
        assertFalse(isValidPlaybackUrl("https:/missing-host"))
        assertFalse(isValidPlaybackUrl("ftp://media.example.invalid/video"))
        assertFalse(isValidPlaybackUrl("javascript:alert(1)"))
    }

    @Test
    fun repeatedSourceIdUpdatesMetadataWithoutCreatingProviderDuplicates() {
        val first = ProphecySourceDto(id = "same", label = "Provider A", quality = "720p", language = "ja")
        val qualityVariant = first.copy(quality = "1080p")
        val languageVariant = first.copy(language = "en")
        val providerVariant = first.copy(label = "Provider B")

        val result = EpisodeSourceAggregator.aggregate(
            apiCandidates = listOf(first, first, qualityVariant, languageVariant, providerVariant),
            rawStatus = null,
            partial = false,
            errorMessage = null,
            nowMs = now
        )

        assertEquals(EpisodeSourceResponseState.AVAILABLE, result.state)
        assertEquals(5, result.apiCandidateCount)
        assertEquals(1, result.sources.size)
        assertEquals(4, result.exactDuplicateCount)
        assertEquals("Provider B", result.sources.single().displayProviderName)
        assertEquals("720p", result.sources.single().quality)
    }

    @Test
    fun partialFlagIsPreservedAndUnconfirmedEmptyIsNotReportedAsConfirmedEmpty() {
        val candidate = ProphecySourceDto(id = "", label = "Provider", language = null)
        val partial = EpisodeSourceAggregator.aggregate(listOf(candidate), null, true, null, now)
        val unknown = EpisodeSourceAggregator.aggregate(emptyList(), null, null, null, now)

        assertEquals(EpisodeSourceResponseState.PARTIAL, partial.state)
        assertEquals(1, partial.sources.size)
        assertEquals("Provider", partial.sources.single().displayProviderName)
        assertEquals(0, partial.playableCandidateCount)
        assertEquals(EpisodeSourceResponseState.UNKNOWN, unknown.state)
        assertEquals(0, unknown.sources.size)
    }

    @Test
    fun timeoutRemainsDistinctEvenWhenTheResponseIsAlsoPartial() {
        val result = EpisodeSourceAggregator.aggregate(
            apiCandidates = emptyList(),
            rawStatus = null,
            partial = true,
            errorMessage = "Upstream request timed out",
            nowMs = now
        )
        assertEquals(EpisodeSourceResponseState.TIMEOUT, result.state)
    }

    @Test
    fun existingStatusNamesMapToTheirMatchingStatesWithoutTreatingUnknownAsEmpty() {
        EpisodeSourceResponseState.values().forEach { expected ->
            val result = EpisodeSourceAggregator.aggregate(
                apiCandidates = if (expected == EpisodeSourceResponseState.AVAILABLE) listOf(ProphecySourceDto(id = "s")) else emptyList(),
                rawStatus = expected.name.lowercase(),
                partial = null,
                errorMessage = null,
                nowMs = now
            )
            assertEquals(expected, result.state)
        }
    }

    @Test
    fun moshiKeepsCandidatesWithMissingOptionalFieldsAndIgnoresUnknownFields() {
        val json = """{
            "sources": [
              {"id": "source-1", "label": "Provider", "quality": "1080p", "extra_future_field": true},
              {"quality": "720p"}
            ],
            "sources_status": "partial",
            "sources_partial": true
        }"""
        val response = Moshi.Builder().build()
            .adapter(ProphecyEpisodeFullResponse::class.java)
            .fromJson(json)!!

        assertEquals(2, response.sources?.size)
        assertEquals("source-1", response.sources?.first()?.id)
        assertEquals("", response.sources?.last()?.id)
        assertEquals("Unknown provider", response.sources?.last()?.displayProviderName)
        assertEquals("Unknown", response.sources?.last()?.displayAudioLanguage)
        assertEquals(true, response.sourcesPartial)
    }

    @Test(expected = JsonDataException::class)
    fun malformedOptionalCandidateTypeIsAParseFailureRatherThanAnEmptyResult() {
        Moshi.Builder().build()
            .adapter(ProphecyEpisodeFullResponse::class.java)
            .fromJson("""{"sources":[{"id":"source-1","size_bytes":"not-a-number"}]}""")
    }

    @Test
    fun expiredCandidatesAreRemovedAndCountedWithoutGuessingAtUnknownExpiry() {
        val expired = ProphecySourceDto(id = "expired", expiresAt = "2026-11-08T23:59:59Z")
        val future = ProphecySourceDto(id = "future", expiresAt = "2026-11-09T00:01:00Z")
        val malformed = ProphecySourceDto(id = "unknown-expiry", expiresAt = "not-a-date")

        val result = EpisodeSourceAggregator.aggregate(
            listOf(expired, future, malformed), null, false, null, now
        )

        assertEquals(listOf("future", "unknown-expiry"), result.sources.map { it.id })
        assertEquals(1, result.expiredCandidateCount)
        assertEquals(EpisodeSourceResponseState.AVAILABLE, result.state)
    }

    @Test
    fun expiryParserSupportsFractionalSecondsAndTimezoneOffsetsOnMinSdk24() {
        assertEquals(
            now + 123L,
            SourceExpiryTime.parseEpochMillis("2026-11-09T00:00:00.123456789Z")
        )
        assertEquals(
            now,
            SourceExpiryTime.parseEpochMillis("2026-11-09T01:00:00+01:00")
        )
        assertEquals(null, SourceExpiryTime.parseEpochMillis("not-a-date"))
    }

    @Test
    fun failedRefreshReturnsOnlyUnexpiredCachedCandidatesMarkedStale() {
        val snapshot = AnimeRepository.EpisodeSourceSnapshot(
            sources = listOf(
                ProphecySourceDto(id = "still-valid"),
                ProphecySourceDto(id = "expired", expiresAt = "2026-11-08T23:59:59Z")
            ),
            state = EpisodeSourceResponseState.AVAILABLE,
            fetchedAtMs = now - 1_000L,
            seasonNumber = 2,
            episodeNumber = 41,
            apiCandidateCount = 2
        )

        val fallback = EpisodeSourceCachePolicy.staleAfterRefreshFailure(
            snapshot = snapshot,
            message = "Refresh failed; candidates are unverified.",
            nowMs = now
        )

        assertEquals(EpisodeSourceResponseState.STALE, fallback.state)
        assertEquals(listOf("still-valid"), fallback.sources.map { it.id })
        assertEquals(1, fallback.expiredCandidateCount)
        assertEquals("Refresh failed; candidates are unverified.", fallback.errorMessage)
        assertEquals(2, fallback.seasonNumber)
        assertEquals(41, fallback.episodeNumber)
    }

    @Test
    fun partialRefreshMergesCandidatesButExplicitCompleteRefreshReplacesThem() {
        val oldCandidate = ProphecySourceDto(id = "old")
        val oldVariant = ProphecySourceDto(id = "same", label = "Old label", quality = "720p")
        val newVariant = ProphecySourceDto(id = "same", label = "New label", quality = "1080p")
        val expired = ProphecySourceDto(id = "expired", expiresAt = "2026-11-08T23:59:59Z")

        val partial = EpisodeSourceCachePolicy.mergeRefreshCandidates(
            existingCandidates = listOf(oldCandidate, oldVariant, expired),
            incomingCandidates = listOf(newVariant),
            authoritativeComplete = false,
            nowMs = now
        )
        assertEquals(setOf("old", "same"), partial.map { it.id }.toSet())
        assertEquals(1, partial.count { it.id == "same" })
        assertEquals("New label", partial.single { it.id == "same" }.label)

        val complete = EpisodeSourceCachePolicy.mergeRefreshCandidates(
            existingCandidates = listOf(oldCandidate, oldVariant),
            incomingCandidates = listOf(newVariant),
            authoritativeComplete = true,
            nowMs = now
        )
        assertEquals(listOf(newVariant), complete)
    }

    @Test
    fun sourceCacheIdentitySeparatesAnimeSeasonAndDelimitedEpisodeIds() {
        val base = EpisodeSourceCacheIdentity.key("anime:x", 1, "ep:y:z")
        assertFalse(base == EpisodeSourceCacheIdentity.key("anime:x", 2, "ep:y:z"))
        assertFalse(base == EpisodeSourceCacheIdentity.key("anime:y", 1, "ep:y:z"))
        assertFalse(base == EpisodeSourceCacheIdentity.key("anime:x", null, "ep:y:z"))
        assertFalse(
            EpisodeSourceCacheIdentity.key("a", 12, "3:ep") ==
                EpisodeSourceCacheIdentity.key("a", 1, "2:3:ep")
        )
    }

    @Test
    fun sourceRetryBackoffIsExponentialAndJitterIsBounded() {
        assertEquals(2_000L, EpisodeSourceRetryBackoff.delayMs(0))
        assertEquals(2_500L, EpisodeSourceRetryBackoff.delayMs(1, 10_000L))
        assertEquals(4_000L, EpisodeSourceRetryBackoff.delayMs(2))
        assertEquals(150_000L, EpisodeSourceRetryBackoff.delayMs(9, 100_000L))
        assertTrue(EpisodeSourceRetryBackoff.shouldDelay(forceRefresh = false, remainingMs = 1L))
        assertFalse(EpisodeSourceRetryBackoff.shouldDelay(forceRefresh = true, remainingMs = 1L))
        assertFalse(EpisodeSourceRetryBackoff.shouldDelay(forceRefresh = false, remainingMs = 0L))
    }

    @Test
    fun preparedPlaybackExpiresAtBackendExpiryEvenBeforeTheCacheTtl() {
        val prepared = PreResolveQueueManager.PreparedEpisode(
            backendAnimeId = "backend-title",
            episodeId = "episode-1",
            source = ProphecySourceDto(id = "source-1", label = "Provider"),
            directData = com.example.data.remote.DirectPlayData(
                sourceId = "source-1",
                playbackUrl = "https://example.invalid/stream.m3u8",
                expiresAt = "2026-11-08T23:59:59Z"
            ),
            resolvedAtMs = now - 1_000L
        )
        assertTrue(prepared.isExpired(now))
    }

    @Test
    fun preparedPlaybackRemainsValidBeforeBothBackendAndLocalExpiry() {
        val prepared = PreResolveQueueManager.PreparedEpisode(
            backendAnimeId = "backend-title",
            episodeId = "episode-1",
            source = ProphecySourceDto(id = "source-1", label = "Provider"),
            directData = com.example.data.remote.DirectPlayData(
                sourceId = "source-1",
                playbackUrl = "https://example.invalid/stream.m3u8",
                expiresAt = "2026-11-09T00:01:00Z"
            ),
            resolvedAtMs = now - 1_000L
        )
        assertFalse(prepared.isExpired(now))
        assertTrue(prepared.copy(resolvedAtMs = now - PreResolveQueueManager.CACHE_TTL_MS - 1L).isExpired(now))
    }

    @Test
    fun directPlayResponseParsingRetainsResolvedSourceAndRequiredHeaders() {
        val response = Moshi.Builder().build().adapter(DirectPlayResponse::class.java).fromJson(
            """{"success":true,"data":{"source_id":"source-7","playback_url":"https://example.invalid/v.m3u8","url_type":"direct","requires_headers":true,"headers":[["Referer","https://provider.invalid/"]]},"meta":{"request_id":"req-1"}}"""
        )!!
        assertTrue(response.success)
        assertEquals("source-7", response.data?.sourceId)
        assertEquals("https://example.invalid/v.m3u8", response.data?.playbackUrl)
        assertEquals(listOf(listOf("Referer", "https://provider.invalid/")), response.data?.headers)
    }

    @Test
    fun partialCatalogMergePreservesOmittedRowsAndReplacesMatchingMetadata() {
        val oldSeason = SeasonEntry(id = "anime_s1", animeId = "anime", seasonNumber = 1, title = "Old")
        val oldEpisode = EpisodeEntry(id = "ep-1", animeId = "anime", seasonNumber = 1, episodeNumber = 1, title = "Old title")
        val retainedEpisode = EpisodeEntry(id = "ep-2", animeId = "anime", seasonNumber = 1, episodeNumber = 2)
        val newSeason = oldSeason.copy(title = "Updated")
        val newEpisode = oldEpisode.copy(title = "Updated title")
        val partial = EpisodeCatalogSnapshotMerger.merge(
            existing = EpisodeCatalogSnapshot(listOf(oldSeason), listOf(oldEpisode, retainedEpisode)),
            incoming = EpisodeCatalogSnapshot(listOf(newSeason), listOf(newEpisode)),
            authoritative = false
        )

        assertEquals("Updated", partial.seasons.single().title)
        assertEquals("Updated title", partial.episodes.first { it.id == "ep-1" }.title)
        assertTrue(partial.episodes.any { it.id == "ep-2" })

        val complete = EpisodeCatalogSnapshotMerger.merge(
            existing = EpisodeCatalogSnapshot(listOf(oldSeason), listOf(oldEpisode, retainedEpisode)),
            incoming = EpisodeCatalogSnapshot(listOf(newSeason), listOf(newEpisode)),
            authoritative = true
        )
        assertEquals(1, complete.episodes.size)
        assertFalse(complete.episodes.any { it.id == "ep-2" })
    }

    @Test
    fun emptyPartialMovieSnapshotRetainsRealSeasonOneEpisodeOneIdentity() {
        val movieId = "backend-movie-id"
        val episode = EpisodeEntry(
            id = "backend-episode-id",
            animeId = movieId,
            seasonNumber = 1,
            episodeNumber = 1,
            title = "Complete Movie"
        )
        val existing = EpisodeCatalogSnapshot(
            seasons = listOf(SeasonEntry(id = "${movieId}_s1", animeId = movieId, seasonNumber = 1)),
            episodes = listOf(episode)
        )
        val partialEmpty = EpisodeCatalogSnapshotMerger.merge(
            existing = existing,
            incoming = EpisodeCatalogSnapshot(emptyList(), emptyList()),
            authoritative = false
        )
        assertEquals(listOf("${movieId}_s1"), partialEmpty.seasons.map { it.id })
        assertEquals(listOf("backend-episode-id"), partialEmpty.episodes.map { it.id })
        assertEquals(1, partialEmpty.episodes.single().seasonNumber)
        assertEquals(1, partialEmpty.episodes.single().episodeNumber)

        val authoritativeEmpty = EpisodeCatalogSnapshotMerger.merge(
            existing = existing,
            incoming = EpisodeCatalogSnapshot(emptyList(), emptyList()),
            authoritative = true
        )
        assertTrue(authoritativeEmpty.seasons.isEmpty())
        assertTrue(authoritativeEmpty.episodes.isEmpty())
    }

    @Test
    fun persistedSourceCandidateSchemaHasNoPlaybackUrlOrCredentialFields() {
        val fieldNames = EpisodeSourceCandidateRecord::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name.lowercase() }
        val prohibited = listOf("url", "header", "token", "signature", "cookie", "credential", "playback")
        prohibited.forEach { fragment -> assertFalse(fieldNames.any { it.contains(fragment) }) }
    }

    @Test
    fun quotaCooldownIsExponentialBoundedAndResetOnSuccess() {
        val cooldown = FirestoreQuotaCooldown(baseDelayMs = 1_000L, maxDelayMs = 5_000L)
        assertEquals(1_000L, cooldown.recordQuotaFailure(nowMs = 10_000L, jitterMs = 100L))
        assertEquals(1_100L, cooldown.remainingMs(10_000L))
        assertEquals(100L, cooldown.remainingMs(11_000L))
        assertEquals(2_000L, cooldown.recordQuotaFailure(nowMs = 11_100L, jitterMs = 0L))
        assertEquals(4_000L, cooldown.recordQuotaFailure(nowMs = 13_100L, jitterMs = 0L))
        assertEquals(5_000L, cooldown.recordQuotaFailure(nowMs = 17_100L, jitterMs = 5_000L))
        assertEquals(5_000L, cooldown.remainingMs(17_100L))
        cooldown.recordSuccess()
        assertEquals(0L, cooldown.remainingMs(17_101L))
    }

    @Test
    fun firestoreQuotaPermissionAuthUnavailableTimeoutAndNetworkRemainDistinct() {
        assertEquals(
            FirestoreFailureCategory.RESOURCE_EXHAUSTED,
            FirestoreRequestGuard.classifyFirestoreCodeName("RESOURCE_EXHAUSTED")
        )
        assertEquals(
            FirestoreFailureCategory.PERMISSION_DENIED,
            FirestoreRequestGuard.classifyFirestoreCodeName("PERMISSION_DENIED")
        )
        assertEquals(
            FirestoreFailureCategory.UNAUTHENTICATED,
            FirestoreRequestGuard.classifyFirestoreCodeName("UNAUTHENTICATED")
        )
        assertEquals(
            FirestoreFailureCategory.UNAVAILABLE,
            FirestoreRequestGuard.classifyFirestoreCodeName("UNAVAILABLE")
        )
        assertEquals(
            FirestoreFailureCategory.DEADLINE_EXCEEDED,
            FirestoreRequestGuard.classifyFirestoreCodeName("DEADLINE_EXCEEDED")
        )
        assertEquals(
            FirestoreFailureCategory.DEADLINE_EXCEEDED,
            FirestoreRequestGuard.classify(java.net.SocketTimeoutException("timed out"))
        )
        assertEquals(FirestoreFailureCategory.NETWORK_ERROR, FirestoreRequestGuard.classify(java.io.IOException("offline")))
        assertEquals(FirestoreFailureCategory.UNKNOWN_ERROR, FirestoreRequestGuard.classify(IllegalStateException("unknown")))
    }
}
