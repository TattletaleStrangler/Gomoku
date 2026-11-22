package com.oldmermaidman.gomoku.net

import com.oldmermaidman.gomoku.model.Stone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client-side projection of the server's clocks (TZ §11.3).
 *
 * The class takes a monotonic reading as an argument, so these tests do not need a
 * device or a real clock: a snapshot arrives at t = 1000 and the checks are made at
 * t = 1000 + elapsed.
 */
class RemoteClockTest {

    private fun clock(
        black: Long? = 60_000L,
        white: Long? = 60_000L,
        running: Stone? = Stone.BLACK,
        serverTime: Long = 1_700_000_000_000L,
        receivedAt: Long = 1_000L,
    ) = RemoteClock(black, white, running, serverTime, receivedAt)

    @Test
    fun onlyTheRunningClockCountsDown() {
        val clock = clock()
        assertEquals(59_000L, clock.remainingMs(Stone.BLACK, 2_000L))
        assertEquals(60_000L, clock.remainingMs(Stone.WHITE, 2_000L))
    }

    @Test
    fun aStoppedClockKeepsItsValue() {
        val clock = clock(running = null)
        assertEquals(60_000L, clock.remainingMs(Stone.BLACK, 30_000L))
        assertEquals(60_000L, clock.remainingMs(Stone.WHITE, 30_000L))
    }

    @Test
    fun anUntimedGameHasNoClocks() {
        val clock = clock(black = null, white = null, running = null)
        assertFalse(clock.timed)
        assertNull(clock.remainingMs(Stone.BLACK, 1_000L))
        assertNull(clock.remainingMs(Stone.WHITE, 1_000L))
    }

    @Test
    fun theClockNeverGoesBelowZero() {
        val clock = clock(black = 1_000L, running = Stone.BLACK)
        assertEquals(0L, clock.remainingMs(Stone.BLACK, 5_000L))
        assertTrue(clock.isFlagged(Stone.BLACK, 5_000L))
    }

    @Test
    fun theLowTimeWarningUsesWhatIsLeft() {
        // 20 seconds at the arrival, 10 at the check: the warning starts at 10.
        val clock = clock(black = 20_000L, running = Stone.BLACK)
        assertFalse(clock.isLow(Stone.BLACK, 1_000L))
        assertTrue(clock.isLow(Stone.BLACK, 11_000L))
    }

    @Test
    fun serverNowIsTheArrivalTimePlusTheElapsedStretch() {
        val clock = clock(serverTime = 1_700_000_000_000L, receivedAt = 1_000L)
        assertEquals(1_700_000_001_500L, clock.serverNowMs(2_500L))
    }
}