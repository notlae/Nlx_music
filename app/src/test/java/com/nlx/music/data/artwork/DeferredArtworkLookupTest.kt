package com.nlx.music.data.artwork

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeferredArtworkLookupTest {

    @Test
    fun resolveWaitsThreeSeconds() = runTest {
        val resolved = mutableListOf<String>()
        val lookup = lookup(resolved = resolved, memory = emptySet())
        lookup.request("Khwaja Mere Khwaja", "A. R. Rahman")
        assertTrue(resolved.isEmpty())
        advanceTimeBy(2_999)
        runCurrent()
        assertTrue(resolved.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("Khwaja Mere Khwaja"), resolved)
    }

    @Test
    fun aNewTrackCancelsThePendingLookup() = runTest {
        val resolved = mutableListOf<String>()
        val lookup = lookup(resolved = resolved, memory = emptySet())
        lookup.request("Arziyan", "Javed Ali")
        advanceTimeBy(1_000)
        lookup.request("Khwaja Mere Khwaja", "A. R. Rahman")
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf("Khwaja Mere Khwaja"), resolved)
    }

    @Test
    fun aMemoryHitDoesNotWaitOrSearch() = runTest {
        val resolved = mutableListOf<String>()
        val key = ArtworkNormalizer.cacheKey("Cached", "Artist")
        val lookup = lookup(resolved = resolved, memory = setOf(key))
        lookup.request("Cached", "Artist")
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(resolved.isEmpty())
    }

    private fun kotlinx.coroutines.test.TestScope.lookup(
        resolved: MutableList<String>,
        memory: Set<String>,
    ) = DeferredArtworkLookup(
        scope = this,
        source = "test",
        settleMs = 3_000,
        hasMemoryHit = { it in memory },
        resolve = { name, _ -> resolved += name },
        log = { _, _ -> },
    )
}
