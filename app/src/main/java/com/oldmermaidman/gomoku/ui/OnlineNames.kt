package com.oldmermaidman.gomoku.ui

import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.model.Stone

/**
 * Localized names of the wire values the online screens share: the lobby, the history
 * list and the replay all show a rule, a clock and a colour.
 */
fun ruleName(rule: String): Int = when (rule) {
    "RENJU" -> R.string.online_rule_renju
    else -> R.string.online_rule_freestyle
}

fun timeName(timeControl: String): Int = when (timeControl) {
    "CLASSIC" -> R.string.online_time_classic
    "RAPID" -> R.string.online_time_rapid
    "BLITZ" -> R.string.online_time_blitz
    else -> R.string.online_time_untimed
}

fun colorName(color: String): Int = when (color) {
    "BLACK" -> R.string.play_black
    "WHITE" -> R.string.play_white
    else -> R.string.online_color_any
}

/** How a finished game reads for the player who is looking at it. */
fun resultName(result: String, you: Stone?): Int = when (result) {
    "DRAW" -> R.string.online_result_draw
    "BLACK_WIN" -> if (you == Stone.BLACK) {
        R.string.online_result_you_win
    } else {
        R.string.online_result_you_lose
    }
    "WHITE_WIN" -> if (you == Stone.WHITE) {
        R.string.online_result_you_win
    } else {
        R.string.online_result_you_lose
    }
    else -> R.string.online_game_over
}