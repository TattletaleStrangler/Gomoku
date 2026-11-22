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
     * a slow REST answer must not overwrite a newer socket event.
     */
    private fun applySnapshot(next: GameSnapshotDto) {
        val previous = snapshot
        if (previous != null) {
            val oldTime = instantOf(previous.serverTime)?.toEpochMilli()
            val newTime = instantOf(next.serverTime)?.toEpochMilli()
            if (oldTime != null && newTime != null && newTime < oldTime) return
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

        updateUndoWindow(previous, next)
        publishUi()
        publishClock()
    }

    /**
     * The server allows an undo request only in the two seconds after the move. The
     * snapshot does not carry the move's timestamp, so the arrival of a snapshot in which
     * our stone is the latest and the opponent is to answer starts the local window; the
     * server remains the judge of whether it is still open.
     */
    private fun updateUndoWindow(previous: GameSnapshotDto?, next: GameSnapshotDto) {
        val you = stoneOf(next.you)
        val last = next.moves.lastOrNull()
        val ownMoveIsLast = you != Stone.EMPTY && last != null && stoneOf(last.color) == you
        val opponentToMove = you != Stone.EMPTY && stoneOf(next.turn) != you
        if (next.status != "ACTIVE" || !ownMoveIsLast || !opponentToMove) {
            undoWindowUntilMs = 0L
            return
        }
        val sameMoves = previous != null && previous.moves.size == next.moves.size
        if (!sameMoves) undoWindowUntilMs = receivedAtMs + UNDO_WINDOW_MS
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

        _clock.value = OnlineClockUi(
            timed = clock.timed,
            youRemainingMs = clock.remainingMs(screen.you, now),
            opponentRemainingMs = clock.remainingMs(screen.you.opponent(), now),
            running = clock.running,
            undoSeconds = undoSeconds,
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
                applySnapshot(next)
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

        /** FEATURES.md: an undo may be asked for only in the first two seconds. */
        const val UNDO_WINDOW_MS = 2_000L

        /** How long "accepted" / "declined" stays on screen. */
        const val FLASH_MS = 1_200L
    }
}