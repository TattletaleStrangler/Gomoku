package com.oldmermaidman.gomoku.net

import android.util.Log
import com.oldmermaidman.gomoku.net.dto.GameEventDto
import com.oldmermaidman.gomoku.net.dto.GameSnapshotDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicInteger

/** One connection state change or game event from [GameSocket]. */
sealed interface SocketEvent {

    /** A message carrying the full game state; `PONG` is filtered out. */
    data class Message(val type: String, val game: GameSnapshotDto?) : SocketEvent

    /** The connection changed state. */
    data class State(val state: SocketState) : SocketEvent

    /** The socket could not run at all (for example, no session to authenticate with). */
    data class Failure(val error: ApiException) : SocketEvent
}

/** Lifecycle of the socket connection. */
enum class SocketState {
    CONNECTING,
    OPEN,
    RECONNECTING,
    CLOSED,
}

/**
 * The push half of the network layer (TZ §8).
 *
 * The socket only receives: commands go over REST, which keeps them idempotent and
 * testable. Authentication happens in the handshake with the same Bearer header as the
 * API, so no token ever appears in a URL.
 *
 * A dropped connection is retried with exponential backoff (1, 2, 4, 8, 16, then every
 * 30 seconds). The server sends `GAME_STATE` on every reconnect, so a retry heals the
 * state without the client asking for a snapshot. A rejected handshake (401) is retried
 * once after a forced token refresh; if that fails the player is signed out.
 */
class GameSocket(
    private val url: HttpUrl,
    private val api: ApiClient,
    private val http: OkHttpClient,
    private val json: Json = NetJson,
    private val scope: CoroutineScope,
) {

    private val _events = MutableSharedFlow<SocketEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every state change and game event, for the ViewModel to collect. */
    val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    @Volatile
    private var running = false

    @Volatile
    private var socket: WebSocket? = null

    private var connectJob: Job? = null
    private var retryJob: Job? = null
    private var pingJob: Job? = null
    private val attempt = AtomicInteger(0)

    /** Starts connecting; repeated calls are ignored until [stop] is called. */
    fun start() {
        if (running) return
        running = true
        attempt.set(0)
        connect()
    }

    /** Closes the connection for good; the server keeps the game running. */
    fun stop() {
        if (!running && socket == null) return
        running = false
        retryJob?.cancel()
        retryJob = null
        connectJob?.cancel()
        connectJob = null
        stopPing()
        socket?.close(CLOSE_NORMAL, "bye")
        socket = null
        emit(SocketEvent.State(SocketState.CLOSED))
    }

    private fun connect() {
        retryJob?.cancel()
        retryJob = null
        connectJob?.cancel()
        connectJob = scope.launch {
            emit(SocketEvent.State(if (attempt.get() == 0) SocketState.CONNECTING else SocketState.RECONNECTING))

            val session = try {
                api.validSession()
            } catch (e: ApiException) {
                Log.w(TAG, "cannot obtain a token for the socket", e)
                emit(SocketEvent.Failure(e))
                scheduleReconnect()
                return@launch
            }
            if (session == null) {
                emit(SocketEvent.Failure(ApiException(ApiError.TOKEN_INVALID)))
                return@launch
            }
            if (!running) return@launch

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${session.accessToken}")
                .build()
            socket = http.newWebSocket(request, listener)
        }
    }

    private fun scheduleReconnect() {
        if (!running) return
        val number = attempt.incrementAndGet()
        val waitMs = backoffMs(number)
        Log.i(TAG, "reconnecting in ${waitMs}ms (attempt $number)")
        emit(SocketEvent.State(SocketState.RECONNECTING))
        retryJob = scope.launch {
            delay(waitMs)
            if (running) connect()
        }
    }

    /** 1, 2, 4, 8, 16 seconds, then every 30 seconds. */
    private fun backoffMs(attempt: Int): Long =
        (1_000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(MAX_BACKOFF_MS)

    private fun emit(event: SocketEvent) {
        _events.tryEmit(event)
    }

    /**
     * Keeps the connection alive with the application-level `PING` the server answers
     * with `PONG` (TZ §8). The server ignores protocol-level ping frames, and an idle
     * connection is reset after about fifteen seconds; this is the keepalive that works.
     */
    private fun startPing(webSocket: WebSocket) {
        stopPing()
        pingJob = scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                if (!webSocket.send("PING")) return@launch
            }
        }
    }

    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt.set(0)
            startPing(webSocket)
            emit(SocketEvent.State(SocketState.OPEN))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { json.decodeFromString<GameEventDto>(text) }.getOrNull()
            if (message == null) {
                Log.w(TAG, "malformed socket message: $text")
                return
            }
            // PONG is the server's answer to a client ping; it carries no game.
            if (message.type == "PONG") return
            emit(SocketEvent.Message(message.type, message.game))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!running) return
            stopPing()
            socket = null
            Log.w(TAG, "socket failed: ${response?.code ?: "-"} ${t.message}")

            if (response?.code == 401) {
                // The access token looked valid locally but the server disagreed: refresh
                // once and reconnect at once, without spending the backoff.
                scope.launch {
                    val refreshed = try {
                        api.forceRefresh()
                    } catch (e: ApiException) {
                        Log.w(TAG, "refresh after a rejected handshake failed", e)
                        null
                    }
                    if (refreshed == null) {
                        running = false
                        emit(SocketEvent.Failure(ApiException(ApiError.TOKEN_INVALID)))
                        emit(SocketEvent.State(SocketState.CLOSED))
                    } else {
                        attempt.set(0)
                        connect()
                    }
                }
            } else if (response?.code == 403) {
                // The account is gone or not allowed to play: retrying would not help.
                running = false
                emit(SocketEvent.Failure(ApiException(ApiError.UNAUTHORIZED, httpStatus = 403)))
                emit(SocketEvent.State(SocketState.CLOSED))
            } else {
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!running) return
            stopPing()
            socket = null
            scheduleReconnect()
        }
    }

    private companion object {
        const val TAG = "GameSocket"
        const val CLOSE_NORMAL = 1000
        const val MAX_BACKOFF_MS = 30_000L

        /** Below the ~15 s after which an idle connection is reset. */
        const val PING_INTERVAL_MS = 10_000L
    }
}