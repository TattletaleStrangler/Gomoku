package com.oldmermaidman.gomoku.model

/**
 * Volume of the move sound, chosen in five steps of 20 %.
 *
 * Kept as a pure Kotlin object so the mapping from a slider position to a
 * percentage can be unit-tested without Android.
 */
object SoundVolume {

    /** Number of slider positions. */
    const val STEPS = 5

    /** Default position: 60 %. */
    const val DEFAULT_STEP = 2

    /**
     * @param step slider position, 0-based; out-of-range values are clamped
     * @return volume in percent — 20, 40, 60, 80 or 100
     */
    fun percentOf(step: Int): Int = (step.coerceIn(0, STEPS - 1) + 1) * (100 / STEPS)
}
