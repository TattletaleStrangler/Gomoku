package com.oldmermaidman.gomoku.online

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.oldmermaidman.gomoku.GomokuApp
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.net.ApiError
import com.oldmermaidman.gomoku.net.ApiException
import com.oldmermaidman.gomoku.net.dto.GameSnapshotDto
import com.oldmermaidman.gomoku.net.dto.LobbyGameDto
import com.oldmermaidman.gomoku.prefs.OnlineStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The room screen's state.
 *
 * [waiting] is a room this player created and is alone in; [resume] is an active game
 * the player can return to; [openGame] is a one-shot signal for the screen to open the
 * game, cleared with [consumeOpenGame] once it has.
 */
data class OnlineUi(
    val signedIn: Boolean = false,
    val nickname: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val waiting: GameSnapshotDto? = null,
    val resume: GameSnapshotDto? = null,
    val openGame: GameSnapshotDto? = null,
    /** Public rooms waiting for an opponent, oldest first. */
    val lobby: List<LobbyGameDto> = emptyList(),
    val lobbyLoading: Boolean = false,
    val lobbyError: String? = null,
)

/** The settings the player last used, so the form comes back the way it was left. */
data class QuickSettings(
    val rule: String,
    val timeControl: String,
    val color: String?,
)

/**
 * Creating a room, joining by code, and waiting for the opponent.
 *
 * The waiting room is polled over REST rather than kept on a socket: one request every
 * two seconds is cheap, and it means the room screen does not have to own a socket that
 * the game screen immediately needs. The socket is opened only when the game starts.
 */
class OnlineViewModel(app: Application) : AndroidViewModel(app) {

    private val net = (app as GomokuApp).net
    private val context = app
    private val store = OnlineStateStore(app)

    private var ui = OnlineUi()
    private val _state = MutableLiveData(ui)
    val state: LiveData<OnlineUi> = _state

    private var pollJob: Job? = null

    init {
        val session = net.tokens.session()
        ui = ui.copy(signedIn = session != null, nickname = session?.nickname.orEmpty())
        publish()
    }

    /** Re-reads the session and the open game; called every time the screen appears. */
    fun onScreenStarted() {
        val session = net.tokens.session()
        ui = ui.copy(signedIn = session != null, nickname = session?.nickname.orEmpty())
        publish()
        if (ui.waiting == null) resumeOpenGame()
        if (ui.signedIn && ui.waiting == null) refreshLobby()
    }

    fun createRoom(rule: String, timeControl: String, color: String) {
        if (ui.busy || ui.waiting != null) return
        rememberQuick(rule, timeControl, color)
        ui = ui.copy(busy = true, error = null)
        publish()
        viewModelScope.launch {
            try {
                val game = withContext(Dispatchers.IO) { net.api.createGame(rule, timeControl, color) }
                if (game.status == "WAITING") startWaiting(game) else open(game)
            } catch (e: ApiException) {
                handleCommandFailure(e)
            }
        }
    }

    /**
     * Quick match (FEATURES.md, B1): the server joins the oldest public room with these
     * settings, or opens one and we wait in it. A null [color] means "no preference".
     */
    fun quickMatch(rule: String, timeControl: String, color: String?) {
        if (ui.busy || ui.waiting != null) return
        rememberQuick(rule, timeControl, color)
        ui = ui.copy(busy = true, error = null)
        publish()
        viewModelScope.launch {
            try {
                val game = withContext(Dispatchers.IO) { net.api.quickGame(rule, timeControl, color) }
                if (game.status == "WAITING") startWaiting(game) else open(game)
            } catch (e: ApiException) {
                handleCommandFailure(e)
            }
        }
    }

