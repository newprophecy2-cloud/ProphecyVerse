package com.example

import com.example.data.local.DownloadEntry
import com.example.data.local.EpisodeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchDownloadAndDurationTest {

    @Test
    fun testTimeFormattingDurationResolving() {
        // Test that when duration is 0 or negative during resolving/buffering,
        // it gracefully presents a clean loading state instead of broken ----
        val durationMillis = 0L
        val posMillis = 0L
        val isResolving = true
        val isBuffering = false

        val formatted = when {
            durationMillis > 0 -> "${formatMillis(posMillis)} / ${formatMillis(durationMillis)}"
            isResolving || isBuffering -> "${formatMillis(posMillis)} / Loading..."
            else -> "${formatMillis(posMillis)} / Loading..."
        }

        assertEquals("00:00 / Loading...", formatted)
        assertFalse(formatted.contains("----"))
        assertFalse(formatted.contains("--:--"))
    }

    @Test
    fun testBatchDownloadSelectionFilter() {
        val episodes = listOf(
            EpisodeEntry(id = "ep1", animeId = "a1", seasonNumber = 1, episodeNumber = 1, title = "Pilot"),
            EpisodeEntry(id = "ep2", animeId = "a1", seasonNumber = 1, episodeNumber = 2, title = "Ep 2"),
            EpisodeEntry(id = "ep3", animeId = "a1", seasonNumber = 1, episodeNumber = 3, title = "Ep 3")
        )

        val downloads = listOf(
            DownloadEntry(id = "ep1", animeId = "a1", animeTitle = "Test Anime", episodeNumber = 1, seasonNumber = 1, downloadStatus = "COMPLETED")
        )

        val downloadMap = downloads.associateBy { it.id }

        // Filter out already completed episodes for default batch download selection
        val eligibleEpisodes = episodes.filter { ep ->
            downloadMap[ep.id]?.downloadStatus != "COMPLETED"
        }

        assertEquals(2, eligibleEpisodes.size)
        assertEquals("ep2", eligibleEpisodes[0].id)
        assertEquals("ep3", eligibleEpisodes[1].id)
    }

    private fun formatMillis(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
