package com.oldmermaidman.gomoku.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model-level tests for the rules, independent of Android. */
class GomokuGameTest {

    private fun newGame(forbidden: Boolean = false) =
        GomokuGame(GomokuGame.BOARD_SIZE, Stone.BLACK, forbidden)

    /**
     * Fills the board with a pattern that never makes five in a row: colour is
     * decided by `(x + 2y) mod 4`, which yields runs of at most two stones along
     * every one of the four directions.
     */
    private fun fillWithoutFive(game: GomokuGame): Pair<Int, Int> {
        var ok = 0
        var draws = 0
        for (y in 0 until GomokuGame.BOARD_SIZE) {
            for (x in 0 until GomokuGame.BOARD_SIZE) {
                val color = if ((x + 2 * y) % 4 < 2) Stone.BLACK else Stone.WHITE
                when (game.place(x, y, color)) {
                    PlaceOutcome.OK -> ok++
                    PlaceOutcome.DRAW -> { ok++; draws++ }
                    else -> throw AssertionError(
                        "unexpected outcome at ($x,$y): expected no five with this pattern"
                    )
                }
            }
        }
        return ok to draws
    }

    @Test
    fun fullBoardWithoutFiveIsADraw() {
        val game = newGame()

        val (placed, draws) = fillWithoutFive(game)

        assertEquals(1, draws)
        assertEquals(GomokuGame.BOARD_SIZE * GomokuGame.BOARD_SIZE, placed)
        assertTrue("game must be over on a full board", game.gameOver)
        assertNull("a draw has no winner", game.winner)
        assertFalse(game.forbiddenLoss)
    }

    @Test
    fun drawSurvivesEncodeDecode() {
        val game = newGame()
        fillWithoutFive(game)

        val restored = GomokuGame.decode(game.encode())

        assertTrue(restored != null)
        assertTrue("restored game must stay finished", restored!!.gameOver)
        assertNull(restored.winner)
    }

    @Test
    fun undoAfterDrawMakesTheGamePlayableAgain() {
        val game = newGame()
        fillWithoutFive(game)

        assertTrue(game.undo())

        assertFalse("undo must clear the finished state", game.gameOver)
        assertNull(game.winner)
        assertTrue("a cell must be free again", game.isEmpty(14, 14))
    }

    @Test
    fun rollbackAfterDrawMakesTheGamePlayableAgain() {
        val game = newGame()
        fillWithoutFive(game)
        val lastCell = GomokuGame.BOARD_SIZE * GomokuGame.BOARD_SIZE - 1

        assertTrue(game.rollbackTo(lastCell))

        assertFalse("rollback must clear the finished state", game.gameOver)
        assertNull(game.winner)
        assertTrue("the last cell must be free again", game.isEmpty(14, 14))
        assertEquals(lastCell, game.history.size)
    }

    @Test
    fun fiveInARowWins() {
        val game = newGame()
        game.place(3, 7, Stone.BLACK)
        game.place(4, 7, Stone.BLACK)
        game.place(5, 7, Stone.BLACK)
        game.place(6, 7, Stone.BLACK)

        val outcome = game.place(7, 7, Stone.BLACK)

        assertEquals(PlaceOutcome.WIN, outcome)
        assertEquals(Stone.BLACK, game.winner)
        assertTrue(game.gameOver)
    }

    @Test
    fun undoClearsTheWinner() {
        val game = newGame()
        game.place(3, 7, Stone.BLACK)
        game.place(4, 7, Stone.BLACK)
        game.place(5, 7, Stone.BLACK)
        game.place(6, 7, Stone.BLACK)
        game.place(7, 7, Stone.BLACK)

        assertTrue(game.undo())

        assertFalse(game.gameOver)
        assertNull(game.winner)
    }

