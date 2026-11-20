package com.oldmermaidman.gomoku.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** The volume slider has five positions, each worth 20 %. */
class SoundVolumeTest {

    @Test
    fun stepsMapToTwentyPercentEach() {
        assertEquals(5, SoundVolume.STEPS)
        assertEquals(20, SoundVolume.percentOf(0))
        assertEquals(40, SoundVolume.percentOf(1))
        assertEquals(60, SoundVolume.percentOf(2))
        assertEquals(80, SoundVolume.percentOf(3))
        assertEquals(100, SoundVolume.percentOf(4))
    }

    @Test
    fun outOfRangeStepsAreClamped() {
        assertEquals(20, SoundVolume.percentOf(-3))
        assertEquals(100, SoundVolume.percentOf(99))
    }

    @Test
    fun defaultStepIsSixtyPercent() {
        assertEquals(60, SoundVolume.percentOf(SoundVolume.DEFAULT_STEP))
    }
}
