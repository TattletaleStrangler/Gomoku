package com.oldmermaidman.gomoku.online

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.oldmermaidman.gomoku.GomokuApp
import com.oldmermaidman.gomoku.net.ApiException
import com.oldmermaidman.gomoku.net.dto.GameHistoryDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The history screen's state: the player's finished games, newest first. */
data class OnlineHistoryUi(
    val loading: Boolean = false,
    val error: String? = null,
    val games: List<GameHistoryDto> = emptyList(),
)

/**
 * The online game history (FEATURES.md, B2).
 *
 * The list is read once per visit and can be refreshed: the server returns finished
 * games only, with the opponent, the result and the rating change as one row each.
 */
class OnlineHistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val net = (app as GomokuApp).net
    private val context = app
    private var ui = OnlineHistoryUi()
    private val _state = MutableLiveData(ui)
    val state: LiveData<OnlineHistoryUi> = _state

    /** Re-reads the list; a failure keeps whatever is already on screen. */
    fun refresh() {
        if (ui.loading) return
        ui = ui.copy(loading = true, error = null)
        _state.value = ui
        viewModelScope.launch {
            ui = try {
                OnlineHistoryUi(games = withContext(Dispatchers.IO) { net.api.history() })
            } catch (e: ApiException) {
                ui.copy(loading = false, error = context.getString(e.error.messageRes))
            }
            _state.value = ui
        }
    }
}