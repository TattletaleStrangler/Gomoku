package com.oldmermaidman.gomoku.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the move history: previewing the position after a prefix of the moves
 * (the history screen's board) and rolling the live game back to such a prefix.
 */
class GomokuGameHistoryTest {

    private fun newGame(forbidden: Boolean = false) =
        GomokuGame(GomokuGame.BOARD_SIZE, Stone.BLACK, forbidden)

    /**
     * Plays [count] stones along the main diagonal, alternating colours. Stones of
     * one colour never touch on that diagonal, so no five appears and every move is
     * an ordinary one.
     */
    private fun gameWithMoves(count: Int): GomokuGame {
        val game = newGame()
        for (i in 0 until count) {
            game.place(i, i, if (i % 2 == 0) Stone.BLACK else Stone.WHITE)
        }
        return game
    }

    /** A finished game: black makes five on row 7 with its fifth move. */
    private fun gameWonByBlack(): GomokuGame {
        val game = newGame()
        for (x in 3..7) game.place(x, 7, Stone.BLACK)
        return game
    }

    // ---- preview of a prefix ----------------------------------------------

    @Test
    fun positionAfterKeepsOnlyTheFirstMoves() {
        val game = gameWithMoves(6)

        val preview = game.positionAfter(3)

        assertEquals(3, preview.history.size)
        assertEquals(game.history.take(3), preview.history)
        assertEquals(Move(2, 2, Stone.BLACK), preview.lastMove)
        assertEquals(Stone.WHITE, preview.currentTurn)
        assertFalse("a prefix the real game continued past is not finished", preview.gameOver)
        assertNull(preview.winner)
        assertTrue("stones past the prefix are gone", preview.isEmpty(3, 3))
        assertFalse(preview.isEmpty(2, 2))
    }

    @Test
    fun positionAfterZeroIsTheEmptyBoard() {
        val game = gameWithMoves(4)

        val preview = game.positionAfter(0)

        assertTrue(preview.history.isEmpty())
        assertNull(preview.lastMove)
        assertEquals(Stone.BLACK, preview.currentTurn)
        assertTrue(preview.isEmpty(0, 0))
        assertFalse(preview.gameOver)
    }

    @Test
    fun positionAfterClampsTheRange() {
        val game = gameWithMoves(4)

        assertEquals(0, game.positionAfter(-7).history.size)
        assertEquals(4, game.positionAfter(99).history.size)
    }

    @Test
    fun positionAfterTheWholeHistoryCarriesTheResult() {
        val game = gameWonByBlack()
        val moves = game.history.size

        val preview = game.positionAfter(moves)

        assertEquals(moves, preview.history.size)
        assertTrue(preview.gameOver)
        assertEquals(Stone.BLACK, preview.winner)
        assertEquals("the winning line is shown in the preview", 5, preview.winningLine.size)
        // A finished game keeps the turn of the side that did not move last: place()
        // returns before switching it, so the preview mirrors that as well.
        assertEquals(game.currentTurn, preview.currentTurn)
        assertEquals(Stone.WHITE, preview.currentTurn)
    }

    @Test
    fun positionAfterTheWholeHistoryCarriesTheTimeout() {
        val game = newGame()
        game.place(7, 7, Stone.BLACK)
        game.timeExpired()

        val preview = game.positionAfter(game.history.size)

        assertTrue(preview.gameOver)
        assertTrue(preview.timeLoss)
        assertEquals(Stone.WHITE, preview.winner)
    }

    @Test
    fun previewLeavesTheGameItselfUntouched() {
        val game = gameWithMoves(5)

        game.positionAfter(2)

        assertEquals(5, game.history.size)
        assertFalse("the live board keeps every stone", game.isEmpty(4, 4))
        assertEquals(Move(4, 4, Stone.BLACK), game.lastMove)
    }

    // ---- rollback ---------------------------------------------------------

    @Test
    fun rollbackReopensAWonGame() {
        val game = gameWonByBlack()
        assertTrue(game.gameOver)

        assertTrue(game.rollbackTo(4))

        assertEquals(4, game.history.size)
        assertFalse(game.gameOver)
        assertNull(game.winner)
        assertTrue("the winning line must be dropped", game.winningLine.isEmpty())
        assertTrue("the winning stone is taken back", game.isEmpty(7, 7))
        assertFalse("the kept stones stay", game.isEmpty(3, 7))
        assertEquals(Stone.WHITE, game.currentTurn)
    }

    @Test
    fun rollbackToZeroEmptiesTheBoard() {
        val game = gameWithMoves(4)

        assertTrue(game.rollbackTo(0))

        assertTrue(game.history.isEmpty())
        assertNull(game.lastMove)
        assertEquals(Stone.BLACK, game.currentTurn)
        assertTrue(game.isEmpty(0, 0))
        assertTrue(game.isEmpty(3, 3))
    }

    @Test
    fun rollbackRefusesTheCurrentOrAWrongPosition() {
        val game = gameWithMoves(3)

        assertFalse("the current position is nothing to roll back to", game.rollbackTo(3))
        assertFalse(game.rollbackTo(4))
        assertFalse(game.rollbackTo(-1))
        assertEquals(3, game.history.size)
    }

    @Test
    fun theGameIsPlayableAgainAfterARollback() {
        val game = gameWonByBlack()
        game.rollbackTo(4)

        val outcome = game.place(7, 7, Stone.BLACK)

        assertEquals(PlaceOutcome.WIN, outcome)
        assertEquals(Stone.BLACK, game.winner)
    }

    @Test
    fun aRolledBackGameSurvivesEncodeDecode() {
        val game = gameWonByBlack()
        game.rollbackTo(3)

        val restored = GomokuGame.decode(game.encode())

        assertTrue(restored != null)
        assertEquals(3, restored!!.history.size)
        assertFalse("the rolled-back game is not finished", restored.gameOver)
        assertFalse(restored.isEmpty(3, 7))
        assertTrue(restored.isEmpty(7, 7))
    }

    // ---- undo, which is a rollback of a whole round ------------------------

    @Test
    fun undoStillTakesBackAFullRound() {
        // The human plays black: 1.B 2.W (AI) 3.B 4.W (AI). Undo removes 4 and 3.
        val game = newGame()
        game.place(7, 7, Stone.BLACK)
        game.place(8, 8, Stone.WHITE)
        game.place(7, 8, Stone.BLACK)
        game.place(8, 7, Stone.WHITE)

        assertTrue(game.undo())

        assertEquals(2, game.history.size)
        assertTrue(game.isEmpty(7, 8))
        assertTrue(game.isEmpty(8, 7))
        assertFalse(game.isEmpty(8, 8))
        assertEquals(Stone.BLACK, game.currentTurn)
    }

    @Test
    fun undoWithThePlayerToMoveTakesBackASingleStone() {
        // The human plays white: 1.B (AI) 2.W. Only the human's stone goes back.
        val game = GomokuGame(GomokuGame.BOARD_SIZE, Stone.WHITE)
        game.place(7, 7, Stone.BLACK)
        game.place(8, 8, Stone.WHITE)

        assertTrue(game.undo())

        assertEquals(1, game.history.size)
        assertTrue(game.isEmpty(8, 8))
        assertEquals(Stone.WHITE, game.currentTurn)
    }
}
