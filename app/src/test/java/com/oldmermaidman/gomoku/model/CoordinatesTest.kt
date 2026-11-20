package com.oldmermaidman.gomoku.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the cell names shown in the move list. */
class CoordinatesTest {

    @Test
    fun cornersAreLabelledFromTheTopLeft() {
        assertEquals("A1", Coordinates.label(0, 0))
        assertEquals("O1", Coordinates.label(14, 0))
        assertEquals("A15", Coordinates.label(0, 14))
        assertEquals("O15", Coordinates.label(14, 14))
    }

    @Test
    fun theCentreOfTheBoardIsH8() {
        assertEquals("H8", Coordinates.label(7, 7))
    }

    @Test
    fun everyCellOfTheBoardHasAUniqueName() {
        val names = mutableSetOf<String>()

        for (y in 0 until GomokuGame.BOARD_SIZE) {
            for (x in 0 until GomokuGame.BOARD_SIZE) {
                assertTrue("duplicate name for ($x,$y)", names.add(Coordinates.label(x, y)))
            }
        }

        assertEquals(GomokuGame.BOARD_SIZE * GomokuGame.BOARD_SIZE, names.size)
    }

    @Test
    fun aColumnPastZIsNumbered() {
        assertEquals("27", Coordinates.column(26))
    }
}
