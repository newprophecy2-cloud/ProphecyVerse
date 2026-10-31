package com.example

import com.example.data.local.EpisodeEntry
import com.example.data.remote.DirectPlayData
import com.example.data.remote.ProphecySourceDto
import com.example.player.PreResolveQueueManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreResolveQueueManagerTest {

    @Test
    fun testWindowSizeConstant() {
        assertEquals(17, PreResolveQueueManager.WINDOW_SIZE)
        assertEquals(25 * 60 * 1000L, PreResolveQueueManager.CACHE_TTL_MS)
    }

    @Test
    fun testPreparedEpisodeTtlExpiration() {
        val now = System.currentTimeMillis()
        val freshEpisode = PreResolveQueueManager.PreparedEpisode(
            episodeId = "ep_1",
            source = ProphecySourceDto(id = "src_1"),
            directData = DirectPlayData(sourceId = "src_1", playbackUrl = "https://example.com/video.mp4"),
            resolvedAtMs = now
        )
        assertFalse(freshEpisode.isExpired())

        val expiredEpisode = PreResolveQueueManager.PreparedEpisode(
            episodeId = "ep_old",
            source = ProphecySourceDto(id = "src_old"),
            directData = DirectPlayData(sourceId = "src_old", playbackUrl = "https://example.com/old.mp4"),
            resolvedAtMs = now - (26 * 60 * 1000L) // 26 minutes ago (> 25 min TTL)
        )
        assertTrue(expiredEpisode.isExpired())
    }

    @Test
    fun testSlidingWindowCalculation() {
        val allEpisodes = (1..50).map { i ->
            EpisodeEntry(
                id = "ep_$i",
                animeId = "anime_one_piece",
                seasonNumber = 22,
                episodeNumber = i,
                title = "Episode $i"
            )
        }

        val currentIndex = allEpisodes.indexOfFirst { it.id == "ep_5" }
        val targetEpisodes = allEpisodes
            .drop(currentIndex + 1)
            .take(PreResolveQueueManager.WINDOW_SIZE)

        assertEquals(17, targetEpisodes.size)
        assertEquals("ep_6", targetEpisodes.first().id)
        assertEquals("ep_22", targetEpisodes.last().id)
    }
}
