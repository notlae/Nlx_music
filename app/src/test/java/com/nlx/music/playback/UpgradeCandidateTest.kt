package com.nlx.music.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeCandidateTest {

    private data class Candidate(val url: String, val durationSec: Int)

    @Test
    fun aMismatchedCandidateDoesNotBlockTheNextOne() = runBlocking {
        val calls = mutableListOf<Set<String>>()
        val selected = selectDurationCompatibleUpgrade(
            expectedSec = 149,
            urlOf = { it.url },
            durationSecOf = { it.durationSec },
        ) { excluded ->
            calls += excluded.toSet()
            when {
                "long" !in excluded -> Candidate("long", 248)
                else -> Candidate("match", 151)
            }
        }
        assertEquals("match", selected?.url)
        assertEquals(listOf(emptySet(), setOf("long")), calls)
    }

    @Test
    fun everyMismatchLeavesPlaybackUnchanged() = runBlocking {
        val selected = selectDurationCompatibleUpgrade(
            expectedSec = 291,
            urlOf = { it.url },
            durationSecOf = { it.durationSec },
        ) { excluded ->
            val next = listOf("a" to 236, "b" to 400, "c" to 10).firstOrNull { it.first !in excluded }
            next?.let { Candidate(it.first, it.second) }
        }
        assertNull(selected)
    }

    @Test
    fun theAttemptCapIsHonored() = runBlocking {
        var calls = 0
        val selected = selectDurationCompatibleUpgrade(
            expectedSec = 100,
            maxAttempts = 3,
            urlOf = { it.url },
            durationSecOf = { it.durationSec },
        ) {
            calls++
            Candidate("again-$calls", 400)
        }
        assertNull(selected)
        assertEquals(3, calls)
    }

    @Test
    fun aRepeatedUrlStopsTheWalk() = runBlocking {
        var calls = 0
        val selected = selectDurationCompatibleUpgrade(
            expectedSec = 100,
            urlOf = { it.url },
            durationSecOf = { it.durationSec },
        ) {
            calls++
            Candidate("same", 400)
        }
        assertNull(selected)
        assertTrue(calls < UPGRADE_DURATION_ATTEMPTS)
    }

    @Test
    fun thirtyFiveSecondsIsStillTheCeiling() {
        assertTrue(durationCompatible(183, 183 + 35))
        assertTrue(!durationCompatible(183, 183 + 36))
    }

    @Test
    fun aTenSecondCeilingRejectsANearEditAndTakesTheNext() = runBlocking {
        val selected = selectDurationCompatibleUpgrade(
            expectedSec = 200,
            toleranceSec = 10,
            urlOf = { it.url },
            durationSecOf = { it.durationSec },
        ) { excluded ->
            when {
                "near" !in excluded -> Candidate("near", 220)
                else -> Candidate("close", 206)
            }
        }
        assertEquals("close", selected?.url)
    }
}
