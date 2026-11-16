package com.example.gomoku.model

/**
 * A single move on the board.
 *
 * Coordinates are **zero-based** (0..size-1) and match the Gomocup/Piskvork
 * protocol convention (see README.md, "Coordinate convention").
 */
data class Move(val x: Int, val y: Int, val color: Stone)
