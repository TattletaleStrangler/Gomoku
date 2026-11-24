package com.oldmermaidman.gomoku.net

import android.util.Log
import com.oldmermaidman.gomoku.net.dto.CreateGameRequest
import com.oldmermaidman.gomoku.net.dto.CredentialsRequest
import com.oldmermaidman.gomoku.net.dto.DeleteAccountRequest
import com.oldmermaidman.gomoku.net.dto.ErrorDto
import com.oldmermaidman.gomoku.net.dto.GameHistoryDto
import com.oldmermaidman.gomoku.net.dto.GameSnapshotDto
import com.oldmermaidman.gomoku.net.dto.JoinGameRequest
import com.oldmermaidman.gomoku.net.dto.LeaderboardRowDto
import com.oldmermaidman.gomoku.net.dto.LobbyGameDto
import com.oldmermaidman.gomoku.net.dto.MoveRequest
import com.oldmermaidman.gomoku.net.dto.ProfileDto
import com.oldmermaidman.gomoku.net.dto.QuickGameRequest
import com.oldmermaidman.gomoku.net.dto.RefreshTokenRequest
import com.oldmermaidman.gomoku.net.dto.TokenDto
import com.oldmermaidman.gomoku.net.dto.UndoDecisionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * The REST half of the network layer (TZ §11.3): registration, rooms and moves.
 *
 * Every call is a suspending function that runs on [Dispatchers.IO] and either returns
 * the parsed DTO or throws an [ApiException]. A 401 is answered with one automatic
 * refresh and one retry; when the refresh fails the session is dropped and the caller
 * is told to show the sign-in screen.
 *
 * The socket is [GameSocket]'s business; commands always go over REST, exactly as the
 * server expects.
 */
