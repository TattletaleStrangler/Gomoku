package com.oldmermaidman.gomoku.model

/**
 * Human-readable names for board cells, used by the move list.
 *
 * The engine grid stays zero-based (see [Move]); only the name shown to the player
 * is one-based: columns are letters `A`..`O`, rows are numbers `1`..`15`, both
 * counted from the top-left corner. So the centre of the 15×15 board, `(7,7)`,
 * reads `H8`.
 */
object Coordinates {

    /** Column letter of a zero-based [x]; past `Z` it falls back to the number. */
    fun column(x: Int): String = if (x in 0..25) ('A' + x).toString() else (x + 1).toString()

    /** Cell name such as `H8` for zero-based ([x], [y]). */
    fun label(x: Int, y: Int): String = column(x) + (y + 1)
}
