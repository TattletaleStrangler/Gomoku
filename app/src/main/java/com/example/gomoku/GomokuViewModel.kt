package com.example.gomoku

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.gomoku.engine.BuiltInAI
import com.example.gomoku.engine.NativeExe
import com.example.gomoku.engine.RapfiGomokuEngine
import com.example.gomoku.model.GomokuGame
import com.example.gomoku.model.Move
import com.example.gomoku.model.PlaceOutcome
import com.example.gomoku.model.Stone
import com.example.gomoku.prefs.AppSettings
import com.example.gomoku.rules.ForbiddenMoves
import com.example.gomoku.ui.SettingsDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GameUiState(
    val game: GomokuGame,
    val revision: Int,
    val thinking: Boolean,
    val engineAvailable: Boolean,
    val engineChecked: Boolean,
    val status: String,
    val engineBadge: String
)

/** Immutable snapshot of the position passed to the AI on a background thread. */
private data class GameSnapshot(
    val board: Array<Array<Stone>>,
    val size: Int,
    val aiColor: Stone,
    val forbidden: Boolean,
    /**
     * Stones in the order they were played. Rapfi infers its own color from the
     * first entry of a BOARD command, so this order must be preserved
     * (see RapfiGomokuEngine).
     */
    val history: List<Move>
) {
    constructor(g: GomokuGame) : this(
        Array(g.size) { y -> g.board[y].copyOf() },
        g.size,
        g.aiColor,
        g.forbiddenMoves,
        g.history.toList()
    )
}

class GomokuViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app
    private val settings = AppSettings(app)
    private val engine = RapfiGomokuEngine(NativeExe(app))
    private val builtIn = BuiltInAI()

    var game: GomokuGame = settings.restoreGame()
        private set

    val currentSettings: AppSettings get() = settings

    private val _uiState = MutableLiveData<GameUiState>()
    val uiState: LiveData<GameUiState> = _uiState

    private var revision = 0
    private var thinking = false
    private var generation = 0
    private var aiJob: Job? = null
    private var engineChecked = false

    init {
        checkEngineInBackground()
        if (!game.gameOver && game.currentTurn == game.aiColor) {
            requestAiMove()
        } else {
            publish()
        }
    }

    // ---- settings ---------------------------------------------------------

    fun applySettings(r: SettingsDialog.Result) {
        val sizeChanged = r.boardSize != settings.boardSize
        val colorChanged = r.playerColor != settings.playerColor

        settings.boardSize = r.boardSize
        settings.playerColor = r.playerColor
        settings.forbiddenMoves = r.forbidden
        settings.showMoveNumbers = r.showNumbers
        settings.aiLevel = r.aiLevel
        settings.theme = r.theme

        // The forbidden-move rule applies to future moves immediately.
        game.forbiddenMoves = r.forbidden

        if (sizeChanged || colorChanged) {
            startNewGame()
        }
        settings.saveGame(game)
        publish()
    }

    fun cycleTheme() {
        settings.theme = (settings.theme + 1) % 3
        publish()
    }

    // ---- game actions -----------------------------------------------------

    fun onBoardTapped(x: Int, y: Int) {
        if (thinking || game.gameOver) return
        if (game.currentTurn != game.playerColor) return

        val outcome = game.place(x, y, game.playerColor)
        if (outcome == PlaceOutcome.INVALID || outcome == PlaceOutcome.OCCUPIED) return

        settings.saveGame(game)
        publish()
        if (!game.gameOver) requestAiMove()
    }

    fun undo() {
        if (thinking) return
        if (game.undo()) {
            settings.saveGame(game)
            publish()
            // Undoing the AI's first move (human plays white) puts black back to move.
            if (!game.gameOver && game.currentTurn == game.aiColor) requestAiMove()
        }
    }

    fun resign() {
        if (thinking || game.gameOver) return
        game.humanResigns()
        settings.saveGame(game)
        publish()
    }

    fun startNewGame() {
        generation++
        aiJob?.cancel()
        thinking = false
        game = GomokuGame(settings.boardSize, settings.playerColor, settings.forbiddenMoves)
        settings.saveGame(game)
        publish()
        if (game.currentTurn == game.aiColor) requestAiMove()
    }

    override fun onCleared() {
        aiJob?.cancel()
        engine.stop()
        super.onCleared()
    }

    // ---- AI ---------------------------------------------------------------

    private fun requestAiMove() {
        val gen = generation
        val snapshot = GameSnapshot(game)
        thinking = true
        publish()

        aiJob = viewModelScope.launch(Dispatchers.IO) {
            val move = computeAiMove(snapshot)
            if (gen != generation) return@launch
            withContext(Dispatchers.Main) {
                if (gen != generation) return@withContext
                applyAiMove(move)
            }
        }
    }

    private fun applyAiMove(move: Move?) {
        if (move != null && !game.gameOver && game.currentTurn == game.aiColor) {
            game.place(move.x, move.y, game.aiColor)
        }
        thinking = false
        settings.saveGame(game)
        publish()
    }

    private fun computeAiMove(snapshot: GameSnapshot): Move? {
        var move: Move? = null
        if (snapshot.size == 15 && engine.available) {
            // Stones are passed in move order — Rapfi derives its own color from
            // the first BOARD entry (see RapfiGomokuEngine).
            move = engine.nextMove(
                moves = snapshot.history,
                aiColor = snapshot.aiColor,
                boardSize = snapshot.size,
                renju = snapshot.forbidden,
                timeLimitMs = rapfiTimeBudget()
            )
            if (move != null && !isValidAiMove(snapshot, move)) move = null
        }
        if (move == null) {
            move = builtIn.nextMove(
                current = snapshot.board,
                aiColor = snapshot.aiColor,
                maxDepth = 99,                            // ID controls depth
                timeLimitMs = aiTimeBudget(),
                forbiddenMoves = snapshot.forbidden
            )
        }
        return move
    }

    private fun isValidAiMove(snapshot: GameSnapshot, move: Move): Boolean {
        if (move.x !in 0 until snapshot.size || move.y !in 0 until snapshot.size) return false
        if (snapshot.board[move.y][move.x] != Stone.EMPTY) return false
        if (snapshot.aiColor == Stone.BLACK && snapshot.forbidden) {
            val board = snapshot.board
            board[move.y][move.x] = Stone.BLACK
            val ok = !ForbiddenMoves.isForbidden(board, move.x, move.y)
            board[move.y][move.x] = Stone.EMPTY
            if (!ok) return false
        }
        return true
    }

    /** Time budget for the built-in AI (TZ: ≤3 s per move). */
    private fun aiTimeBudget(): Long =
        if (settings.aiLevel == AppSettings.LEVEL_STRONG) 3000L else 1500L

    /**
     * Time budget handed to Rapfi as INFO timeout_turn. Kept comfortably below
     * RapfiGomokuEngine's 12 s reply timeout. Rapfi searches deeper than the
     * built-in AI in the same wall-clock time.
     */
    private fun rapfiTimeBudget(): Long =
        if (settings.aiLevel == AppSettings.LEVEL_STRONG) 5000L else 2000L

    private fun checkEngineInBackground() {
        viewModelScope.launch(Dispatchers.IO) {
            engine.initAndSelfCheck()
            withContext(Dispatchers.Main) {
                engineChecked = true
                publish()
            }
        }
    }

    // ---- UI state ---------------------------------------------------------

    private fun publish() {
        revision++
        _uiState.value = GameUiState(
            game = game,
            revision = revision,
            thinking = thinking,
            engineAvailable = engine.available,
            engineChecked = engineChecked,
            status = buildStatus(),
            engineBadge = buildEngineBadge()
        )
    }

    private fun buildStatus(): String = when {
        game.gameOver -> when {
            game.forbiddenLoss -> context.getString(R.string.forbidden_loss)
            game.winner == Stone.BLACK -> context.getString(R.string.black_wins)
            game.winner == Stone.WHITE -> context.getString(R.string.white_wins)
            else -> context.getString(R.string.draw)
        }
        thinking -> context.getString(R.string.computer_thinking)
        game.currentTurn == game.playerColor -> context.getString(R.string.your_turn)
        else -> context.getString(R.string.computer_turn)
    }

    private fun buildEngineBadge(): String {
        val base = when {
            !engineChecked -> context.getString(R.string.engine_checking)
            engine.available && game.size == 15 -> context.getString(R.string.engine_rapfi)
            else -> context.getString(R.string.engine_builtin)
        }
        val err = engine.lastError
        return if (!engine.available && err != null) "$base ($err)" else base
    }
}
