package com.oldmermaidman.gomoku.model

/** Stone color on the board. */
enum class Stone {
    EMPTY, BLACK, WHITE;

    /** The opposing color. EMPTY maps to itself. */
    fun opponent(): Stone = when (this) {
        BLACK -> WHITE
        WHITE -> BLACK
        EMPTY -> EMPTY
    }
}
