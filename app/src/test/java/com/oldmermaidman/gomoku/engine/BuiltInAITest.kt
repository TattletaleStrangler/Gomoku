package com.oldmermaidman.gomoku.engine

import com.oldmermaidman.gomoku.model.GomokuGame
import com.oldmermaidman.gomoku.model.Move
import com.oldmermaidman.gomoku.model.Stone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the built-in engine — the one behind the "normal"
 * difficulty, and the fallback for "hard" when Rapfi is unavailable.
 *
 * They encode the behaviour that used to be broken: the engine ignored the
 * opponent's threats because (a) the evaluation only saw solid runs, (b) the
 * candidate list was capped by an offensive-only ordering and (c) quiescence
 * examined only its own threats.
 *
 * The engine is pure Kotlin (no Android dependencies), so these run as plain
 * JVM unit tests. The board is always 15×15.
 *
 * Note on the positions: only moves that are *forced* are asserted. When the
 * engine can make an open four it must play that instead of blocking, so most
 * positions deliberately avoid giving the engine its own counter-threat.
 */
class BuiltInAITest {

    private val ai = BuiltInAI()

    // ---- helpers -----------------------------------------------------------

    private fun emptyBoard(): Array<Array<Stone>> {
        val n = GomokuGame.BOARD_SIZE
        return Array(n) { Array(n) { Stone.EMPTY } }
    }

    private fun Array<Array<Stone>>.putAll(stone: Stone, vararg cells: Pair<Int, Int>) {
        for ((x, y) in cells) this[y][x] = stone
    }

    private fun moveOf(board: Array<Array<Stone>>, color: Stone, budgetMs: Long = 2000): Move {
        val move = ai.nextMove(board, color, 99, budgetMs, false)
        assertNotNull("engine returned no move", move)
        return move!!
    }

    private fun Move.isOneOf(vararg cells: Pair<Int, Int>): Boolean =
        cells.any { it.first == x && it.second == y }

    // ---- forced defence ----------------------------------------------------

    /** The opponent is one move from five through a gap: that gap must be taken. */
    @Test
    fun blocksBrokenFour() {
        val board = emptyBoard()
        board.putAll(Stone.WHITE, 5 to 7, 6 to 7, 7 to 7, 9 to 7)   // OOO.O  (gap at 8)

        val move = moveOf(board, Stone.BLACK)

        assertTrue(
            "expected the gap at (8,7) to be blocked, got (${move.x},${move.y})",
            move.isOneOf(8 to 7)
        )
    }

    /** The classic complaint: the opponent has an open three and it must be answered. */
    @Test
    fun blocksOpenThree() {
        val board = emptyBoard()
        board.putAll(Stone.WHITE, 5 to 7, 6 to 7, 7 to 7)           // .OOO.

        val move = moveOf(board, Stone.BLACK)

        assertTrue(
            "expected one end of the three, (4,7) or (8,7), got (${move.x},${move.y})",
            move.isOneOf(4 to 7, 8 to 7)
        )
    }

    /**
     * Same, but on a board crowded enough that the old 24-move candidate cap hid
     * the blocking cell behind its offensive-only ordering.
     *
     * Black's stones are deliberately scattered and non-collinear: any three of
     * them in a line would let black make an open four, and then playing that
     * (correctly) beats blocking.
     */
    @Test
    fun blocksOpenThreeInMidgame() {
        val board = emptyBoard()
        board.putAll(Stone.WHITE, 5 to 5, 6 to 5, 7 to 5)           // open three on row 5
        board.putAll(Stone.BLACK, 1 to 1, 13 to 1, 1 to 13, 13 to 13, 10 to 7)

        val move = moveOf(board, Stone.BLACK)

        assertTrue(
            "expected the three to be blocked at (4,5) or (8,5), got (${move.x},${move.y})",
            move.isOneOf(4 to 5, 8 to 5)
        )
    }

    // ---- forced attack -----------------------------------------------------

    /** A win must be taken immediately. */
    @Test
    fun takesTheWin() {
        val board = emptyBoard()
        board.putAll(Stone.BLACK, 6 to 7, 7 to 7, 8 to 7, 9 to 7)   // .XXXX.

        val move = moveOf(board, Stone.BLACK)

        assertTrue(
            "expected five in a row at (5,7) or (10,7), got (${move.x},${move.y})",
            move.isOneOf(5 to 7, 10 to 7)
        )
    }

    /** A win through a gap must also be taken. */
    @Test
    fun takesTheWinThroughGap() {
        val board = emptyBoard()
        board.putAll(Stone.BLACK, 5 to 7, 6 to 7, 7 to 7, 9 to 7)   // XXXX with gap at 8

        val move = moveOf(board, Stone.BLACK)

        assertTrue(
            "expected the gap at (8,7) to complete five, got (${move.x},${move.y})",
            move.isOneOf(8 to 7)
        )
    }

    /**
     * Documented rule: an open four wins next move, so it outranks blocking the
     * opponent's three. This is the rule that made a badly built "must block"
     * test fail for the wrong reason, so it is pinned down here explicitly.
     */
    @Test
    fun prefersOpenFourOverBlocking() {
        val board = emptyBoard()
        board.putAll(Stone.WHITE, 5 to 5, 6 to 5, 7 to 5)           // opponent open three
        board.putAll(Stone.BLACK, 9 to 4, 10 to 3, 11 to 2)         // black three, anti-diagonal

        val move = moveOf(board, Stone.BLACK)

        assertTrue(
            "expected the winning open four at (12,1), got (${move.x},${move.y})",
            move.isOneOf(12 to 1)
        )
    }

    // ---- basics ------------------------------------------------------------

    /** Empty board: the centre is the only sensible opening. */
    @Test
    fun opensInTheCentre() {
        val move = moveOf(emptyBoard(), Stone.BLACK)
        assertEquals(7, move.x)
        assertEquals(7, move.y)
    }

    /** The engine must stay inside its time budget. */
    @Test
    fun respectsTimeBudget() {
        val board = emptyBoard()
        board.putAll(Stone.WHITE, 7 to 7, 8 to 7, 9 to 7)
        board.putAll(Stone.BLACK, 8 to 8, 9 to 9, 7 to 8)

        val started = System.currentTimeMillis()
        val move = ai.nextMove(board, Stone.BLACK, 99, 3000, false)
        val elapsed = System.currentTimeMillis() - started

        assertNotNull("engine returned no move", move)
        assertTrue("took ${elapsed}ms, budget was 3000ms", elapsed < 7000)
    }

    /**
     * Sanity check that a real search happens (depth > 0) on a position without
     * forced moves: every stone here is isolated, so nothing in the forced-move
     * ladder can trigger and iterative deepening has to run.
     */
    @Test
    fun reachesReasonableDepth() {
        val board = emptyBoard()
        board.putAll(Stone.BLACK, 3 to 3, 7 to 7, 11 to 11, 5 to 9, 9 to 5, 4 to 12, 12 to 4)
        board.putAll(Stone.WHITE, 5 to 5, 9 to 9, 3 to 11, 11 to 3, 7 to 5, 10 to 12, 2 to 4)

        moveOf(board, Stone.BLACK, 3000)

        println("BuiltInAI reached depth ${ai.lastDepth} (${ai.lastNodes} nodes)")
        assertTrue(
            "forced ladder short-circuited or search failed: depth ${ai.lastDepth}",
            ai.lastDepth >= 3
        )
    }
}
