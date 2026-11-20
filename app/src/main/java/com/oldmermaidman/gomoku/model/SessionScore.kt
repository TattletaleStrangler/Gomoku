package com.oldmermaidman.gomoku.model

/** Result of a finished game, from the human player's point of view. */
enum class GameResult { WIN, LOSS, DRAW }

/** The result of [game] for its player, or null while the game is still running. */
fun resultOf(game: GomokuGame): GameResult? = when {
    !game.gameOver -> null
    game.winner == null -> GameResult.DRAW
    game.winner == game.playerColor -> GameResult.WIN
    else -> GameResult.LOSS
}

/**
 * Wins / losses / draws of the current session.
 *
 * A result can be taken back. If the player loses, undoes the move and wins
 * instead, only the win is counted — [rollback] removes the earlier record.
 */
class SessionScore {

    var wins: Int = 0
        private set

    var losses: Int = 0
        private set

    var draws: Int = 0
        private set

    val total: Int get() = wins + losses + draws

    fun record(result: GameResult) {
        when (result) {
            GameResult.WIN -> wins++
            GameResult.LOSS -> losses++
            GameResult.DRAW -> draws++
        }
    }

    /** Undoes a previous [record]. Counters never go below zero. */
    fun rollback(result: GameResult) {
        when (result) {
            GameResult.WIN -> if (wins > 0) wins--
            GameResult.LOSS -> if (losses > 0) losses--
            GameResult.DRAW -> if (draws > 0) draws--
        }
    }

    fun reset() {
        wins = 0
        losses = 0
        draws = 0
    }
}