    @Test
    fun forbiddenDoubleThreeLosesForBlack() {
        val game = newGame(forbidden = true)
        // Two open twos crossing at (7,7): completing the crossing point makes a
        // double three, which is forbidden for black.
        game.place(5, 7, Stone.BLACK)
        game.place(6, 7, Stone.BLACK)
        game.place(7, 5, Stone.BLACK)
        game.place(7, 6, Stone.BLACK)

        val outcome = game.place(7, 7, Stone.BLACK)

        assertEquals(PlaceOutcome.FORBIDDEN, outcome)
        assertEquals(Stone.WHITE, game.winner)
        assertTrue(game.forbiddenLoss)
    }

    // ---- clock -------------------------------------------------------------

    @Test
    fun timeExpiredGivesTheGameToTheComputer() {
        val game = newGame()
        game.place(7, 7, Stone.BLACK)

        game.timeExpired()

        assertTrue(game.gameOver)
        assertEquals(Stone.WHITE, game.winner)
        assertTrue(game.timeLoss)
        assertFalse("a timeout is not a forbidden move", game.forbiddenLoss)
        assertTrue("there is no line to highlight", game.winningLine.isEmpty())
    }

    @Test
    fun timeLossSurvivesEncodeDecode() {
        val game = newGame()
        game.place(7, 7, Stone.BLACK)
        game.timeExpired()

        val restored = GomokuGame.decode(game.encode())

        assertTrue(restored != null)
        assertTrue(restored!!.gameOver)
        assertTrue(restored.timeLoss)
        assertEquals(Stone.WHITE, restored.winner)
    }

    @Test
    fun aGameSavedBeforeTheClockExistedStillDecodes() {
        // The old format carries no marker, so its moves start right after the
        // forbidden-loss field.
        val legacy = "15;BLACK;0;NONE;0;0;7,7,BLACK;8,8,WHITE"

        val game = GomokuGame.decode(legacy)

        assertTrue(game != null)
        assertEquals(2, game!!.history.size)
        assertEquals(Move(7, 7, Stone.BLACK), game.history[0])
        assertFalse(game.timeLoss)
        assertFalse(game.gameOver)
    }

    @Test
    fun rollbackClearsTheTimeout() {
        val game = newGame()
        game.place(7, 7, Stone.BLACK)
        game.place(8, 8, Stone.WHITE)
        game.timeExpired()

        assertTrue(game.rollbackTo(1))

        assertFalse(game.timeLoss)
        assertFalse(game.gameOver)
        assertNull(game.winner)
    }

    // ---- winning line ------------------------------------------------------

    @Test
    fun winningLineIsReported() {
        val game = newGame()
        for (x in 3..6) game.place(x, 7, Stone.BLACK)
        game.place(7, 7, Stone.BLACK)

        val line = game.winningLine

        assertEquals(5, line.size)
        assertEquals(listOf(3, 4, 5, 6, 7), line.map { it.x })
        assertTrue("the whole line lies on row 7", line.all { it.y == 7 })
    }

    @Test
    fun winningLineSurvivesEncodeDecode() {
        val game = newGame()
        for (x in 3..6) game.place(x, 7, Stone.BLACK)
        game.place(7, 7, Stone.BLACK)

        val restored = GomokuGame.decode(game.encode())

        assertTrue(restored != null)
        assertEquals(5, restored!!.winningLine.size)
    }

    @Test
    fun winningLineIsClearedByUndo() {
        val game = newGame()
        for (x in 3..6) game.place(x, 7, Stone.BLACK)
        game.place(7, 7, Stone.BLACK)

        game.undo()

        assertTrue("undo must drop the highlight", game.winningLine.isEmpty())
    }

    @Test
    fun noWinningLineWhenBlackLosesByForbiddenMove() {
        val game = newGame(forbidden = true)
        game.place(5, 7, Stone.BLACK)
        game.place(6, 7, Stone.BLACK)
        game.place(7, 5, Stone.BLACK)
        game.place(7, 6, Stone.BLACK)
        game.place(7, 7, Stone.BLACK)

        assertTrue(game.forbiddenLoss)
        assertTrue("white never made five, so there is no line", game.winningLine.isEmpty())
    }
}
