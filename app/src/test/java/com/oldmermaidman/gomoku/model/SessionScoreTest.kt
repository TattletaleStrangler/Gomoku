package com.oldmermaidman.gomoku.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Session score bookkeeping, including the "lost, undid, then won" case. */
class SessionScoreTest {

    @Test
    fun countsEveryResult() {
        val score = SessionScore()

        score.record(GameResult.WIN)
        score.record(GameResult.WIN)
        score.record(GameResult.LOSS)
        score.record(GameResult.DRAW)

        assertEquals(2, score.wins)
        assertEquals(1, score.losses)
        assertEquals(1, score.draws)
        assertEquals(4, score.total)
    }

    /** The requirement: a loss that is taken back must not stay in the stats. */
    @Test
    fun loseThenUndoThenWinCountsOnlyTheWin() {
        val score = SessionScore()

        score.record(GameResult.LOSS)       // the computer completed five
        score.rollback(GameResult.LOSS)     // the player took the move back
        score.record(GameResult.WIN)        // ...and won instead

        assertEquals(1, score.wins)
        assertEquals(0, score.losses)
        assertEquals(0, score.draws)
    }

    @Test
    fun rollbackNeverGoesBelowZero() {
        val score = SessionScore()

        score.rollback(GameResult.WIN)
        score.rollback(GameResult.LOSS)
        score.rollback(GameResult.DRAW)

        assertEquals(0, score.total)
    }

    @Test
    fun resetClearsEverything() {
        val score = SessionScore()
        score.record(GameResult.WIN)
        score.record(GameResult.LOSS)

        score.reset()

        assertEquals(0, score.total)
    }

    // ---- resultOf ----------------------------------------------------------

    @Test
    fun runningGameHasNoResult() {
        val game = GomokuGame(GomokuGame.BOARD_SIZE, Stone.BLACK, false)

        assertNull(resultOf(game))
    }

    @Test
    fun winAndLossAreRelativeToThePlayer() {
        val game = GomokuGame(GomokuGame.BOARD_SIZE, Stone.BLACK, false)
        for (x in 3..7) game.place(x, 7, Stone.BLACK)

        assertEquals(GameResult.WIN, resultOf(game))

        // The same finished game seen by a player who played white.
        val asWhite = GomokuGame(GomokuGame.BOARD_SIZE, Stone.WHITE, false)
        for (x in 3..7) asWhite.place(x, 7, Stone.BLACK)

        assertEquals(GameResult.LOSS, resultOf(asWhite))
    }
}
