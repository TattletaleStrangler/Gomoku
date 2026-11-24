package com.oldmermaidman.gomoku.online

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.oldmermaidman.gomoku.GomokuApp
import com.oldmermaidman.gomoku.model.GomokuGame
import com.oldmermaidman.gomoku.model.Stone
import com.oldmermaidman.gomoku.net.ApiException
import com.oldmermaidman.gomoku.net.dto.GameSnapshotDto
import com.oldmermaidman.gomoku.net.dto.MoveDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the replay screen draws: the finished snapshot plus a board that can be shown
 * after any number of moves.
 */
data class OnlineReplayUi(
    val loading: Boolean = false,
    val error: String? = null,
    val snapshot: GameSnapshotDto? = null,
    val board: GomokuGame? = null,
    val moves: List<MoveDto> = emptyList(),
    val you: Stone = Stone.EMPTY,
)

/**
 * Replays one finished online game (FEATURES.md, B2).
 *
 * The moves come from the same `GET /games/{id}` the live screen uses, so a replayed
 * position is the one the server recorded — nothing is judged locally.
 */
class OnlineReplayViewModel(
    app: Application,
    private val gameId: String,
) : AndroidViewModel(app) {

    private val net = (app as GomokuApp).net
    private val context = app
    private var ui = OnlineReplayUi()
    private val _state = MutableLiveData(ui)
    val state: LiveData<OnlineReplayUi> = _state

    fun load() {
        if (ui.loading || ui.snapshot != null) return
        ui = ui.copy(loading = true, error = null)
        _state.value = ui
        viewModelScope.launch {
            ui = try {
                val game = withContext(Dispatchers.IO) { net.api.game(gameId) }
                OnlineReplayUi(
                    snapshot = game,
                    board = boardOf(game),
                    moves = game.moves,
                    you = stoneOf(game.you),
                )
            } catch (e: ApiException) {
                OnlineReplayUi(error = context.getString(e.error.messageRes))
            }
            _state.value = ui
        }
    }

    /** The final position, rebuilt move by move the same way the live board is. */
    private fun boardOf(game: GameSnapshotDto): GomokuGame {
        val you = stoneOf(game.you)
        val board = GomokuGame(
            GomokuGame.BOARD_SIZE,
            if (you == Stone.EMPTY) Stone.BLACK else you,
            game.rule == "RENJU",
        )
        for (move in game.moves) {
            val color = stoneOf(move.color)
            if (color == Stone.EMPTY) continue
            if (move.x !in 0 until board.size || move.y !in 0 until board.size) continue
            board.replay(move.x, move.y, color)
        }
        return board
    }

    /** Creates the ViewModel with the game it replays. */
    class Factory(
        private val application: Application,
        private val gameId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            OnlineReplayViewModel(application, gameId) as T
    }
}