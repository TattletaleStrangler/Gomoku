package com.oldmermaidman.gomoku.model

/**
 * Wins / losses / draws for every difficulty level.
 *
 * The statistics are cumulative and survive restarts, so they are kept per
 * level: a win on «Нормальный» must not be mixed with a win on «Сложный».
 */
class ScoreBoard(val levelCount: Int) {

    private val scores = Array(levelCount) { SessionScore() }

    /** Statistics of one level; out-of-range values fall back to the last level. */
    fun scoreOf(level: Int): SessionScore = scores[level.coerceIn(0, levelCount - 1)]

    fun record(level: Int, result: GameResult) = scoreOf(level).record(result)

    fun rollback(level: Int, result: GameResult) = scoreOf(level).rollback(result)

    fun reset() = scores.forEach { it.reset() }

    /** Storage format: `wins,losses,draws` per level, levels separated by ';'. */
    fun encode(): String = scores.joinToString(";") { "${it.wins},${it.losses},${it.draws}" }

    companion object {
        /** Inverse of [encode]. Malformed or missing input yields an empty board. */
        fun decode(raw: String?, levelCount: Int): ScoreBoard {
            val board = ScoreBoard(levelCount)
            if (raw.isNullOrEmpty()) return board

            val levels = raw.split(';')
            for (i in 0 until minOf(levels.size, levelCount)) {
                val values = levels[i].split(',')
                if (values.size != 3) continue
                board.scoreOf(i).set(
                    values[0].toIntOrNull() ?: 0,
                    values[1].toIntOrNull() ?: 0,
                    values[2].toIntOrNull() ?: 0
                )
            }
            return board
        }
    }
}
