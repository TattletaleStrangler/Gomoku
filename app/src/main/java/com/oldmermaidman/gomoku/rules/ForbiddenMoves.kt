package com.oldmermaidman.gomoku.rules

import com.oldmermaidman.gomoku.model.Stone

/**
 * Renju forbidden-move logic for black, plus the shared "five in a row" check.
 *
 * The black stone is assumed to have already been placed at (x, y) when any of
 * the `is*` methods is called. The three renju forbidden moves are:
 *   - overline:    six or more stones in a row;
 *   - double-four: the move creates two (or more) fours;
 *   - double-three:the move creates two (or more) open (live) threes.
 *
 * Detection uses a 13-cell window (±6) along each of the four directions,
 * which is wide enough to see any five-in-a-row containing the placed stone
 * and to inspect the open ends around it.
 */
object ForbiddenMoves {

    private val DIRS = arrayOf(
        intArrayOf(1, 0),   // horizontal
        intArrayOf(0, 1),   // vertical
        intArrayOf(1, 1),   // diagonal "\"
        intArrayOf(1, -1)   // diagonal "/"
    )

    /** Number of consecutive stones of [color] on the line through (x, y) along (dx, dy). */
    fun countLine(
        board: Array<Array<Stone>>,
        x: Int,
        y: Int,
        dx: Int,
        dy: Int,
        color: Stone
    ): Int {
        val size = board.size
        var count = 1
        var nx = x + dx
        var ny = y + dy
        while (nx in 0 until size && ny in 0 until size && board[ny][nx] == color) {
            count++
            nx += dx
            ny += dy
        }
        nx = x - dx
        ny = y - dy
        while (nx in 0 until size && ny in 0 until size && board[ny][nx] == color) {
            count++
            nx -= dx
            ny -= dy
        }
        return count
    }

    /** Five or more stones in a row — the standard gomoku (and white's renju) win. */
    fun hasFive(board: Array<Array<Stone>>, x: Int, y: Int, color: Stone): Boolean {
        for (d in DIRS) {
            if (countLine(board, x, y, d[0], d[1], color) >= 5) return true
        }
        return false
    }

    /**
     * True when the black stone at (x, y) is a renju forbidden move.
     * A genuine five is a win, never a forbidden move, so it short-circuits.
     */
    fun isForbidden(board: Array<Array<Stone>>, x: Int, y: Int): Boolean {
        if (isOverline(board, x, y)) return true
        if (hasFive(board, x, y, Stone.BLACK)) return false // five wins
        if (isDoubleFour(board, x, y)) return true
        if (isDoubleThree(board, x, y)) return true
        return false
    }

    fun isOverline(board: Array<Array<Stone>>, x: Int, y: Int): Boolean {
        for (d in DIRS) {
            if (countLine(board, x, y, d[0], d[1], Stone.BLACK) >= 6) return true
        }
        return false
    }

    fun isDoubleFour(board: Array<Array<Stone>>, x: Int, y: Int): Boolean {
        var count = 0
        for (d in DIRS) {
            if (directionHasFour(board, x, y, d[0], d[1])) count++
            if (count >= 2) return true
        }
        return false
    }

    fun isDoubleThree(board: Array<Array<Stone>>, x: Int, y: Int): Boolean {
        var count = 0
        for (d in DIRS) {
            if (directionHasLiveThree(board, x, y, d[0], d[1])) count++
            if (count >= 2) return true
        }
        return false
    }

    // ---- internals --------------------------------------------------------

    /**
     * Builds a 13-character string centred on (x, y) along (dx, dy):
     *   '1' = black stone, '0' = opponent stone, '.' = empty, '#' = board edge.
     */
    private fun window(board: Array<Array<Stone>>, x: Int, y: Int, dx: Int, dy: Int): String {
        val size = board.size
        val sb = StringBuilder(13)
        for (i in -6..6) {
            val nx = x + dx * i
            val ny = y + dy * i
            sb.append(
                when {
                    nx !in 0 until size || ny !in 0 until size -> '#'
                    board[ny][nx] == Stone.BLACK -> '1'
                    board[ny][nx] == Stone.EMPTY -> '.'
                    else -> '0'
                }
            )
        }
        return sb.toString()
    }

    /** A cell is "open" for extension when it is empty or already our stone. */
    private fun isOpen(s: CharArray, idx: Int): Boolean {
        if (idx < 0 || idx >= s.size) return true
        val c = s[idx]
        return c == '.' || c == '1'
    }

    /**
     * True when the placed stone creates a "four" along this direction:
     * there exists an empty cell whose completion would make a five-in-a-row
     * containing the placed stone. (This covers both open and closed fours
     * but not dead fours, which cannot be completed.)
     */
    private fun directionHasFour(board: Array<Array<Stone>>, x: Int, y: Int, dx: Int, dy: Int): Boolean {
        val s = window(board, x, y, dx, dy)
        for (e in s.indices) {
            if (s[e] != '.') continue
            val t = s.toCharArray().also { it[e] = '1' }
            if (containsFiveThrough(t, 6)) return true
        }
        return false
    }

    /**
     * True when the placed stone creates a live three along this direction:
     * there exists an empty cell whose completion would make an open four
     * (".XXXX." with both ends open) that contains the placed stone.
     */
    private fun directionHasLiveThree(board: Array<Array<Stone>>, x: Int, y: Int, dx: Int, dy: Int): Boolean {
        val s = window(board, x, y, dx, dy)
        for (e in s.indices) {
            if (s[e] != '.') continue
            val t = s.toCharArray().also { it[e] = '1' }
            if (containsOpenFourThrough(t, 6)) return true
        }
        return false
    }

    /** Any five consecutive '1' cells containing index [center]. */
    private fun containsFiveThrough(t: CharArray, center: Int): Boolean {
        for (i in 0..t.size - 5) {
            if (center < i || center > i + 4) continue
            var ok = true
            for (j in i until i + 5) {
                if (t[j] != '1') { ok = false; break }
            }
            if (ok) return true
        }
        return false
    }

    /**
     * An open four (".XXXX.") containing [center]: four consecutive stones with an
     * empty cell immediately on both sides.
     */
    private fun containsOpenFourThrough(t: CharArray, center: Int): Boolean {
        for (i in 0..t.size - 5) {
            if (center < i || center > i + 4) continue
            var stones = 0
            var emptyIdx = -1
            for (j in i until i + 5) {
                when {
                    t[j] == '1' -> stones++
                    t[j] == '.' -> {
                        if (emptyIdx != -1) { stones = -1; break } // two empties -> not a four
                        emptyIdx = j
                    }
                    else -> { stones = -1; break }
                }
            }
            if (stones == 4) {
                // The four stones are consecutive; the single empty cell is one open
                // end, and the other open end is the neighbour beyond the block.
                when (emptyIdx) {
                    i -> if (isOpen(t, i + 5)) return true       // window ".XXXX"
                    i + 4 -> if (isOpen(t, i - 1)) return true   // window "XXXX."
                }
            }
        }
        return false
    }
}
