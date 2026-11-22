package com.oldmermaidman.gomoku.net

import android.content.Context
import androidx.core.content.edit

/**
 * The storage the [ApiClient] depends on. Keeping it an interface lets the JVM tests use
 * an in-memory store instead of a real `SharedPreferences`.
 */
interface SessionStore {

    /** The stored session, or null when nobody is signed in. */
    fun session(): Session?

    fun save(session: Session)

    fun clear()

    /** True when a refresh token is stored, i.e. the player can still be signed in. */
    fun hasSession(): Boolean
}

/**
 * The signed-in player's tokens, in their own preferences file (TZ §11.1).
 *
 * The file is `gomoku_auth`, deliberately separate from the game settings, and both
 * backup rule sets exclude it: a refresh token must never travel to the cloud. The
 * access token lives 15 minutes; [Session.expiresAtMs] is the local wall-clock moment
 * it stops being trusted, so the client can refresh before the server rejects a call.
 *
 * All methods are synchronized: the socket may read the token while a REST call is
 * refreshing it on another thread.
 */
class TokenStore(context: Context) : SessionStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun session(): Session? {
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        return Session(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMs = prefs.getLong(KEY_EXPIRES_AT, 0L),
            userId = userId,
            nickname = prefs.getString(KEY_NICKNAME, null).orEmpty(),
        )
    }

    @Synchronized
    override fun save(session: Session) {
        prefs.edit {
            putString(KEY_ACCESS, session.accessToken)
            putString(KEY_REFRESH, session.refreshToken)
            putLong(KEY_EXPIRES_AT, session.expiresAtMs)
            putString(KEY_USER_ID, session.userId)
            putString(KEY_NICKNAME, session.nickname)
        }
    }

    @Synchronized
    override fun clear() {
        prefs.edit { clear() }
    }

    @Synchronized
    override fun hasSession(): Boolean = prefs.getString(KEY_REFRESH, null) != null

    companion object {
        /**
         * The preferences file excluded from backup in
         * `res/xml/data_extraction_rules.xml` and `res/xml/backup_rules.xml`.
         */
        const val PREFS_NAME = "gomoku_auth"

        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at_ms"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_NICKNAME = "nickname"
    }
}

/**
 * A signed-in session: the two tokens, the moment the access token stops being trusted,
 * and who the player is.
 *
 * [expiresAtMs] is wall-clock time (`System.currentTimeMillis`), which is good enough
 * for deciding when to refresh: being a little early costs one request, being late
 * costs a 401 that the client handles anyway.
 */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val userId: String,
    val nickname: String,
) {

    /**
     * True while the access token can still be used. A minute of slack is kept so a
     * request that takes a while does not arrive already expired.
     */
    fun isFresh(nowMs: Long): Boolean = nowMs < expiresAtMs - EXPIRY_SKEW_MS

    companion object {
        /** How early the client refreshes before the server's 15 minutes run out. */
        const val EXPIRY_SKEW_MS = 60_000L
    }
}
