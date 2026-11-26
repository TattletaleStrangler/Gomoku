package com.oldmermaidman.gomoku.online

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.oldmermaidman.gomoku.GomokuApp
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.net.ApiException
import com.oldmermaidman.gomoku.net.dto.BlockedPlayerDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The block list screen: everybody this player has blocked, newest first. */
data class OnlineBlocksUi(
    val loading: Boolean = false,
    val error: String? = null,
    val blocked: List<BlockedPlayerDto> = emptyList(),
    /** A one-shot line for the toast, for example after letting somebody back. */
    val message: String? = null,
)

/**
 * The player's own block list (FEATURES.md).
 *
 * A block happens in one tap on the game screen, so this is the way back: the list shows
 * who is blocked and one tap lets them back into matchmaking. Blocking somebody twice
 * and unblocking somebody who is not blocked are both fine, as far as the server cares.
 */
class OnlineBlocksViewModel(app: Application) : AndroidViewModel(app) {

    private val net = (app as GomokuApp).net
    private val context = app
    private var ui = OnlineBlocksUi()
    private val _state = MutableLiveData(ui)
    val state: LiveData<OnlineBlocksUi> = _state

    /** Re-reads the list; a failure keeps whatever is already on screen. */
    fun refresh() {
        if (ui.loading) return
        ui = ui.copy(loading = true, error = null)
        _state.value = ui
        viewModelScope.launch {
            ui = try {
                ui.copy(loading = false, blocked = withContext(Dispatchers.IO) { net.api.blocks() })
            } catch (e: ApiException) {
                ui.copy(loading = false, error = context.getString(e.error.messageRes))
            }
            _state.value = ui
        }
    }

    /** Lets one player back and reloads the list. */
    fun unblock(userId: String, nickname: String?) {
        if (ui.loading) return
        ui = ui.copy(loading = true, error = null)
        _state.value = ui
        viewModelScope.launch {
            ui = try {
                withContext(Dispatchers.IO) { net.api.unblock(userId) }
                ui.copy(
                    loading = false,
                    blocked = withContext(Dispatchers.IO) { net.api.blocks() },
                    message = context.getString(R.string.blocks_unblocked, nickname.orEmpty()),
                )
            } catch (e: ApiException) {
                ui.copy(loading = false, error = context.getString(e.error.messageRes))
            }
            _state.value = ui
        }
    }

    /** The toast has been shown. */
    fun consumeMessage() {
        ui = ui.copy(message = null)
        _state.value = ui
    }
}