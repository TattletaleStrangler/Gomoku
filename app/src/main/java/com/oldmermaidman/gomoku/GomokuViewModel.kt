package com.oldmermaidman.gomoku

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.oldmermaidman.gomoku.engine.BuiltInAI
import com.oldmermaidman.gomoku.engine.NativeExe
import com.oldmermaidman.gomoku.engine.RapfiGomokuEngine
import com.oldmermaidman.gomoku.model.GameResult
import com.oldmermaidman.gomoku.model.GomokuGame
import com.oldmermaidman.gomoku.model.Move
import com.oldmermaidman.gomoku.model.PlaceOutcome
import com.oldmermaidman.gomoku.model.Stone
import com.oldmermaidman.gomoku.model.resultOf
import com.oldmermaidman.gomoku.prefs.AppSettings
import com.oldmermaidman.gomoku.rules.ForbiddenMoves
import com.oldmermaidman.gomoku.ui.SettingsValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GameUiState(
    val game: GomokuGame,
    val revision: Int,
    val thinking: Boolean,
    val status: String,
    /**
     * Selected difficulty, e.g. "Сложность: Сложный". It implies which engine
     * plays, so the engine is not named separately in the header.
     */
    val difficultyLabel: String
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

/**
 * Game state holder.
 *
 * The board is always 15×15. The difficulty level doubles as the engine choice:
 * **Сложный** runs Rapfi, **Нормальный** runs the built-in AI. If Rapfi cannot
 * start, the hard level falls back to the built-in AI and says so in the header.
 *
 * The outcome of a finished game is reported through the status line — there is
 * no result dialog, so nothing interrupts the game.
 *
 * Statistics are cumulative, kept separately for each difficulty level and stored
 * between launches.
 */
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

    /** Cumulative wins / losses / draws, per difficulty level. */
    private var scores = settings.loadScores()

    /** Result already added to [scores]; null while the running game has none. */
    private var countedResult: GameResult? = null

    /** Level the result was counted for, so undo can roll back the right one. */
    private var countedLevel: Int = AppSettings.LEVEL_NORMAL

    /** False when the restored game was already finished before this session. */
    private var countedByThisSession = false

    init {
        // A game restored from disk may already be finished. It must not be added
        // to the statistics again, but undo still has to clear its result.
        countedResult = resultOf(game)
        countedLevel = settings.aiLevel
        countedByThisSession = false

        checkEngineInBackground()
        if (!game.gameOver && game.currentTurn == game.aiColor) {
            requestAiMove()
        } else {
            publish()
        }
    }

    // ---- settings ---------------------------------------------------------

    fun applySettings(r: SettingsValues) {
        val colorChanged = r.playerColor != settings.playerColor

        settings.playerColor = r.playerColor
        settings.forbiddenMoves = r.forbidden
        settings.showMoveNumbers = r.showNumbers
        settings.soundEnabled = r.soundEnabled
        settings.soundVolumeStep = r.soundVolumeStep
        settings.aiLevel = r.aiLevel
        settings.theme = r.theme

        // The forbidden-move rule applies to future moves immediately.
        game.forbiddenMoves = r.forbidden

        // Changing colour restarts the game. The engine follows the difficulty
        // and is chosen per move, so switching difficulty needs no restart.
        if (colorChanged) startNewGame()

        settings.saveGame(game)
        publish()
    }

    fun cycleTheme() {
        settings.theme = (settings.theme + 1) % 3
        publish()
    }

    /**
     * Re-reads the statistics from storage, for example after the settings screen
     * cleared them.
     *
     * The bookkeeping of an already counted result is dropped as well: that result
     * no longer exists in the statistics, so it must not be rolled back later.
     */
    fun reloadStats() {
        scores = settings.loadScores()
        countedResult = null
        countedByThisSession = false
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

    /**
     * Takes back the last round. It also reopens a finished game, so a player who
     * lost to a surprise five can replay that move instead of starting over.
     *
     * A result that was already counted is taken back with the move: losing,
     * undoing and then winning leaves only the win in the statistics.
     */
    fun undo() {
        if (thinking) return
        if (game.undo()) {
            countedResult?.let {
                if (countedByThisSession) {
                    scores.rollback(countedLevel, it)
                    settings.saveScores(scores)
                }
            }
            countedResult = null
            countedByThisSession = false

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
        // The finished result stays in the statistics; the new game starts clean.
        countedResult = null
        countedByThisSession = false

        game = settings.newGame()
        settings.saveGame(game)
        publish()
        if (game.currentTurn == game.aiColor) requestAiMove()
    }

    override fun onCleared() {
        aiJob?.cancel()
        engine.stop()   // sends END and force-kills if needed (TZ §4.3)
        // No super.onCleared() call: ViewModel.onCleared() is empty.
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

        // Difficulty selects the engine: "hard" plays Rapfi, "normal" the
        // built-in AI. If Rapfi is unavailable the built-in AI takes over.
        if (settings.aiLevel == AppSettings.LEVEL_STRONG && engine.available) {
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
                timeLimitMs = builtInTimeBudget(),
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

    /**
     * Time budget for the built-in AI. It plays the "normal" level, and also
     * serves as the fallback when Rapfi is unavailable at the "hard" level.
     */
    private fun builtInTimeBudget(): Long =
        if (settings.aiLevel == AppSettings.LEVEL_STRONG) 3000L else 1500L

    /**
     * Time budget handed to Rapfi as `INFO timeout_turn`, kept comfortably below
     * RapfiGomokuEngine's 12 s reply timeout.
     */
    private fun rapfiTimeBudget(): Long = 5000L

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

    /**
     * Adds a freshly finished game to the statistics, once. The result is
     * attributed to the level that was selected when the game ended.
     */
    private fun syncScore() {
        val result = resultOf(game) ?: return
        if (countedResult != null) return

        countedLevel = settings.aiLevel
        scores.record(countedLevel, result)
        settings.saveScores(scores)
        countedResult = result
        countedByThisSession = true
    }

    private fun publish() {
        syncScore()
        revision++
        _uiState.value = GameUiState(
            game = game,
            revision = revision,
            thinking = thinking,
            status = buildStatus(),
            difficultyLabel = buildDifficultyLabel()
        )
    }

    /** Also carries the result of a finished game — that is the only report shown. */
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

    private fun levelName(level: Int): String = context.getString(
        if (level == AppSettings.LEVEL_STRONG) R.string.level_strong else R.string.level_normal
    )

    /**
     * The header shows the difficulty rather than the engine name, because the
     * difficulty already implies which engine plays.
     *
     * The only addition is a short hint when the "hard" level cannot use Rapfi:
     * a silent fallback to the weaker engine would otherwise mislead the player.
     */
    private fun buildDifficultyLabel(): String {
        val base = context.getString(R.string.difficulty_label, levelName(settings.aiLevel))

        val hardWithoutEngine = settings.aiLevel == AppSettings.LEVEL_STRONG &&
            engineChecked && !engine.available
        return if (hardWithoutEngine) {
            base + context.getString(R.string.engine_fallback_suffix)
        } else {
            base
        }
    }
}
