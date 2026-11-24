package com.oldmermaidman.gomoku.online

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.oldmermaidman.gomoku.GomokuApp
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.net.ApiException
import com.oldmermaidman.gomoku.net.dto.StatsDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The account screen's state: who is signed in, their record, and the last failure. */
data class AccountUi(
    val signedIn: Boolean = false,
    val nickname: String = "",
    /** Elo of ranked (quick-play) games. */
    val rating: Int = 1200,
    val stats: StatsDto = StatsDto(),
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Sign-in, registration and account deletion.
 *
 * The screen is deliberately thin: every rule (nickname shape, password length) is
 * checked locally for a fast answer and again by the server, which stays authoritative.
 * The tokens live in the [com.oldmermaidman.gomoku.net.TokenStore]; this ViewModel only
 * asks the [com.oldmermaidman.gomoku.net.ApiClient] to fill it.
 */
class AccountViewModel(app: Application) : AndroidViewModel(app) {

    private val net = (app as GomokuApp).net
    private val context = app

    private var ui = AccountUi()
    private val _state = MutableLiveData(ui)
    val state: LiveData<AccountUi> = _state

    init {
        val session = net.tokens.session()
        if (session != null) {
            ui = ui.copy(signedIn = true, nickname = session.nickname)
            publish()
            viewModelScope.launch { loadProfile() }
        }
    }

    fun signIn(nickname: String, password: String) =
        authenticate(nickname, password, register = false)

    fun register(nickname: String, password: String) =
        authenticate(nickname, password, register = true)

    fun signOut() {
        if (ui.busy) return
        ui = ui.copy(busy = true, error = null)
        publish()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { net.api.logout() }
            ui = AccountUi()
            publish()
        }
    }

    fun deleteAccount(password: String) {
        if (ui.busy) return
        if (password.isBlank()) {
            ui = ui.copy(error = context.getString(R.string.account_delete_password))
            publish()
            return
        }
        ui = ui.copy(busy = true, error = null)
        publish()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { net.api.deleteAccount(password) }
                ui = AccountUi()
            } catch (e: ApiException) {
                ui = ui.copy(busy = false, error = message(e))
            }
            publish()
        }
    }

    /** Re-reads the profile and the record, for example after returning to the screen. */
    fun refreshProfile() {
        if (!net.tokens.hasSession() || ui.busy) return
        viewModelScope.launch { loadProfile() }
    }

    fun clearError() {
        if (ui.error == null) return
        ui = ui.copy(error = null)
        publish()
    }

    // ---- internals --------------------------------------------------------

    private fun authenticate(rawNickname: String, password: String, register: Boolean) {
        if (ui.busy) return
        val nickname = rawNickname.trim()
        if (!validNickname(nickname) || !validPassword(password)) {
            ui = ui.copy(error = context.getString(R.string.error_validation))
            publish()
            return
        }

        ui = ui.copy(busy = true, error = null)
        publish()
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    if (register) {
                        net.api.register(nickname, password)
                    } else {
                        net.api.login(nickname, password)
                    }
                }
                ui = ui.copy(
                    signedIn = true,
                    nickname = session.nickname.ifEmpty { nickname },
                    busy = false,
                )
                publish()
                loadProfile()
            } catch (e: ApiException) {
                ui = ui.copy(busy = false, error = message(e))
                publish()
            }
        }
    }

    private suspend fun loadProfile() {
        try {
            val profile = withContext(Dispatchers.IO) { net.api.profile() }
            ui = ui.copy(
                signedIn = true,
                nickname = profile.nickname,
                rating = profile.rating,
                stats = profile.stats,
                busy = false,
                error = null,
            )
        } catch (e: ApiException) {
            if (e.isAuthFailure) {
                net.tokens.clear()
                ui = AccountUi()
            } else {
                ui = ui.copy(busy = false, error = message(e))
            }
        }
        publish()
    }

    private fun validNickname(value: String): Boolean =
        value.matches(Regex("^[A-Za-z0-9_-]{3,24}$"))

    private fun validPassword(value: String): Boolean = value.length in 8..72

    private fun message(e: ApiException): String = context.getString(e.error.messageRes)

    private fun publish() {
        _state.value = ui
    }
}