package com.oldmermaidman.gomoku.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.oldmermaidman.gomoku.model.GomokuGame
import com.oldmermaidman.gomoku.model.Stone

/**
 * SharedPreferences-backed settings plus the current game state.
 * Everything survives process death (TZ §8, criterion 6).
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var boardSize: Int
        get() = prefs.getInt(KEY_SIZE, 15)
        set(value) = prefs.edit { putInt(KEY_SIZE, value) }

    var playerColor: Stone
        get() = if (prefs.getString(KEY_COLOR, "BLACK") == "WHITE") Stone.WHITE else Stone.BLACK
        set(value) = prefs.edit { putString(KEY_COLOR, value.name) }

    var forbiddenMoves: Boolean
        get() = prefs.getBoolean(KEY_FORBIDDEN, false)
        set(value) = prefs.edit { putBoolean(KEY_FORBIDDEN, value) }

    var showMoveNumbers: Boolean
        get() = prefs.getBoolean(KEY_NUMBERS, true)
        set(value) = prefs.edit { putBoolean(KEY_NUMBERS, value) }

    var aiLevel: Int
        get() = prefs.getInt(KEY_LEVEL, LEVEL_NORMAL)
        set(value) = prefs.edit { putInt(KEY_LEVEL, value) }

    var theme: Int
        get() = prefs.getInt(KEY_THEME, THEME_LIGHT)
        set(value) = prefs.edit { putInt(KEY_THEME, value) }

    fun saveGame(game: GomokuGame) {
        prefs.edit { putString(KEY_GAME, game.encode()) }
    }

    fun restoreGame(): GomokuGame {
        val raw = prefs.getString(KEY_GAME, null)
            ?: return GomokuGame(boardSize, playerColor, forbiddenMoves)
        return GomokuGame.decode(raw)
            ?: GomokuGame(boardSize, playerColor, forbiddenMoves)
    }

    companion object {
        const val LEVEL_NORMAL = 0
        const val LEVEL_STRONG = 1

        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_WARM = 2

        private const val PREFS_NAME = "gomoku_prefs"
        private const val KEY_SIZE = "board_size"
        private const val KEY_COLOR = "player_color"
        private const val KEY_FORBIDDEN = "forbidden_moves"
        private const val KEY_NUMBERS = "show_move_numbers"
        private const val KEY_LEVEL = "ai_level"
        private const val KEY_THEME = "theme"
        private const val KEY_GAME = "game_state"
    }
}