    /** Reloads the open-games list; a failure keeps the previous rows on screen. */
    fun refreshLobby() {
        if (!ui.signedIn) return
        ui = ui.copy(lobbyLoading = true)
        publish()
        viewModelScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) { net.api.lobby() }
                ui = ui.copy(lobby = rows, lobbyLoading = false, lobbyError = null)
            } catch (e: ApiException) {
                ui = ui.copy(
                    lobbyLoading = false,
                    lobbyError = context.getString(e.error.messageRes),
                )
            }
            publish()
        }
    }

    /** The settings last used; null on the first visit, when the form keeps its defaults. */
    fun savedQuickSettings(): QuickSettings? {
        val rule = store.quickRule ?: return null
        return QuickSettings(rule, store.quickTimeControl ?: "UNTIMED", store.quickColor)
    }

    private fun rememberQuick(rule: String, timeControl: String, color: String?) {
        store.quickRule = rule
        store.quickTimeControl = timeControl
        store.quickColor = color
    }

    fun joinRoom(rawCode: String, color: String? = null) {
        if (ui.busy || ui.waiting != null) return
        val code = rawCode.trim().uppercase()
        if (!code.matches(Regex("^[A-Z0-9]{6}$"))) {
            ui = ui.copy(error = context.getString(R.string.error_validation))
            publish()
            return
        }
        ui = ui.copy(busy = true, error = null)
        publish()
        viewModelScope.launch {
            try {
                val game = withContext(Dispatchers.IO) { net.api.joinGame(code, color) }
                open(game)
            } catch (e: ApiException) {
                handleCommandFailure(e)
            }
        }
    }

    /** Closes the waiting room: the server discards it, nothing is lost. */
    fun leaveWaitingRoom() {
        val waiting = ui.waiting ?: return
        if (ui.busy) return
        ui = ui.copy(busy = true)
        publish()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { net.api.leave(waiting.gameId) }
            } catch (e: ApiException) {
                // The room is abandoned either way; a failed call only means the server
                // will close it on its own.
            }
            pollJob?.cancel()
            store.clear()
            ui = ui.copy(busy = false, waiting = null)
            publish()
        }
    }

    /** Opens the active game the player came back to. */
    fun openResume() {
        val game = ui.resume ?: return
        open(game)
    }

    /** The screen has opened the game it was asked to; forget the request. */
    fun consumeOpenGame() {
        if (ui.openGame == null) return
        ui = ui.copy(openGame = null)
        publish()
    }

    fun clearError() {
        if (ui.error == null) return
        ui = ui.copy(error = null)
        publish()
    }

    override fun onCleared() {
        pollJob?.cancel()
    }

    // ---- internals --------------------------------------------------------

    /**
     * Looks for the player's open game in the local store and asks the server what state
     * it is in: a waiting room reopens the waiting screen, an active game offers a way
     * back in, and anything finished is forgotten.
     */
    private fun resumeOpenGame() {
        val id = store.gameId ?: return
        viewModelScope.launch {
            val game = try {
                withContext(Dispatchers.IO) { net.api.game(id) }
            } catch (e: ApiException) {
                if (e.error == ApiError.GAME_NOT_FOUND ||
                    e.error == ApiError.NOT_A_PARTICIPANT ||
                    e.isAuthFailure
                ) {
                    store.clear()
                    ui = ui.copy(resume = null)
                    publish()
                }
                return@launch
            }
            when (game.status) {
                "WAITING" -> startWaiting(game)
                "ACTIVE" -> {
                    ui = ui.copy(resume = game)
                    publish()
                }
                else -> {
                    store.clear()
                    ui = ui.copy(resume = null)
                    publish()
                }
            }
        }
    }

    private fun startWaiting(game: GameSnapshotDto) {
        ui = ui.copy(busy = false, error = null, waiting = game, resume = null)
        store.save(game.gameId, game.code)
        publish()

        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_MS)
                val current = try {
                    withContext(Dispatchers.IO) { net.api.game(game.gameId) }
                } catch (e: ApiException) {
                    if (e.isAuthFailure) {
                        ui = ui.copy(waiting = null, error = context.getString(e.error.messageRes))
                        publish()
                        return@launch
                    }
                    // A hiccup: keep waiting, the next poll may succeed.
                    continue
                }
                when (current.status) {
                    "WAITING" -> Unit
                    "ACTIVE" -> {
                        open(current)
                        return@launch
                    }
                    else -> {
                        store.clear()
                        ui = ui.copy(waiting = null, error = context.getString(R.string.error_room_closed))
                        publish()
                        return@launch
                    }
                }
            }
        }
    }

    private fun open(game: GameSnapshotDto) {
        store.save(game.gameId, game.code)
        ui = ui.copy(
            busy = false,
            error = null,
            waiting = null,
            resume = null,
            openGame = game,
        )
        publish()
    }

    /**
     * A create or join failed. The one failure worth recovering from is "you already
     * have a game": the server names it in `details.gameId`, so the screen can offer a
     * way back instead of only complaining.
     */
    private suspend fun handleCommandFailure(e: ApiException) {
        if (e.error == ApiError.ACTIVE_GAME_EXISTS) {
            val id = e.details?.get("gameId")?.jsonPrimitive?.contentOrNull
            if (id != null) {
                try {
                    val game = withContext(Dispatchers.IO) { net.api.game(id) }
                    if (game.status == "WAITING") {
                        startWaiting(game)
                    } else {
                        open(game)
                    }
                    return
                } catch (_: ApiException) {
                    // Fall through to the plain error below.
                }
            }
        }
        ui = ui.copy(busy = false, error = context.getString(e.error.messageRes))
        publish()
    }

    private fun publish() {
        _state.value = ui
    }

    private companion object {
        /** How often the waiting room asks the server whether the opponent has joined. */
        const val POLL_MS = 2_000L
    }
}