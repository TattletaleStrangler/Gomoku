package com.oldmermaidman.gomoku.model

import com.oldmermaidman.gomoku.rules.ForbiddenMoves

/** Result of trying to place a stone. */
enum class PlaceOutcome {
    OK,        // stone placed, game continues
    WIN,       // stone placed, five in a row -> current player wins
    DRAW,      // stone placed, board is full and nobody has five
    FORBIDDEN, // black placed a forbidden move under renju rules -> black loses
    INVALID,   // out of bounds or game already over
    OCCUPIED   // cell is not empty
}

/**
 * Immutable rules + mutable board state for a single game.
 *
 * `playerColor` is the human's stone; the computer plays `aiColor` (its opponent).
 * `forbiddenMoves` enables renju forbidden moves for black (overline, double-four,
 * double-three all mean black loses).
 */
class GomokuGame(
    val size: Int,
    val playerColor: Stone,
    var forbiddenMoves: Boolean = false
) {
    /** Board indexed as board[y][x]. */
    val board: Array<Array<Stone>> = Array(size) { Array(size) { Stone.EMPTY } }

    var currentTurn: Stone = Stone.BLACK
        private set

    val history = mutableListOf<Move>()

    var winner: Stone? = null
        private set

    var gameOver: Boolean = false
        private set

    var forbiddenLoss: Boolean = false
        private set

    var lastMove: Move? = null
        private set

    /**
     * Cells of the winning five (or more) in a row, in board order. Empty when
     * there is no winning line — for instance when black lost by a forbidden
     * move and white never made five.
     */
    var winningLine: List<Move> = emptyList()
        private set

    val aiColor: Stone get() = playerColor.opponent()

    fun inBounds(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size

    fun isEmpty(x: Int, y: Int): Boolean = inBounds(x, y) && board[y][x] == Stone.EMPTY

    /** Places [color] at (x, y) and applies the win / forbidden rules. */
    fun place(x: Int, y: Int, color: Stone): PlaceOutcome {
        if (gameOver) return PlaceOutcome.INVALID
        if (!inBounds(x, y)) return PlaceOutcome.INVALID
        if (board[y][x] != Stone.EMPTY) return PlaceOutcome.OCCUPIED

        forbiddenLoss = false
        board[y][x] = color
        val move = Move(x, y, color)
        history.add(move)
        lastMove = move

        // Renju rule order for black: overline is never a win, a five wins, and
        // double-four / double-three lose only when no five was formed.
        if (color == Stone.BLACK && forbiddenMoves && ForbiddenMoves.isOverline(board, x, y)) {
            winner = Stone.WHITE
            gameOver = true
            forbiddenLoss = true
            winningLine = emptyList()
            return PlaceOutcome.FORBIDDEN
        }

        if (ForbiddenMoves.hasFive(board, x, y, color)) {
            winner = color
            gameOver = true
            winningLine = findWinningLine(color) ?: emptyList()
            return PlaceOutcome.WIN
        }

        if (color == Stone.BLACK && forbiddenMoves &&
            (ForbiddenMoves.isDoubleFour(board, x, y) || ForbiddenMoves.isDoubleThree(board, x, y))
        ) {
            winner = Stone.WHITE
            gameOver = true
            forbiddenLoss = true
            winningLine = emptyList()
            return PlaceOutcome.FORBIDDEN
        }

        currentTurn = color.opponent()

        // A full board without a five is a draw. Without this check the game would
        // simply run out of legal moves and never declare a result.
        if (history.size == size * size) {
            gameOver = true          // winner stays null
            return PlaceOutcome.DRAW
        }

        return PlaceOutcome.OK
    }

    /**
     * Undo a full round: the AI's last stone and, if present, the player's stone before it.
     * Returns false when there is nothing to undo.
     *
     * Undo also clears a finished game, so a player who lost to a surprise five
     * can take the move back and play on.
     */
    fun undo(): Boolean {
        if (history.isEmpty()) return false

        val last = history.last()
        removeLastStone()

        // If the removed stone belonged to the AI, also remove the player's preceding stone.
        if (last.color == aiColor && history.isNotEmpty() && history.last().color == playerColor) {
            removeLastStone()
        }

        winner = null
        gameOver = false
        forbiddenLoss = false
        winningLine = emptyList()
        currentTurn = if (history.isEmpty()) Stone.BLACK else history.last().color.opponent()
        return true
    }

    /** The human resigns: the computer wins. */
    fun humanResigns() {
        if (gameOver) return
        winner = aiColor
        gameOver = true
        winningLine = emptyList()
    }

    /** Places a stone without rule checks — used only to reconstruct a saved game. */
    fun replay(x: Int, y: Int, color: Stone) {
        board[y][x] = color
        val move = Move(x, y, color)
        history.add(move)
        lastMove = move
        currentTurn = color.opponent()
    }

    private fun removeLastStone() {
        val m = history.removeAt(history.size - 1)
        board[m.y][m.x] = Stone.EMPTY
        lastMove = history.lastOrNull()
    }

    /**
     * Finds the stones that won the game: the first run of five or more of
     * [color] on the board. Returns null when that colour has no such run.
     */
    private fun findWinningLine(color: Stone): List<Move>? {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (board[y][x] != color) continue
                for (d in LINE_DIRS) {
                    val px = x - d[0]
                    val py = y - d[1]
                    // Start only from the first stone of a run.
                    if (px in 0 until size && py in 0 until size && board[py][px] == color) continue
                    val len = ForbiddenMoves.countLine(board, x, y, d[0], d[1], color)
                    if (len >= 5) {
                        return (0 until len).map { Move(x + d[0] * it, y + d[1] * it, color) }
                    }
                }
            }
        }
        return null
    }

    /** Compact persistence format (see README.md, "State persistence"). */
    fun encode(): String {
        val sb = StringBuilder()
        sb.append(size).append(';')
        sb.append(playerColor.name).append(';')
        sb.append(if (forbiddenMoves) 1 else 0).append(';')
        sb.append(winner?.name ?: "NONE").append(';')
        sb.append(if (gameOver) 1 else 0).append(';')
        sb.append(if (forbiddenLoss) 1 else 0).append(';')
        sb.append(history.joinToString(";") { "${it.x},${it.y},${it.color.name}" })
        return sb.toString()
    }

    companion object {
        /**
         * The only supported board size. Rapfi plays 15×15 only, and the built-in
         * AI is offered as an alternative opponent on the same board.
         */
        const val BOARD_SIZE = 15

        private val LINE_DIRS = arrayOf(
            intArrayOf(1, 0),
            intArrayOf(0, 1),
            intArrayOf(1, 1),
            intArrayOf(1, -1)
        )

        /** Inverse of [encode]. Returns null on malformed input. */
        fun decode(raw: String): GomokuGame? {
            return try {
                val parts = raw.split(';')
                if (parts.size < 6) return null
                val size = parts[0].toInt()
                val playerColor = if (parts[1] == "WHITE") Stone.WHITE else Stone.BLACK
                val forbidden = parts[2] == "1"
                val winner = when (parts[3]) {
                    "BLACK" -> Stone.BLACK
                    "WHITE" -> Stone.WHITE
                    else -> null
                }
                val over = parts[4] == "1"
                val forbiddenLoss = parts[5] == "1"

                val game = GomokuGame(size, playerColor, forbidden)
                for (i in 6 until parts.size) {
                    if (parts[i].isEmpty()) continue
                    val t = parts[i].split(',')
                    if (t.size != 3) continue
                    game.replay(t[0].toInt(), t[1].toInt(), if (t[2] == "WHITE") Stone.WHITE else Stone.BLACK)
                }
                game.winner = winner
                game.gameOver = over
                game.forbiddenLoss = forbiddenLoss
                // The line is recomputed instead of stored: saved games written by
                // an older build keep working, and the highlight survives a restart.
                if (over && winner != null) {
                    game.winningLine = game.findWinningLine(winner) ?: emptyList()
                }
                game
            } catch (e: Exception) {
                null
            }
        }
    }
}
