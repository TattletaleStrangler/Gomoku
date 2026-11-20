package com.oldmermaidman.gomoku.model

/**
 * The player's clock: main time for the game plus a Fischer increment after every
 * move of the player (FIDE gives the increment for the first move as well).
 *
 * The clock measures nothing by itself — every method takes the current reading of
 * a monotonic clock as [nowMs] (`SystemClock.elapsedRealtime()` on Android). That
 * keeps the class free of Android dependencies, immune to wall-clock changes and
 * exact while the app sits in the background: a paused clock simply is not running.
 */
class GameClock(
    val control: TimeControl,
    remainingMs: Long = control.mainTimeMs
) {

    /** Time left while the clock is stopped; use [remainingAt] for the live value. */
    var remainingMs: Long = remainingMs.coerceAtLeast(0L)
        private set

    /** Monotonic reading taken when the clock was last started; null while stopped. */
    private var runningSinceMs: Long? = null

    val running: Boolean get() = runningSinceMs != null

    /** Time left at [nowMs], counting the running stretch. Never negative. */
    fun remainingAt(nowMs: Long): Long {
        val since = runningSinceMs ?: return remainingMs
        return (remainingMs - (nowMs - since)).coerceAtLeast(0L)
    }

    /** Starts the countdown; does nothing when it is already running. */
    fun start(nowMs: Long) {
        if (runningSinceMs == null) runningSinceMs = nowMs
    }

    /** Freezes the countdown and keeps what is left. */
    fun stop(nowMs: Long) {
        val since = runningSinceMs ?: return
        remainingMs = remainingAt(nowMs)
        runningSinceMs = null
    }

    /** Adds the increment for the move that has just been played. */
    fun addIncrement() {
        remainingMs = (remainingMs + control.incrementMs).coerceAtLeast(0L)
    }

    /**
     * Takes back the increments of [count] moves that were undone. The time already
     * spent thinking stays spent, but playing and taking back a move repeatedly
     * cannot buy extra time.
     */
    fun removeIncrements(count: Int) {
        if (count <= 0) return
        remainingMs = (remainingMs - control.incrementMs * count).coerceAtLeast(0L)
    }

    /** True when the player has used up all of their time at [nowMs]. */
    fun isFlagged(nowMs: Long): Boolean = control.timed && remainingAt(nowMs) <= 0L

    /** True in the last stretch, when the clock is highlighted as a warning. */
    fun isLow(nowMs: Long): Boolean = control.timed && remainingAt(nowMs) <= LOW_TIME_MS

    companion object {
        /** Below this the clock is shown as a warning (blitz needs the hint most). */
        const val LOW_TIME_MS = 10_000L
    }
}