class ApiClient(
    baseUrl: HttpUrl,
    private val tokens: SessionStore,
    private val http: OkHttpClient,
    private val json: Json = NetJson,
) {

    /** Everything under `/api/v1`; the prefix is added once, here. */
    private val apiBase: HttpUrl = baseUrl.newBuilder().addPathSegments("api/v1").build()

    /** The WebSocket endpoint (`GET /ws/game`), used by [GameSocket]. */
    val socketUrl: HttpUrl = baseUrl.newBuilder().addPathSegments("ws/game").build()

    /**
     * Serializes refreshes: a refresh token is single-use (the server rotates it), so
     * two concurrent refreshes would invalidate each other and sign the player out.
     */
    private val refreshMutex = Mutex()

    // ---------------------------------------------------------------- accounts

    /** Registers a new account and stores the session it returns. */
    suspend fun register(nickname: String, password: String): Session {
        val text = call(
            "POST",
            url("auth/register"),
            json.encodeToString(CredentialsRequest(nickname, password)),
            authorized = false,
        )
        return save(decode<TokenDto>(text))
    }

    /** Signs in and stores the session. */
    suspend fun login(nickname: String, password: String): Session {
        val text = call(
            "POST",
            url("auth/login"),
            json.encodeToString(CredentialsRequest(nickname, password)),
            authorized = false,
        )
        return save(decode<TokenDto>(text))
    }

    /**
     * Revokes the refresh token on the server and forgets the session locally. The
     * local tokens are dropped even when the server cannot be reached: staying signed
     * in on the device after the player asked to leave would be worse.
     */
    suspend fun logout() {
        val refresh = tokens.session()?.refreshToken
        try {
            if (refresh != null) {
                call(
                    "POST",
                    url("auth/logout"),
                    json.encodeToString(RefreshTokenRequest(refresh)),
                )
            }
        } catch (e: ApiException) {
            Log.w(TAG, "logout did not reach the server", e)
        } finally {
            tokens.clear()
        }
    }

    /** Deletes the account on the server and forgets the session. */
    suspend fun deleteAccount(password: String) {
        call("DELETE", url("auth/account"), json.encodeToString(DeleteAccountRequest(password)))
        tokens.clear()
    }

    /** The signed-in player's profile and record. */
    suspend fun profile(): ProfileDto = decode(call("GET", url("me")))

    /** The top players by rating. */
    suspend fun leaderboard(limit: Int = 50): List<LeaderboardRowDto> = decode(
        call(
            "GET",
            url("leaderboard").newBuilder()
                .addQueryParameter("limit", limit.toString())
                .build(),
        ),
    )

    // ------------------------------------------------------------------- games

    suspend fun createGame(
        rule: String,
        timeControl: String,
        color: String? = null,
        visibility: String? = null,
    ): GameSnapshotDto =
        decode(
            call(
                "POST",
                url("games"),
                json.encodeToString(CreateGameRequest(rule, timeControl, color, visibility)),
            ),
        )

    /** Quick match: joins the oldest public room with these settings, or opens one. */
    suspend fun quickGame(
        rule: String,
        timeControl: String,
        color: String? = null,
    ): GameSnapshotDto =
        decode(
            call(
                "POST",
                url("games/quick"),
                json.encodeToString(QuickGameRequest(rule, timeControl, color)),
            ),
        )

    /** The open-games list, oldest first. */
    suspend fun lobby(): List<LobbyGameDto> = decode(call("GET", url("games/lobby")))

    /** The caller's finished games, newest first; the moves come from [game]. */
    suspend fun history(limit: Int = 20): List<GameHistoryDto> = decode(
        call(
            "GET",
            url("games/history").newBuilder()
                .addQueryParameter("limit", limit.toString())
                .build(),
        ),
    )

    suspend fun joinGame(code: String, color: String? = null): GameSnapshotDto =
        decode(call("POST", url("games/join"), json.encodeToString(JoinGameRequest(code, color))))

    suspend fun game(id: String): GameSnapshotDto = decode(call("GET", url("games/$id")))

    suspend fun move(id: String, ply: Int, x: Int, y: Int): GameSnapshotDto =
        decode(call("POST", url("games/$id/moves"), json.encodeToString(MoveRequest(ply, x, y))))

    suspend fun resign(id: String): GameSnapshotDto = decode(call("POST", url("games/$id/resign")))

    suspend fun leave(id: String): GameSnapshotDto = decode(call("POST", url("games/$id/leave")))

    /** Asks the opponent to take the last move back (FEATURES.md, online undo). */
    suspend fun requestUndo(id: String): GameSnapshotDto =
        decode(call("POST", url("games/$id/undo-request")))

    /** The opponent's answer to a pending request. */
    suspend fun decideUndo(id: String, accept: Boolean): GameSnapshotDto =
        decode(call("POST", url("games/$id/undo-decision"), json.encodeToString(UndoDecisionRequest(accept))))

    // ----------------------------------------------------------------- session

    /**
     * A session whose access token is still good, refreshing it when needed. Null when
     * nobody is signed in or the refresh token was rejected; throws when the server
     * cannot be reached, so the caller can show "no connection" instead of "signed out".
     */
    suspend fun validSession(): Session? {
        val current = tokens.session() ?: return null
        if (current.isFresh(System.currentTimeMillis())) return current
        return try {
            refreshSession(current.accessToken)
        } catch (e: ApiException) {
            if (e.isAuthFailure) null else throw e
        }
    }

    /** Forces a refresh — used when the socket handshake is rejected as unauthorized. */
    suspend fun forceRefresh(): Session? {
        val current = tokens.session() ?: return null
        return try {
            refreshSession(current.accessToken)
        } catch (e: ApiException) {
            if (e.isAuthFailure) null else throw e
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * Exchanges the refresh token for a new pair. [stale] is the access token the failed
     * request used: when another thread has already refreshed, the stored token differs
     * and is returned instead of spending the refresh token a second time.
     */
    private suspend fun refreshSession(stale: String?): Session = refreshMutex.withLock {
        val current = tokens.session() ?: throw ApiException(ApiError.TOKEN_INVALID)
        if (stale != null && current.accessToken != stale) return@withLock current

        try {
            val text = call(
                "POST",
                url("auth/refresh"),
                json.encodeToString(RefreshTokenRequest(current.refreshToken)),
                authorized = false,
            )
            save(decode<TokenDto>(text))
        } catch (e: ApiException) {
            // A rejected refresh means the session is gone; drop the tokens here, once,
            // for every caller instead of in each of them.
            if (e.isAuthFailure) tokens.clear()
            throw e
        }
    }

    private fun save(token: TokenDto): Session {
        val now = System.currentTimeMillis()
        val session = Session(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            // expiresIn is seconds; a missing value leaves the token trusted for a
            // minute only, so the next call refreshes instead of failing.
            expiresAtMs = now + (token.expiresIn.takeIf { it > 0 } ?: 60L) * 1_000L,
            userId = token.user?.id.orEmpty(),
            nickname = token.user?.nickname.orEmpty(),
        )
        tokens.save(session)
        return session
    }

    private fun url(path: String): HttpUrl = apiBase.newBuilder().addPathSegments(path).build()

    /**
     * Performs one request and returns the response body as text.
     *
     * A 401 on an authorized call triggers exactly one refresh and one retry; a second
     * 401 is reported to the caller as an auth failure.
     */
    private suspend fun call(
        method: String,
        url: HttpUrl,
        body: String? = null,
        authorized: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        // Refresh before the call when the stored token is already past its lifetime:
        // a 401 round trip would otherwise be the normal path of every 16th minute.
        var access = if (authorized) {
            val session = tokens.session()
            if (session != null && !session.isFresh(System.currentTimeMillis())) {
                refreshSession(session.accessToken).accessToken
            } else {
                session?.accessToken
            }
        } else {
            null
        }
        var response = execute(method, url, body, access)

        if (response.code == 401 && authorized) {
            response.close()
            access = refreshSession(access).accessToken
            response = execute(method, url, body, access)
        }

        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                Log.w(TAG, "$method ${url.encodedPath} -> ${r.code}: $text")
                throw errorFrom(r.code, text)
            }
            text
        }
    }

    private fun execute(method: String, url: HttpUrl, body: String?, access: String?): Response {
        val builder = Request.Builder().url(url)
        if (access != null) builder.header("Authorization", "Bearer $access")
        val requestBody = if (method == "GET") null else (body ?: "").toRequestBody(JSON_MEDIA)
        builder.method(method, requestBody)
        return try {
            http.newCall(builder.build()).execute()
        } catch (e: SocketTimeoutException) {
            throw ApiException(ApiError.TIMEOUT, e.message, cause = e)
        } catch (e: IOException) {
            throw ApiException(ApiError.NETWORK, e.message, cause = e)
        }
    }

    /** Turns an error response into an [ApiException]; the code is the contract. */
    private fun errorFrom(status: Int, text: String): ApiException {
        val dto = runCatching { json.decodeFromString<ErrorDto>(text) }.getOrNull()
        val code = dto?.code
        val error = when {
            code != null -> ApiError.fromWire(code)
            status == 401 -> ApiError.UNAUTHORIZED
            status == 429 -> ApiError.RATE_LIMITED
            status >= 500 -> ApiError.INTERNAL_ERROR
            else -> ApiError.UNKNOWN
        }
        return ApiException(error, dto?.message ?: text.take(200), dto?.details, status)
    }

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (e: Exception) {
        Log.w(TAG, "cannot parse the response", e)
        throw ApiException(ApiError.MALFORMED, e.message, cause = e)
    }

    private companion object {
        const val TAG = "ApiClient"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
