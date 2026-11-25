package com.oldmermaidman.gomoku.online

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.oldmermaidman.gomoku.GomokuApp
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.model.Stone
import com.oldmermaidman.gomoku.net.ApiError
import com.oldmermaidman.gomoku.net.ApiException
import com.oldmermaidman.gomoku.net.RemoteClock
import com.oldmermaidman.gomoku.net.SocketEvent
import com.oldmermaidman.gomoku.net.SocketState
import com.oldmermaidman.gomoku.net.dto.GameSnapshotDto
import com.oldmermaidman.gomoku.prefs.OnlineStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Both clocks, the undo countdown and whether the undo button may be pressed now. */
data class OnlineClockUi(
    val timed: Boolean = false,
    val youRemainingMs: Long? = null,
    val opponentRemainingMs: Long? = null,
    val running: Stone? = null,
    /** Seconds left for the opponent to answer a pending request, or null. */
    val undoSeconds: Int? = null,
    /** Seconds left for the opponent to answer a draw offer, or null. */
    val drawSeconds: Int? = null,
    /** True during the two seconds after our own move when an undo may be asked for. */
    val canUndo: Boolean = false,
)

/**
 * The online game screen.
 *
 * The server is the only authority: every command answers with the whole snapshot and
 * every socket event carries one, so the ViewModel simply replaces its copy and rebuilds
 * the screen. The clocks are a local projection of the last snapshot ([RemoteClock]);
 * nothing here decides an outcome.
 *
 * The undo rules follow FEATURES.md: the button shows the attempts left and is live only
 * in the two seconds after our move; the opponent's answer arrives as a snapshot with a
 * pending `undo` block; the accepted move comes back as `lastUndoneMove` and is drawn as
 * a ghost until the next stone.
 */
