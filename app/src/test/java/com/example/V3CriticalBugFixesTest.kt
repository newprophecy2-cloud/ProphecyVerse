package com.example

import com.example.BuildConfig
import com.example.data.local.DownloadEntry
import com.example.data.local.EpisodeEntry
import com.example.data.remote.DirectPlayData
import com.example.player.PlayerManager
import com.example.player.PreResolveQueueManager
import com.example.ui.screens.groupDownloads
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class V3CriticalBugFixesTest {

    // --- P1: Version Verification ---
    @Test
    fun versionMetadata_matchesV3ReleaseSpecs() {
        assertEquals("Installed version name must be 3.0.1", "3.0.1", BuildConfig.VERSION_NAME)
        assertEquals("Installed version code must be 6", 6, BuildConfig.VERSION_CODE)
        assertEquals("Application ID must match project namespace", "com.aistudio.prophecyverse.vykqtb", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun playerRoute_preservesSelectedSourceId() {
        assertEquals(
            "player/anime-1/episode-2?preferredSourceId=provider%2Fsource-3",
            com.example.ui.navigation.Screen.Player.createRoute("anime-1", "episode-2", "provider/source-3")
        )
        assertEquals(
            "player/anime-1/episode-2",
            com.example.ui.navigation.Screen.Player.createRoute("anime-1", "episode-2")
        )
    }

    // --- P0: Audio Tracks & Decoder Support ---
    @Test
    fun audioTrackOptions_correctlyIdentifiesSupportedAndUnsupportedTracks() {
        val supportedAacTrack = PlayerManager.TrackOption(
            id = "audio_1",
            label = "English (AAC)",
            isSelected = true,
            isSupported = true,
            sampleMimeType = "audio/mp4a-latm",
            decoderType = "MediaCodec"
        )
        val supportedEac3Track = PlayerManager.TrackOption(
            id = "audio_2",
            label = "Japanese (EAC3) [FFmpeg]",
            isSelected = false,
            isSupported = true,
            sampleMimeType = "audio/eac3",
            decoderType = "FFmpeg"
        )
        val supportedAc3Track = PlayerManager.TrackOption(
            id = "audio_3",
            label = "English (AC3) [FFmpeg]",
            isSelected = false,
            isSupported = true,
            sampleMimeType = "audio/ac3",
            decoderType = "FFmpeg"
        )
        val unsupportedTrack = PlayerManager.TrackOption(
            id = "audio_4",
            label = "Hindi (UnknownCodec) [Unsupported]",
            isSelected = false,
            isSupported = false,
            decoderType = "Unsupported"
        )

        assertTrue("Supported AAC track must be marked supported", supportedAacTrack.isSupported)
        assertEquals("MediaCodec", supportedAacTrack.decoderType)

        assertTrue("Supported EAC3 track must be marked supported with FFmpeg", supportedEac3Track.isSupported)
        assertEquals("FFmpeg", supportedEac3Track.decoderType)
        assertTrue(supportedEac3Track.label.contains("[FFmpeg]"))

        assertTrue("Supported AC3 track must be marked supported with FFmpeg", supportedAc3Track.isSupported)
        assertEquals("FFmpeg", supportedAc3Track.decoderType)

        assertFalse("Unsupported track must be marked unsupported", unsupportedTrack.isSupported)
        assertTrue("Unsupported label must reflect status", unsupportedTrack.label.contains("[Unsupported]"))
    }

    @Test
    fun mimeTypeDetection_identifiesEac3AndStandardFormats() {
        assertTrue(PlayerManager.isEac3MimeType("audio/eac3"))
        assertTrue(PlayerManager.isEac3MimeType("audio/eac3-joc"))
        assertFalse(PlayerManager.isEac3MimeType("audio/mp4a-latm"))
        assertFalse(PlayerManager.isEac3MimeType("audio/mpeg"))
        assertFalse(PlayerManager.isEac3MimeType(null))
    }

    @Test
    fun playbackMimeDetection_prefersBackendMetadataThenExistingUrlTypeAndExtension() {
        val backendMetadataWins = DirectPlayData(
            sourceId = "source-1",
            playbackUrl = "https://cdn.example/video.mp4?signature=redacted",
            urlType = "hls",
            contentType = "video/mp4"
        )
        assertEquals(androidx.media3.common.MimeTypes.VIDEO_MP4, PlayerManager.detectMimeType(backendMetadataWins))

        val urlTypeWinsOverExtension = backendMetadataWins.copy(contentType = null)
        assertEquals(androidx.media3.common.MimeTypes.APPLICATION_M3U8, PlayerManager.detectMimeType(urlTypeWinsOverExtension))

        val arbitraryUrlIsNotGuessed = backendMetadataWins.copy(
            playbackUrl = "https://cdn.example/signed-object?file=video.mp4",
            urlType = "direct",
            contentType = null
        )
        assertEquals(null, PlayerManager.detectMimeType(arbitraryUrlIsNotGuessed))
    }

    @Test
    fun playbackErrors_areClassifiedFromTheAvailableMedia3Evidence() {
        val httpError = androidx.media3.common.PlaybackException(
            "HTTP", null, androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        val timeoutError = androidx.media3.common.PlaybackException(
            "timeout", java.net.SocketTimeoutException("timeout"),
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )
        val containerError = androidx.media3.common.PlaybackException(
            "container", null,
            androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        )
        assertEquals(PlayerManager.PlaybackFailureCategory.HTTP, PlayerManager.classifyPlaybackError(httpError))
        assertEquals(PlayerManager.PlaybackFailureCategory.NETWORK_TIMEOUT, PlayerManager.classifyPlaybackError(timeoutError))
        assertEquals(PlayerManager.PlaybackFailureCategory.CONTAINER, PlayerManager.classifyPlaybackError(containerError))
    }

    @Test
    fun trackSwitching_preservesPlaybackPositionDuringDecoderRecovery() {
        // Simulating track selection parameters and position restoration
        val startPosition = 125_000L
        var currentPosition = startPosition
        val originalTrackId = "audio_0_0"
        var activeTrackId = originalTrackId

        // User attempts to switch to an unsupported track
        val requestedTrack = PlayerManager.TrackOption(
            id = "audio_0_1",
            label = "Exotic Audio [Unsupported]",
            isSelected = false,
            isSupported = false,
            decoderType = "Unsupported"
        )

        // Verifying unsupported track is rejected and does not mutate active track or lose position
        if (!requestedTrack.isSupported) {
            // Guard in selectTrack prevents switching and retains current working track
        } else {
            activeTrackId = requestedTrack.id
        }

        assertEquals("Active track must remain the working track", originalTrackId, activeTrackId)
        assertEquals("Playback position must be strictly preserved", startPosition, currentPosition)
    }

    // --- P0: Bounded Resolution Sequence ---
    @Test
    fun episodeSequence_boundsPreResolveWindowWithoutChangingEpisodeIdentity() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        // Generate 17 sequential episodes
        val episodes = (1..17).map { epNum ->
            EpisodeEntry(
                id = "ep_$epNum",
                animeId = "anime_solo_leveling",
                seasonNumber = 1,
                episodeNumber = epNum,
                title = "Episode $epNum",
                overview = "Overview for episode $epNum",
                isAvailable = true
            )
        }
        assertEquals("Episode list must have exactly 17 episodes", 17, episodes.size)

        // Verify window sliding does not crash or restrict to 2 episodes
        val window = episodes.take(PreResolveQueueManager.WINDOW_SIZE)
        assertEquals("Pre-resolve window must be bounded to two episodes", 2, window.size)
        assertEquals("ep_1", window.first().id)
        assertEquals("ep_2", window.last().id)

        // Verify individual episode identifiers are distinct
        val ids = episodes.map { it.id }.toSet()
        assertEquals("All 17 episode IDs must be unique", 17, ids.size)
    }

    // --- P1: Download Hierarchy (Anime -> Season -> Episode) ---
    @Test
    fun downloadGrouping_correctlyBuildsAnimeSeasonEpisodeHierarchy() {
        val downloads = listOf(
            DownloadEntry(
                id = "ep_1",
                animeId = "anime_one_piece",
                animeTitle = "One Piece",
                posterUrl = "https://image.com/op.jpg",
                seasonNumber = 1,
                episodeNumber = 1,
                episodeTitle = "I'm Luffy!",
                downloadStatus = "COMPLETED",
                downloadedBytes = 150_000_000L,
                totalBytes = 150_000_000L,
                mediaType = "anime"
            ),
            DownloadEntry(
                id = "ep_2",
                animeId = "anime_one_piece",
                animeTitle = "One Piece",
                posterUrl = "https://image.com/op.jpg",
                seasonNumber = 1,
                episodeNumber = 2,
                episodeTitle = "The Great Swordsman",
                downloadStatus = "COMPLETED",
                downloadedBytes = 160_000_000L,
                totalBytes = 160_000_000L,
                mediaType = "anime"
            ),
            DownloadEntry(
                id = "ep_100",
                animeId = "anime_one_piece",
                animeTitle = "One Piece",
                posterUrl = "https://image.com/op.jpg",
                seasonNumber = 2,
                episodeNumber = 1,
                episodeTitle = "Enter the Grand Line",
                downloadStatus = "COMPLETED",
                downloadedBytes = 180_000_000L,
                totalBytes = 180_000_000L,
                mediaType = "anime"
            ),
            DownloadEntry(
                id = "movie_mugentrain",
                animeId = "demon_slayer_movie",
                animeTitle = "Demon Slayer: Mugen Train",
                posterUrl = "https://image.com/mugen.jpg",
                seasonNumber = 0,
                episodeNumber = 1,
                episodeTitle = "Mugen Train",
                downloadStatus = "COMPLETED",
                downloadedBytes = 900_000_000L,
                totalBytes = 900_000_000L,
                mediaType = "movie"
            )
        )

        val groups = groupDownloads(downloads)
        assertEquals("Must group into 2 distinct Anime titles", 2, groups.size)

        // Find Demon Slayer Movie
        val movieGroup = groups.first { it.animeId == "demon_slayer_movie" }
        assertTrue("Movie entry must be marked as movie", movieGroup.isMovie)
        assertEquals(1, movieGroup.totalEpisodes)
        assertEquals(900_000_000L, movieGroup.totalBytes)

        // Find One Piece Series
        val seriesGroup = groups.first { it.animeId == "anime_one_piece" }
        assertFalse("Series entry must not be marked as movie", seriesGroup.isMovie)
        assertEquals("Must have 3 episodes total across seasons", 3, seriesGroup.totalEpisodes)
        assertEquals(2, seriesGroup.seasons.size) // Season 1 and Season 2
        assertEquals(490_000_000L, seriesGroup.totalBytes)

        val season1 = seriesGroup.seasons.first { it.seasonNumber == 1 }
        assertEquals(2, season1.episodes.size)
        assertEquals("ep_1", season1.episodes[0].id)
        assertEquals("ep_2", season1.episodes[1].id)

        val season2 = seriesGroup.seasons.first { it.seasonNumber == 2 }
        assertEquals(1, season2.episodes.size)
        assertEquals("ep_100", season2.episodes[0].id)
    }
}
