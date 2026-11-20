package com.oldmermaidman.gomoku.model

/**
 * Preset game clocks: main time for the whole game plus a Fischer increment added
 * after every move of the player.
 *
 * The values follow the FIDE controls. FIDE classifies a control by
 * `main time + 60 × increment`, which makes these three classical (30 + 30 = 60
 * minutes), rapid (15 + 10 = 25 minutes) and blitz (3 + 2 = 5 minutes) — see
 * README.md, "Timer and game mode".
 *
 * Only the human player is timed: the engine has its own per-move budget and
 * cannot run out of time, so it needs no clock.
 */
enum class TimeControl(val mainTimeMs: Long, val incrementMs: Long) {

    /** Casual play: no clock at all. */
    NONE(0L, 0L),

    /** Классика: 30 minutes for the game, +30 seconds per move. */
    CLASSIC(30 * 60_000L, 30_000L),

    /** Рапид: 15 minutes for the game, +10 seconds per move. */
    RAPID(15 * 60_000L, 10_000L),

    /** Блиц: 3 minutes for the game, +2 seconds per move. */
    BLITZ(3 * 60_000L, 2_000L);

    /** True when this control gives the player a clock to run down. */
    val timed: Boolean get() = this != NONE

    companion object {
        /**
         * Milliseconds in a second and in a minute, for turning the controls above
         * into text. The entries themselves cannot read this object — a companion is
         * initialized after them — so they spell the values out.
         */
        const val SECOND_MS = 1_000L
        const val MINUTE_MS = 60 * SECOND_MS

        /** The control stored under [id], or [NONE] when the value is unknown. */
        fun byId(id: Int): TimeControl = entries.firstOrNull { it.ordinal == id } ?: NONE
    }
}
