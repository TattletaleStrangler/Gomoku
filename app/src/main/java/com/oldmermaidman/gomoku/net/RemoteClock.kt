package com.oldmermaidman.gomoku.net

import com.oldmermaidman.gomoku.model.Stone

/**
 * The two game clocks as the server reported them, counted down locally.
 *
 * A snapshot carries the milliseconds left and whose clock is running, measured at the
 * server's `serverTime`. The client does not know the server's clock, so it treats the
 * moment the snapshot arrived as the starting point: `serverTime` is the reference the
 * values were computed at, and [receivedAtMs] is where the local countdown starts. The
 * device clock is never consulted, so changing the phone's time cannot move a game.
 *
 * The class is pure: [nowMs] is passed in, which makes it testable without a device.
 */
class RemoteClock(
    private val blackRemainingMs: Long?,
    private val whiteRemainingMs: Long?,
    /** Whose clock is running, or null when the game has no clocks or is paused. */
    val running: Stone?,
    /** The server's `serverTime` of the snapshot, in epoch milliseconds. */
    val serverTimeMs: Long,
    /** Local monotonic reading (`SystemClock.elapsedRealtime()`) when it arrived. */
    private val receivedAtMs: Long,
) {

    /** True when the game has a clock at all; an untimed game has neither value. */
    val timed: Boolean get() = blackRemainingMs != null && whiteRemainingMs != null

    /** Milliseconds left on [color]'s clock at [nowMs]; null in an untimed game. */
    fun remainingMs(color: Stone, nowMs: Long): Long? {
        val base = baseOf(color) ?: return null
        if (color != running) return base
        return (base - elapsedMs(nowMs)).coerceAtLeast(0L)
    }

    /** True when [color]'s clock is in its last stretch and should be highlighted. */
    fun isLow(color: Stone, nowMs: Long): Boolean {
        val left = remainingMs(color, nowMs) ?: return false
        return left <= LOW_TIME_MS
    }

    /** True when [color]'s clock has run out at [nowMs]. */
    fun isFlagged(color: Stone, nowMs: Long): Boolean {
        val left = remainingMs(color, nowMs) ?: return false
        return left <= 0L
    }

    /**
     * The server's clock as the client estimates it now. Used to turn a server deadline
     * (an undo request's `deadline`) into a local countdown.
     */
    fun serverNowMs(nowMs: Long): Long = serverTimeMs + elapsedMs(nowMs)

    private fun baseOf(color: Stone): Long? = when (color) {
        Stone.BLACK -> blackRemainingMs
        Stone.WHITE -> whiteRemainingMs
        Stone.EMPTY -> null
    }

    /** Time passed since the snapshot arrived; the only stretch the client measures. */
    private fun elapsedMs(nowMs: Long): Long = (nowMs - receivedAtMs).coerceAtLeast(0L)

    companion object {
        /** Below this the clock is shown as a warning (blitz needs the hint most). */
        const val LOW_TIME_MS = 10_000L
    }
}
