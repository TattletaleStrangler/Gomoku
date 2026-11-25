package com.oldmermaidman.gomoku.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.oldmermaidman.gomoku.model.GomokuGame
import com.oldmermaidman.gomoku.model.ScoreBoard
import com.oldmermaidman.gomoku.model.SoundVolume
import com.oldmermaidman.gomoku.model.Stone
import com.oldmermaidman.gomoku.model.TimeControl

/**
 * SharedPreferences-backed settings, cumulative statistics and the current game
 * state. Everything survives process death and app restarts (TZ §8, criterion 6).
 *
 * The board is always 15×15 — that is the size Rapfi plays, and the built-in AI
 * is offered as an alternative opponent on the same board. The difficulty level
 * therefore also selects the engine (see [aiLevel]).
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var playerColor: Stone
        get() = if (prefs.getString(KEY_COLOR, "BLACK") == "WHITE") Stone.WHITE else Stone.BLACK
        set(value) = prefs.edit { putString(KEY_COLOR, value.name) }

    var forbiddenMoves: Boolean
        get() = prefs.getBoolean(KEY_FORBIDDEN, false)
        set(value) = prefs.edit { putBoolean(KEY_FORBIDDEN, value) }

    var showMoveNumbers: Boolean
        get() = prefs.getBoolean(KEY_NUMBERS, true)
        set(value) = prefs.edit { putBoolean(KEY_NUMBERS, value) }

    /** Move sound on/off. */
    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) = prefs.edit { putBoolean(KEY_SOUND, value) }

    /**
     * Whether the crash journal is sent to the server on the next launch. Off by
     * default: the journal exists to be shared by hand, and sending it is the player's
     * choice (see README.md, "Crash journal").
     */
    var sendCrashReports: Boolean
        get() = prefs.getBoolean(KEY_SEND_CRASHES, false)
        set(value) = prefs.edit { putBoolean(KEY_SEND_CRASHES, value) }

    /** Volume slider position, `0..SoundVolume.STEPS-1`; see [SoundVolume.percentOf]. */
    var soundVolumeStep: Int
        get() = prefs.getInt(KEY_VOLUME, SoundVolume.DEFAULT_STEP)
        set(value) = prefs.edit { putInt(KEY_VOLUME, value.coerceIn(0, SoundVolume.STEPS - 1)) }

    /**
     * Difficulty, which doubles as the engine choice:
     * [LEVEL_NORMAL] plays the built-in AI, [LEVEL_STRONG] plays Rapfi.
     */
    var aiLevel: Int
        get() = prefs.getInt(KEY_LEVEL, LEVEL_NORMAL)
        set(value) = prefs.edit { putInt(KEY_LEVEL, value) }

    var theme: Int
        get() = prefs.getInt(KEY_THEME, THEME_LIGHT)
        set(value) = prefs.edit { putInt(KEY_THEME, value) }

    /**
     * Game mode: the [TimeControl] ordinal — no clock, classical, rapid or blitz.
     * It belongs to the settings, so the chosen mode survives a restart.
     */
    var gameMode: Int
        get() = prefs.getInt(KEY_MODE, TimeControl.NONE.ordinal)
        set(value) = prefs.edit { putInt(KEY_MODE, value) }

    /**
     * Time left on the player's clock, in milliseconds, or [NO_CLOCK_SAVED] when
     * nothing is stored yet. It belongs to the running game: a new game starts from
     * the main time of its control again.
     */
    var clockRemainingMs: Long
        get() = prefs.getLong(KEY_CLOCK_LEFT, NO_CLOCK_SAVED)
        set(value) = prefs.edit { putLong(KEY_CLOCK_LEFT, value) }

    fun saveGame(game: GomokuGame) {
        prefs.edit { putString(KEY_GAME, game.encode()) }
    }

    /** Restores the saved game, or starts a fresh one on the standard board. */
    fun restoreGame(): GomokuGame {
        val saved = prefs.getString(KEY_GAME, null)?.let { GomokuGame.decode(it) }
        // A game saved by an older build may use another board size; the app only
        // supports 15×15 now, so such a state is discarded.
        if (saved != null && saved.size == GomokuGame.BOARD_SIZE) return saved
        return newGame()
    }

    fun newGame(): GomokuGame =
        GomokuGame(GomokuGame.BOARD_SIZE, playerColor, forbiddenMoves)

    /** Cumulative wins / losses / draws, one set per difficulty level. */
    fun loadScores(): ScoreBoard =
        ScoreBoard.decode(prefs.getString(KEY_STATS, null), LEVEL_COUNT)

    fun saveScores(board: ScoreBoard) {
        prefs.edit { putString(KEY_STATS, board.encode()) }
    }

    companion object {
        /** Difficulty levels; they also select the engine (see [aiLevel]). */
        const val LEVEL_NORMAL = 0
        const val LEVEL_STRONG = 1

        /** Number of difficulty levels, i.e. the size of the stored statistics. */
        const val LEVEL_COUNT = 2

        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_WARM = 2

        /** [clockRemainingMs] before anything was stored. */
        const val NO_CLOCK_SAVED = -1L

        private const val PREFS_NAME = "gomoku_prefs"
        private const val KEY_COLOR = "player_color"
        private const val KEY_FORBIDDEN = "forbidden_moves"
        private const val KEY_NUMBERS = "show_move_numbers"
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_SEND_CRASHES = "send_crash_reports"
        private const val KEY_VOLUME = "sound_volume_step"
        private const val KEY_LEVEL = "ai_level"
        private const val KEY_THEME = "theme"
        private const val KEY_MODE = "game_mode"
        private const val KEY_CLOCK_LEFT = "clock_remaining_ms"
        private const val KEY_GAME = "game_state"
        private const val KEY_STATS = "stats_by_level"
    }
}
