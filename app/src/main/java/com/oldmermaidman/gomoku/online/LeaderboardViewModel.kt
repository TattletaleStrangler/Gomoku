package com.oldmermaidman.gomoku.online

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.oldmermaidman.gomoku.GomokuApp
import com.oldmermaidman.gomoku.net.ApiException
import com.oldmermaidman.gomoku.net.dto.LeaderboardRowDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The leaderboard screen's state. [you] is the signed-in nickname, so the screen can
 * mark the player's own row.
 */
data class LeaderboardUi(
    val loading: Boolean = false,
    val error: String? = null,
    val rows: List<LeaderboardRowDto> = emptyList(),
    val you: String = "",
)

/** The top players by Elo (FEATURES.md, B2). */
class LeaderboardViewModel(app: Application) : AndroidViewModel(app) {

    private val net = (app as GomokuApp).net
    private val context = app
    private var ui = LeaderboardUi(you = net.tokens.session()?.nickname.orEmpty())
    private val _state = MutableLiveData(ui)
    val state: LiveData<LeaderboardUi> = _state

    /** Re-reads the board; a failure keeps whatever is already on screen. */
    fun refresh() {
        if (ui.loading) return
        ui = ui.copy(loading = true, error = null, you = net.tokens.session()?.nickname.orEmpty())
        _state.value = ui
        viewModelScope.launch {
            ui = try {
                ui.copy(loading = false, rows = withContext(Dispatchers.IO) { net.api.leaderboard() })
            } catch (e: ApiException) {
                ui.copy(loading = false, error = context.getString(e.error.messageRes))
            }
            _state.value = ui
        }
    }
}