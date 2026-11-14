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
    fun queueContainsNext17AndSlidesTo18And19() {
        val records = (1..42).map { ep(1, it) }
        val ep1Upcoming = PreResolvePlan.upcoming(records.first(), records)
        val batches = PreResolvePlan.batches(ep1Upcoming)
        assertEquals(17, ep1Upcoming.size)
        assertEquals(listOf(17), batches.map { it.size })
        // Episode 1 + next 17 = Episodes 1..18 prepared
        assertEquals((2..18).map { "s1e$it" }, ep1Upcoming.map { it.id })
        assertEquals(ep1Upcoming, batches.flatten())

        // Advancing to Ep 2 brings Ep 19 into window
        val ep2Upcoming = PreResolvePlan.upcoming(records[1], records)
        assertEquals((3..19).map { "s1e$it" }, ep2Upcoming.map { it.id })
        assertEquals("s1e19", ep2Upcoming.last().id)

        // Advancing to Ep 3 brings Ep 20 into window
        val ep3Upcoming = PreResolvePlan.upcoming(records[2], records)
        assertEquals((4..20).map { "s1e$it" }, ep3Upcoming.map { it.id })
        assertEquals("s1e20", ep3Upcoming.last().id)
    }

    @Test
    fun sameEpisodeNumberInMultipleSeasonsRemainsASeparateOrderedIdentity() {
        val records = listOf(ep(1, 1), ep(1, 2), ep(2, 1), ep(3, 1))
        val upcoming = PreResolvePlan.upcoming(records[0], records)
        assertEquals(listOf("s1e2"), upcoming.map { it.id })
        assertEquals(setOf(1), (listOf(records[0]) + upcoming).filter { it.episodeNumber == 1 }.map { it.seasonNumber }.toSet())
        val sameNumberIds = records.filter { it.episodeNumber == 1 }.map {
            PreResolveQueueManager.identity("anilist_1", it)
        }
        assertEquals(3, sameNumberIds.distinct().size)
    }

    @Test
    fun duplicateSameSeasonEpisodeIsNotQueuedTwice() {
        val current = ep(1, 1)
        val upcoming = PreResolvePlan.upcoming(current, listOf(current, ep(1, 2), ep(1, 2, "alternate-backend-id"), ep(2, 1)))
        assertEquals(1, upcoming.size)
        assertTrue(upcoming.map { it.id }.distinct().size == upcoming.size)
    }

    @Test
    fun episodeSeventeen_hasFastSeven_backgroundTen_andPreviousSix() {
        val records = (1..50).map { ep(1, it) }
        val current = records[16]
        assertEquals((18..34).toList(), PreResolvePlan.upcoming(current, records).map { it.episodeNumber })
        assertEquals((18..24).toList(), PreResolvePlan.fastUpcoming(current, records).map { it.episodeNumber })
        assertEquals((25..34).toList(), PreResolvePlan.backgroundUpcoming(current, records).map { it.episodeNumber })
        assertEquals((11..16).toList(), PreResolvePlan.previous(current, records).map { it.episodeNumber })
    }

    @Test
    fun veryLargeCatalogOnlyBuildsBoundedSameSeasonWindows() {
        val records = (1..1200).map { ep(1, it) } + (1..1200).map { ep(2, it, "s2e$it") }
        val current = records[599]
        val started = System.nanoTime()
        val upcoming = PreResolvePlan.upcoming(current, records)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(17, upcoming.size)
        assertEquals(setOf(1), upcoming.map { it.seasonNumber }.toSet())
        assertTrue("Window planning should remain bounded; took ${elapsedMs}ms", elapsedMs < 1000)
    }

    @Test
    fun queuedEpisodeCannotBecomeVerifiedPlayableWithoutRealSuccessfulResolution() {
        assertEquals(ResolutionStatus.FAILED, PreResolveQueueManager.completionStatus(false))
        assertEquals(ResolutionStatus.VERIFIED_PLAYABLE, PreResolveQueueManager.completionStatus(true))
    }
}
