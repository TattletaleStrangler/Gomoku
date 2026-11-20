package com.oldmermaidman.gomoku.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the player's clock: main time, increment, flagging and the preset
 * controls themselves. The clock takes the current time as an argument, so these
 * tests need no Android and no waiting.
 */
class GameClockTest {

    private fun clock(
        control: TimeControl = TimeControl.BLITZ,
        remainingMs: Long = control.mainTimeMs
    ) = GameClock(control, remainingMs)

    @Test
    fun aStoppedClockKeepsItsTime() {
        val c = clock(remainingMs = 60_000)

        assertEquals(60_000L, c.remainingAt(1_000_000))
        assertFalse(c.running)
    }

    @Test
    fun aRunningClockCountsDown() {
        val c = clock(remainingMs = 60_000)

        c.start(1_000)

        assertTrue(c.running)
        assertEquals(58_000L, c.remainingAt(3_000))
    }

    @Test
    fun stoppingFreezesTheTimeForGood() {
        val c = clock(remainingMs = 60_000)
        c.start(1_000)

        c.stop(11_000)

        assertFalse(c.running)
        assertEquals(50_000L, c.remainingAt(99_999))
    }

    @Test
    fun startingTwiceDoesNotRestartTheCountdown() {
        val c = clock(remainingMs = 60_000)
        c.start(1_000)

        c.start(30_000)

        assertEquals(59_000L, c.remainingAt(2_000))
    }

    @Test
    fun theTimeNeverGoesBelowZero() {
        val c = clock(remainingMs = 5_000)
        c.start(0)

        assertEquals(0L, c.remainingAt(10_000))
        assertTrue(c.isFlagged(10_000))
    }

    @Test
    fun theIncrementIsAddedAfterAMove() {
        val c = clock(TimeControl.BLITZ, remainingMs = 10_000)
        c.start(0)
        c.stop(4_000)

        c.addIncrement()

        assertEquals(8_000L, c.remainingAt(50_000))   // 10 s - 4 s spent + 2 s increment
    }

    @Test
    fun undoneMovesTakeTheirIncrementBack() {
        val c = clock(TimeControl.BLITZ, remainingMs = 10_000)
        c.addIncrement()
        c.addIncrement()
        assertEquals(14_000L, c.remainingAt(0))

        c.removeIncrements(2)

        assertEquals(10_000L, c.remainingAt(0))
    }

    @Test
    fun takingBackMoreIncrementsThanGrantedStopsAtZero() {
        val c = clock(TimeControl.BLITZ, remainingMs = 1_000)

        c.removeIncrements(5)

        assertEquals(0L, c.remainingAt(0))
    }

    @Test
    fun aControlWithoutTimeNeverFlags() {
        val c = clock(TimeControl.NONE)
        c.start(0)

        assertFalse(c.control.timed)
        assertFalse(c.isFlagged(10_000_000))
        assertFalse(c.isLow(10_000_000))
    }

    @Test
    fun lowTimeIsReportedForTheWarningColour() {
        val c = clock(TimeControl.RAPID, remainingMs = GameClock.LOW_TIME_MS + 1_000)
        assertFalse(c.isLow(0))

        c.start(0)

        assertFalse(c.isLow(999))     // 10 001 ms left
        assertTrue(c.isLow(1_001))    // 9 999 ms left
        assertTrue(c.isLow(2_000))
    }

    // ---- the preset controls ----------------------------------------------

    @Test
    fun presetsMatchTheAgreedControls() {
        assertEquals(30 * TimeControl.MINUTE_MS, TimeControl.CLASSIC.mainTimeMs)
        assertEquals(30 * TimeControl.SECOND_MS, TimeControl.CLASSIC.incrementMs)

        assertEquals(15 * TimeControl.MINUTE_MS, TimeControl.RAPID.mainTimeMs)
        assertEquals(10 * TimeControl.SECOND_MS, TimeControl.RAPID.incrementMs)

        assertEquals(3 * TimeControl.MINUTE_MS, TimeControl.BLITZ.mainTimeMs)
        assertEquals(2 * TimeControl.SECOND_MS, TimeControl.BLITZ.incrementMs)
    }

    /**
     * FIDE classifies a control by `main time + 60 × increment`: at least 60 minutes
     * is classical, between 10 and 60 rapid, at most 10 blitz. The names of the
     * presets are only honest while this holds (README.md, "Timer and game mode").
     */
    @Test
    fun presetsFallIntoTheFideClassTheyAreNamedAfter() {
        fun fideSum(control: TimeControl) = control.mainTimeMs + 60 * control.incrementMs

        assertTrue(fideSum(TimeControl.CLASSIC) >= 60 * TimeControl.MINUTE_MS)
        assertTrue(fideSum(TimeControl.RAPID) > 10 * TimeControl.MINUTE_MS)
        assertTrue(fideSum(TimeControl.RAPID) < 60 * TimeControl.MINUTE_MS)
        assertTrue(fideSum(TimeControl.BLITZ) <= 10 * TimeControl.MINUTE_MS)
    }

    @Test
    fun anUnknownStoredIdFallsBackToNoClock() {
        assertEquals(TimeControl.NONE, TimeControl.byId(-1))
        assertEquals(TimeControl.NONE, TimeControl.byId(99))
        assertEquals(TimeControl.RAPID, TimeControl.byId(TimeControl.RAPID.ordinal))
    }
}