class OnlineGameViewModel(
    app: Application,
    private val gameId: String,
) : AndroidViewModel(app) {

    private val net = (app as GomokuApp).net
    private val context = app
    private val store = OnlineStateStore(app)

    private val socket = net.socket(viewModelScope)

    private var ui: OnlineGameUi? = null
    private var snapshot: GameSnapshotDto? = null
    private var remote: RemoteClock? = null
    private var receivedAtMs = 0L
    private var undoWindowUntilMs = 0L
    private var socketState = SocketState.CLOSED
    private var busy = false
    private var ticker: Job? = null
    private var flashJob: Job? = null

    private val _state = MutableLiveData<OnlineGameUi?>()
    val state: LiveData<OnlineGameUi?> = _state

    private val _clock = MutableLiveData(OnlineClockUi())
    val clock: LiveData<OnlineClockUi> = _clock

    /** A short message shown over the board: "accepted", "declined", an error. */
    private val _flash = MutableLiveData<String?>()
    val flash: LiveData<String?> = _flash

    /** True when the game cannot be shown any more and the screen should close. */
    private val _gone = MutableLiveData(false)
    val gone: LiveData<Boolean> = _gone

    /**
     * The answer to [rematch]: a snapshot of another game, which the screen opens
     * instead of applying it here. Cleared with [consumeRematch] once it has.
     */
    private val _rematch = MutableLiveData<GameSnapshotDto?>()
    val rematchGame: LiveData<GameSnapshotDto?> = _rematch

    init {
        viewModelScope.launch {
            socket.events.collect { onSocketEvent(it) }
        }
    }

    /** The screen is visible: fetch a fresh snapshot and listen for events. */
    fun onScreenStarted() {
        socket.start()
        reload(showError = snapshot == null)
        startTicker()
    }

    /** The screen went away: the server keeps the clocks running, the client listens no more. */
    fun onScreenStopped() {
        socket.stop()
        ticker?.cancel()
        ticker = null
    }

    // ---- commands ---------------------------------------------------------

    fun move(x: Int, y: Int) {
        val current = snapshot ?: return
        val screen = ui ?: return
        if (busy || screen.finished || !screen.yourTurn) return
        if (current.undo != null) return
        if (current.moves.any { it.x == x && it.y == y }) return
        // `ply` in a snapshot is the number of the next move, which is exactly what the
        // server expects back (TZ_BACKEND.md §7).
        command { net.api.move(gameId, current.ply, x, y) }
    }

    fun resign() {
        val screen = ui ?: return
        if (busy || screen.finished) return
        command { net.api.resign(gameId) }
    }

    /**
     * Offers a rematch of this finished game, or accepts the one already offered
     * (FUTURE.md). The room belongs to another game, so the snapshot is not applied
     * here: the screen opens it and closes this one.
     */
    fun rematch() {
        val screen = ui ?: return
        if (busy || !screen.finished) return
        busy = true
        publishUi()
        viewModelScope.launch {
            try {
                val next = withContext(Dispatchers.IO) { net.api.rematch(gameId) }
                busy = false
                publishUi()
                // Remember the new game, so the room screen can resume into it.
                store.save(next.gameId, next.code)
                _rematch.value = next
            } catch (e: ApiException) {
                busy = false
                publishUi()
                flash(e)
            }
        }
    }

    /** The screen has opened the game the rematch produced; forget the request. */
    fun consumeRematch() {
        if (_rematch.value == null) return
        _rematch.value = null
    }

    /**
     * Stops being paired with this opponent again (FUTURE.md). Offered on the finished
     * screen, where the game definitely had two players.
     */
    fun blockOpponent() {
        val screen = ui ?: return
        if (busy || !screen.finished) return
        busy = true
        publishUi()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { net.api.block(gameId) }
                busy = false
                publishUi()
                flash(context.getString(R.string.online_block_done))
            } catch (e: ApiException) {
                busy = false
                publishUi()
                flash(e)
            }
        }
    }

    fun requestUndo() {
        val screen = ui ?: return
        // The button is only a hint: the same rule guards the call, so a stale tap after
        // the two-second window (or by the player who still has to move) sends nothing.
        if (!canRequestUndo(screen, SystemClock.elapsedRealtime(), undoWindowUntilMs)) return
        // A refusal is shown as "declined", never as its actual reason (FEATURES.md).
        command(quietUndoFailure = true) { net.api.requestUndo(gameId) }
    }

    fun decideUndo(accept: Boolean) {
        val screen = ui ?: return
        val pending = screen.pendingUndo ?: return
        if (busy || pending.requestedByMe) return
        command { net.api.decideUndo(gameId, accept) }
    }

    /**
     * Offers a draw (FUTURE.md). Unlike an undo it is possible at any moment of a
     * running game; the server gives the opponent ten seconds and the attempt is spent
     * whether they agree, refuse or say nothing.
     */
    fun offerDraw() {
        val screen = ui ?: return
        if (busy || screen.finished) return
        if (screen.pendingUndo != null || screen.pendingDraw != null) return
        if (screen.drawRemaining <= 0) return
        command { net.api.drawOffer(gameId) }
    }

    /** The opponent's answer to a draw offer; only the asked side may send it. */
    fun decideDraw(accept: Boolean) {
        val screen = ui ?: return
        val pending = screen.pendingDraw ?: return
        if (busy || pending.requestedByMe) return
        command { net.api.drawDecision(gameId, accept) }
    }

    override fun onCleared() {
        socket.stop()
        ticker?.cancel()
        flashJob?.cancel()
    }

    // ---- state ------------------------------------------------------------

    private fun reload(showError: Boolean) {
        viewModelScope.launch {
            try {
                val game = withContext(Dispatchers.IO) { net.api.game(gameId) }
                applySnapshot(game)
            } catch (e: ApiException) {
                when {
                    e.error == ApiError.GAME_NOT_FOUND || e.error == ApiError.NOT_A_PARTICIPANT -> {
                        store.clear()
                        _gone.value = true
                    }
                    e.isAuthFailure -> {
                        store.clear()
                        flash(e)
                        _gone.value = true
                    }
                    showError -> flash(e)
                    else -> Unit
                }
            }
        }
    }

    /**
     * Replaces the local copy with [next] unless it is older than what is already shown:
     * a slow REST answer must not overwrite a newer socket event. Returns false when the
     * snapshot was ignored, so the caller can still publish the command's own change.
     */
    private fun applySnapshot(next: GameSnapshotDto): Boolean {
        val previous = snapshot
        if (previous != null) {
            val oldTime = instantOf(previous.serverTime)?.toEpochMilli()
            val newTime = instantOf(next.serverTime)?.toEpochMilli()
            if (oldTime != null && newTime != null && newTime < oldTime) return false
        }

        snapshot = next
        receivedAtMs = SystemClock.elapsedRealtime()
        remote = remoteClockOf(next, receivedAtMs)

        if (next.status == "FINISHED" || next.status == "ABANDONED") {
            store.clear()
        } else {
            store.save(next.gameId, next.code)
        }

        // A pending request disappeared: it was accepted exactly when a stone left the
        // board, which is true for both players' snapshots.
        if (previous?.undo != null && next.undo == null) {
            flash(if (next.moves.size < previous.moves.size) R.string.online_undo_accepted else R.string.online_undo_declined)
        }
        // A draw offer that disappeared was either agreed to (the game is a draw now)
        // or refused; the panel itself stays silent about which.
        if (previous?.draw != null && next.draw == null) {
            flash(if (next.result == "DRAW") R.string.online_draw_accepted else R.string.online_draw_declined)
        }

        undoWindowUntilMs = nextUndoWindow(previous, next, receivedAtMs, undoWindowUntilMs)
        publishUi()
        publishClock()
        return true
    }

    private fun remoteClockOf(game: GameSnapshotDto, receivedAt: Long): RemoteClock {
        val serverTime = instantOf(game.serverTime)?.toEpochMilli() ?: System.currentTimeMillis()
        return RemoteClock(
            blackRemainingMs = game.clocks?.blackRemainingMs,
            whiteRemainingMs = game.clocks?.whiteRemainingMs,
            running = stoneOf(game.clocks?.running).takeIf { it != Stone.EMPTY },
            serverTimeMs = serverTime,
            receivedAtMs = receivedAt,
        )
    }

    private fun publishUi() {
        val game = snapshot ?: return
        ui = buildOnlineGameUi(game, socketState, busy)
        _state.value = ui
    }

    private fun publishClock(now: Long = SystemClock.elapsedRealtime()) {
        val game = snapshot ?: return
        val clock = remote ?: return
        val screen = ui ?: return

        val deadline = instantOf(game.undo?.deadline)?.toEpochMilli()
        val undoSeconds = if (deadline != null) {
            ((deadline - clock.serverNowMs(now) + 999) / 1_000).coerceAtLeast(0).toInt()
        } else {
            null
        }
        val drawDeadline = instantOf(game.draw?.deadline)?.toEpochMilli()
        val drawSeconds = if (drawDeadline != null) {
            ((drawDeadline - clock.serverNowMs(now) + 999) / 1_000).coerceAtLeast(0).toInt()
        } else {
            null
        }

        _clock.value = OnlineClockUi(
            timed = clock.timed,
            youRemainingMs = clock.remainingMs(screen.you, now),
            opponentRemainingMs = clock.remainingMs(screen.you.opponent(), now),
            running = clock.running,
            undoSeconds = undoSeconds,
            drawSeconds = drawSeconds,
            canUndo = canRequestUndo(screen, now, undoWindowUntilMs),
        )
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                publishClock()
                delay(TICK_MS)
            }
        }
    }

    // ---- socket -----------------------------------------------------------

    private fun onSocketEvent(event: SocketEvent) {
        when (event) {
            is SocketEvent.State -> {
                socketState = event.state
                publishUi()
            }
            is SocketEvent.Message -> {
                val game = event.game ?: return
                if (game.gameId != gameId) return
                applySnapshot(game)
            }
            is SocketEvent.Failure -> {
                flash(event.error)
            }
        }
    }

    // ---- helpers ----------------------------------------------------------

    private fun command(
        quietUndoFailure: Boolean = false,
        action: suspend () -> GameSnapshotDto,
    ) {
        if (busy) return
        busy = true
        publishUi()
        viewModelScope.launch {
            try {
                val next = withContext(Dispatchers.IO) { action() }
                busy = false
                if (!applySnapshot(next)) {
                    // The socket event for this move arrived first and is already on
                    // screen; the older REST answer is ignored, but `busy` still has to
                    // leave the UI state — otherwise the board and the undo button would
                    // stay locked until the next event.
                    publishUi()
                    publishClock()
                }
            } catch (e: ApiException) {
                busy = false
                publishUi()
                if (quietUndoFailure && e.error.isUndoRefusal) {
                    flash(R.string.online_undo_declined)
                } else {
                    flash(e)
                }
                // The local copy may be behind a racing move or a decided request.
                reload(showError = false)
            }
        }
    }

    private fun flash(error: ApiException) {
        flash(context.getString(error.error.messageRes))
    }

    private fun flash(res: Int) {
        flash(context.getString(res))
    }

    private fun flash(text: String) {
        _flash.value = text
        flashJob?.cancel()
        flashJob = viewModelScope.launch {
            delay(FLASH_MS)
            _flash.value = null
        }
    }

    /** Creates the ViewModel with the game it belongs to. */
    class Factory(
        private val application: Application,
        private val gameId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            OnlineGameViewModel(application, gameId) as T
    }

    private companion object {
        /** How often the clocks and the undo countdown are redrawn. */
        const val TICK_MS = 250L

        /** How long "accepted" / "declined" stays on screen. */
        const val FLASH_MS = 1_200L
    }
}