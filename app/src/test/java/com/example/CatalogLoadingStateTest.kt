package com.example

import com.example.data.repository.withLoadingFlag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogLoadingStateTest {
    @Test
    fun successAndEmptyResultsAlwaysFinishLoading() = runTest {
        val states = mutableListOf<Boolean>()
        assertEquals("loaded", withLoadingFlag({ states.add(it) }) { "loaded" })
        assertEquals(listOf(true, false), states)

        states.clear()
        assertEquals(null, withLoadingFlag({ states.add(it) }) { null })
        assertEquals(listOf(true, false), states)
    }

    @Test
    fun exceptionAlwaysFinishesLoadingAndPropagates() = runTest {
        val states = mutableListOf<Boolean>()
        var thrown: Throwable? = null
        try {
            withLoadingFlag({ states.add(it) }) { throw IllegalStateException("catalog failed") }
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue(thrown is IllegalStateException)
        assertEquals("catalog failed", thrown?.message)
        assertEquals(listOf(true, false), states)
    }

    @Test
    fun cancellationAlwaysFinishesLoadingAndRemainsCancellation() = runTest {
        val states = mutableListOf<Boolean>()
        var cancelled = false
        try {
            withLoadingFlag({ states.add(it) }) { throw CancellationException("cancelled") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(listOf(true, false), states)
        assertFalse(states.last())
    }
}
