package com.example

import com.example.data.repository.EpisodeResolutionCoordinator
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EpisodeResolutionCoordinatorTest {
    @Test
    fun simultaneousRequestsForSameEpisodeShareOneOperation() = runBlocking {
        val calls = AtomicInteger(0)
        suspend fun resolve() = EpisodeResolutionCoordinator.resolve("test:same-episode") {
            calls.incrementAndGet()
            delay(50)
            "verified"
        }
        val first = async { resolve() }
        val second = async { resolve() }
        assertEquals("verified", first.await())
        assertEquals("verified", second.await())
        assertEquals(1, calls.get())
    }
}
