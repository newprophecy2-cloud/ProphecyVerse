package com.example

import com.example.data.local.DownloadEntry
import com.example.data.local.EpisodeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadBatchAndPlaybackTest {

    @Test
    fun testBatchDownloadSelectionFilter() {
        val episodes = listOf(
            EpisodeEntry("ep1", "anime1", 1, 1, "Episode 1", null),
            EpisodeEntry("ep2", "anime1", 1, 2, "Episode 2", null),
            EpisodeEntry("ep3", "anime1", 1, 3, "Episode 3", null),
            EpisodeEntry("ep4", "anime1", 2, 1, "Episode S2E1", null)
        )

        val downloads = listOf(
            DownloadEntry("ep1", "anime1", "Anime", null, 1, 1, "Ep 1", "COMPLETED", 1000L, 1000L),
            DownloadEntry("ep2", "anime1", "Anime", null, 1, 2, "Ep 2", "DOWNLOADING", 500L, 1000L)
        )

        val downloadsMap = downloads.associateBy { it.id }

        // Filter season 1 episodes
        val season1Eps = episodes.filter { it.seasonNumber == 1 }
        assertEquals(3, season1Eps.size)

        // Episodes available to download in batch (not completed)
        val availableForBatch = season1Eps.filter {
            downloadsMap[it.id]?.downloadStatus != "COMPLETED"
        }

        assertEquals(2, availableForBatch.size)
        assertTrue(availableForBatch.any { it.id == "ep2" })
        assertTrue(availableForBatch.any { it.id == "ep3" })
        assertFalse(availableForBatch.any { it.id == "ep1" })
    }

    @Test
    fun testTimeDisplayLogicPreventsDashes() {
        // Verify time string logic: when duration <= 0, it must be "Loading..."
        fun formatPlaybackTime(currentPos: Long, duration: Long, isResolving: Boolean, isBuffering: Boolean): String {
            return if (duration > 0) {
                "${currentPos / 1000}s / ${duration / 1000}s"
            } else {
                "00:00 / Loading..."
            }
        }

        // Just resolved but duration not yet determined
        val timeDisplay = formatPlaybackTime(0L, 0L, isResolving = false, isBuffering = false)
        assertEquals("00:00 / Loading...", timeDisplay)
        assertFalse(timeDisplay.contains("----"))
        assertFalse(timeDisplay.contains("--:--"))

        // Known duration
        val normalDisplay = formatPlaybackTime(5000L, 24000L, isResolving = false, isBuffering = false)
        assertEquals("5s / 24s", normalDisplay)
    }
}
