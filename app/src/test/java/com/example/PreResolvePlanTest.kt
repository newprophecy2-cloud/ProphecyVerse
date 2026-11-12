package com.example

import com.example.data.local.EpisodeEntry
import com.example.player.PreResolvePlan
import com.example.player.PreResolveQueueManager
import com.example.player.PreResolveQueueManager.ResolutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreResolvePlanTest {
    private fun ep(season: Int, number: Int, id: String = "s${season}e$number") =
        EpisodeEntry(id, "anime", season, number)

    @Test
    fun queueContinuesPastTheSeventeenthEpisodeInMultipleControlledBatches() {
        val records = (1..42).map { ep(1, it) }
        val upcoming = PreResolvePlan.upcoming(records.first(), records)
        val batches = PreResolvePlan.batches(upcoming)
        assertEquals(41, upcoming.size)
        assertEquals(listOf(17, 17, 7), batches.map { it.size })
        assertEquals(upcoming, batches.flatten())
    }

    @Test
    fun sameEpisodeNumberInMultipleSeasonsRemainsASeparateOrderedIdentity() {
        val records = listOf(ep(1, 1), ep(1, 2), ep(2, 1), ep(3, 1))
        val upcoming = PreResolvePlan.upcoming(records[0], records)
        assertEquals(listOf("s1e2", "s2e1", "s3e1"), upcoming.map { it.id })
        assertEquals(setOf(1, 2, 3), (listOf(records[0]) + upcoming).filter { it.episodeNumber == 1 }.map { it.seasonNumber }.toSet())
        val sameNumberIds = records.filter { it.episodeNumber == 1 }.map {
            PreResolveQueueManager.identity("backend-anime", it)
        }
        assertEquals(3, sameNumberIds.distinct().size)
    }

    @Test
    fun duplicateSameSeasonEpisodeIsNotQueuedTwice() {
        val current = ep(1, 1)
        val upcoming = PreResolvePlan.upcoming(current, listOf(current, ep(1, 2), ep(1, 2, "alternate-backend-id"), ep(2, 1)))
        assertEquals(2, upcoming.size)
        assertTrue(upcoming.map { it.id }.distinct().size == upcoming.size)
    }
    @Test
    fun queuedEpisodeCannotBecomeVerifiedPlayableWithoutRealSuccessfulResolution() {
        assertEquals(ResolutionStatus.FAILED, PreResolveQueueManager.completionStatus(false))
        assertEquals(ResolutionStatus.VERIFIED_PLAYABLE, PreResolveQueueManager.completionStatus(true))
    }
}
