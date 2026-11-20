package com.oldmermaidman.gomoku.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Per-level statistics, their storage format and the reset. */
class ScoreBoardTest {

    private val levels = 2

    @Test
    fun levelsAreCountedSeparately() {
        val board = ScoreBoard(levels)

        board.record(0, GameResult.WIN)
        board.record(0, GameResult.LOSS)
        board.record(1, GameResult.WIN)

        assertEquals(1, board.scoreOf(0).wins)
        assertEquals(1, board.scoreOf(0).losses)
        assertEquals(0, board.scoreOf(0).draws)
        assertEquals(1, board.scoreOf(1).wins)
        assertEquals(0, board.scoreOf(1).losses)
    }

    @Test
    fun rollbackHitsOnlyTheGivenLevel() {
        val board = ScoreBoard(levels)
        board.record(0, GameResult.LOSS)
        board.record(1, GameResult.LOSS)

        board.rollback(1, GameResult.LOSS)      // the game was played on level 1

        assertEquals("level 0 keeps its loss", 1, board.scoreOf(0).losses)
        assertEquals("level 1 loses it", 0, board.scoreOf(1).losses)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val board = ScoreBoard(levels)
        board.record(0, GameResult.WIN)
        board.record(0, GameResult.WIN)
        board.record(0, GameResult.DRAW)
        board.record(1, GameResult.LOSS)

        val restored = ScoreBoard.decode(board.encode(), levels)

        assertEquals(2, restored.scoreOf(0).wins)
        assertEquals(0, restored.scoreOf(0).losses)
        assertEquals(1, restored.scoreOf(0).draws)
        assertEquals(1, restored.scoreOf(1).losses)
    }

    @Test
    fun decodeSurvivesGarbage() {
        val inputs = arrayOf(null, "", "junk", "1,2", ";;", "a,b,c")
        for (raw in inputs) {
            val board = ScoreBoard.decode(raw, levels)
            assertEquals("input: $raw", 0, board.scoreOf(0).total)
            assertEquals("input: $raw", 0, board.scoreOf(1).total)
        }
    }

    @Test
    fun resetClearsEveryLevel() {
        val board = ScoreBoard(levels)
        board.record(0, GameResult.WIN)
        board.record(1, GameResult.DRAW)

        board.reset()

        assertEquals(0, board.scoreOf(0).total)
        assertEquals(0, board.scoreOf(1).total)
    }
}
