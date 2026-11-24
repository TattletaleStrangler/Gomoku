package com.oldmermaidman.gomoku.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.databinding.ActivityOnlineBinding
import com.oldmermaidman.gomoku.databinding.ItemLobbyGameBinding
import com.oldmermaidman.gomoku.online.OnlineUi
import com.oldmermaidman.gomoku.online.OnlineViewModel
import com.oldmermaidman.gomoku.prefs.AppSettings

/**
 * The room screen (TZ §11.4): create a room with a rule and a time control, join one by
 * code, wait for the opponent while the code is on screen, or return to a game that is
 * still running.
 *
 * The waiting room is polled rather than socket-driven, so this screen does not have to
 * hand a live connection to the game screen a moment later.
 */
class OnlineActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, OnlineActivity::class.java)
    }

    private lateinit var binding: ActivityOnlineBinding
    private val viewModel: OnlineViewModel by viewModels()
    private var theme: GomokuTheme = Themes.LIGHT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnlineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyTheme()

        // The form comes back the way it was left (FEATURES.md, favourite settings).
        viewModel.savedQuickSettings()?.let { saved ->
            if (saved.rule == "RENJU") binding.rbRenju.isChecked = true else binding.rbFreestyle.isChecked = true
            when (saved.timeControl) {
                "CLASSIC" -> binding.rbClassic.isChecked = true
                "RAPID" -> binding.rbRapid.isChecked = true
                "BLITZ" -> binding.rbBlitz.isChecked = true
                else -> binding.rbUntimed.isChecked = true
            }
            when (saved.color) {
                "WHITE" -> binding.rbColorWhite.isChecked = true
                "ANY" -> binding.rbColorAny.isChecked = true
                else -> binding.rbColorBlack.isChecked = true
            }
        }

        binding.btnOnlineBack.setOnClickListener { finish() }
        binding.btnOnlineAccount.setOnClickListener {
            startActivity(AccountActivity.intent(this))
        }
        binding.btnCreate.setOnClickListener {
            viewModel.createRoom(selectedRule(), selectedTimeControl(), selectedColor())
        }
        binding.btnQuick.setOnClickListener {
            viewModel.quickMatch(selectedRule(), selectedTimeControl(), quickColor())
        }
        binding.btnRefreshLobby.setOnClickListener { viewModel.refreshLobby() }
        binding.btnJoin.setOnClickListener {
            viewModel.joinRoom(binding.etCode.text.toString(), selectedJoinColor())
        }
        binding.btnCloseRoom.setOnClickListener { viewModel.leaveWaitingRoom() }
        binding.btnResume.setOnClickListener { viewModel.openResume() }

        viewModel.state.observe(this) { render(it) }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onScreenStarted()
    }

    private fun selectedRule(): String = if (binding.rbRenju.isChecked) "RENJU" else "FREESTYLE"

    private fun selectedTimeControl(): String = when (binding.rgTime.checkedRadioButtonId) {
        R.id.rbClassic -> "CLASSIC"
        R.id.rbRapid -> "RAPID"
        R.id.rbBlitz -> "BLITZ"
        else -> "UNTIMED"
    }

    /** The creator's colour: black by default, or ANY to let the joiner choose. */
    private fun selectedColor(): String = when (binding.rgColor.checkedRadioButtonId) {
        R.id.rbColorWhite -> "WHITE"
        R.id.rbColorAny -> "ANY"
        else -> "BLACK"
    }

    /** The joiner's wish; null means "no preference" and is omitted from the request. */
    private fun selectedJoinColor(): String? = when (binding.rgJoinColor.checkedRadioButtonId) {
        R.id.rbJoinBlack -> "BLACK"
        R.id.rbJoinWhite -> "WHITE"
        else -> null
    }

    /** Quick play: ANY means "no preference", which the server keeps open for the opponent. */
    private fun quickColor(): String? = selectedColor().takeUnless { it == "ANY" }

    /**
     * Fills the open-games list. Rows are plain views rather than a RecyclerView: the
     * server caps the list at fifty, and one player can only ever hold one room.
     */
    private fun renderLobby(ui: OnlineUi) {
        binding.lobbyList.removeAllViews()

        val error = ui.lobbyError
        val message = when {
            ui.lobbyLoading && ui.lobby.isEmpty() -> getString(R.string.online_lobby_loading)
            error != null -> error
            ui.lobby.isEmpty() -> getString(R.string.online_lobby_empty)
            else -> null
        }
        binding.tvLobbyEmpty.text = message
        binding.tvLobbyEmpty.visibility = if (message == null) View.GONE else View.VISIBLE
        binding.tvLobbyEmpty.setTextColor(if (error != null) theme.timeLow else theme.statusText)

        for (row in ui.lobby) {
            val item = ItemLobbyGameBinding.inflate(layoutInflater, binding.lobbyList, false)
            item.tvLobbyRow.text = listOfNotNull(
                row.creatorNickname.ifEmpty { null },
                row.creatorRating?.toString(),
                getString(ruleName(row.rule)),
                getString(timeName(row.timeControl)),
                getString(colorName(row.creatorColor)),
            ).joinToString(" · ")
            item.tvLobbyRow.setTextColor(theme.statusText)
            item.root.setOnClickListener { viewModel.joinRoom(row.code) }
            binding.lobbyList.addView(item.root)
        }
    }

    private fun ruleName(rule: String): Int = when (rule) {
        "RENJU" -> R.string.online_rule_renju
        else -> R.string.online_rule_freestyle
    }

    private fun timeName(timeControl: String): Int = when (timeControl) {
        "CLASSIC" -> R.string.online_time_classic
        "RAPID" -> R.string.online_time_rapid
        "BLITZ" -> R.string.online_time_blitz
        else -> R.string.online_time_untimed
    }

    private fun colorName(color: String): Int = when (color) {
        "BLACK" -> R.string.play_black
        "WHITE" -> R.string.play_white
        else -> R.string.online_color_any
    }

    private fun render(ui: OnlineUi) {
        binding.tvOnlineNickname.text = ui.nickname

        val waiting = ui.waiting
        binding.groupWaiting.visibility = if (waiting != null) View.VISIBLE else View.GONE
        binding.groupForm.visibility = if (waiting == null) View.VISIBLE else View.GONE

        if (waiting != null) binding.tvWaitingCode.text = waiting.code

        binding.btnResume.visibility = if (ui.resume != null) View.VISIBLE else View.GONE

        val busy = ui.busy
        binding.btnCreate.isEnabled = !busy && ui.signedIn
        binding.btnQuick.isEnabled = !busy && ui.signedIn
        binding.btnRefreshLobby.isEnabled = !ui.lobbyLoading
        binding.btnJoin.isEnabled = !busy && ui.signedIn
        binding.btnCloseRoom.isEnabled = !busy
        renderLobby(ui)

        // The error line doubles as the "sign in first" hint; the account icon above is
        // the way there.
        val error = ui.error ?: if (ui.signedIn) null else getString(R.string.online_signed_out)
        binding.tvOnlineError.text = error
        binding.tvOnlineError.visibility = if (error == null) View.GONE else View.VISIBLE

        ui.openGame?.let { game ->
            startActivity(OnlineGameActivity.intent(this, game.gameId))
            viewModel.consumeOpenGame()
        }
    }

    private fun applyTheme() {
        theme = Themes.byId(AppSettings(this).theme)
        ThemeSupport.applySystemBarInsets(binding.root)
        ThemeSupport.applyWindow(this, binding.root, theme)
        ThemeSupport.tintHierarchy(binding.root, theme)

        binding.onlineTopBar.setBackgroundColor(theme.background)
        binding.tvOnlineTitle.setTextColor(theme.statusText)
        binding.tvOnlineNickname.setTextColor(theme.accent)
        binding.tvOnlineError.setTextColor(theme.timeLow)
        binding.tvWaitingCode.setTextColor(theme.accent)

        val tint = ColorStateList.valueOf(theme.statusText)
        binding.btnOnlineBack.imageTintList = tint
        binding.btnOnlineAccount.imageTintList = tint
    }
}