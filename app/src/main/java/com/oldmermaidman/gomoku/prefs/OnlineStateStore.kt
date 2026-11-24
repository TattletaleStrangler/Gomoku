package com.oldmermaidman.gomoku.prefs

import android.content.Context
import androidx.core.content.edit

/**
 * The open online game, remembered across process death so the player can return to it.
 *
 * Only the id and the room code are stored — nothing secret, so the file is backed up
 * like the game settings. The tokens live in [com.oldmermaidman.gomoku.net.TokenStore]
 * and are excluded from backup.
 */
class OnlineStateStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Id of the game the player is in, or null when there is none. */
    var gameId: String?
        get() = prefs.getString(KEY_ID, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_ID) else putString(KEY_ID, value)
        }

    /** The room code shown while waiting for an opponent. */
    var code: String?
        get() = prefs.getString(KEY_CODE, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_CODE) else putString(KEY_CODE, value)
        }

    fun save(gameId: String, code: String) {
        prefs.edit {
            putString(KEY_ID, gameId)
            putString(KEY_CODE, code)
        }
    }

    fun clear() {
        prefs.edit {
            remove(KEY_ID)
            remove(KEY_CODE)
        }
    }

    /** The settings last used for a room or a quick match, restored on the next visit. */
    var quickRule: String?
        get() = prefs.getString(KEY_QUICK_RULE, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_QUICK_RULE) else putString(KEY_QUICK_RULE, value)
        }

    var quickTimeControl: String?
        get() = prefs.getString(KEY_QUICK_TIME, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_QUICK_TIME) else putString(KEY_QUICK_TIME, value)
        }

    var quickColor: String?
        get() = prefs.getString(KEY_QUICK_COLOR, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_QUICK_COLOR) else putString(KEY_QUICK_COLOR, value)
        }

    companion object {
        private const val PREFS_NAME = "gomoku_online"
        private const val KEY_ID = "game_id"
        private const val KEY_CODE = "game_code"
        private const val KEY_QUICK_RULE = "quick_rule"
        private const val KEY_QUICK_TIME = "quick_time"
        private const val KEY_QUICK_COLOR = "quick_color"
    }
}
