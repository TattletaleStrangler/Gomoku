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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnlineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyTheme()

        binding.btnOnlineBack.setOnClickListener { finish() }
        binding.btnOnlineAccount.setOnClickListener {
            startActivity(AccountActivity.intent(this))
        }
        binding.btnCreate.setOnClickListener {
            viewModel.createRoom(selectedRule(), selectedTimeControl())
        }
        binding.btnJoin.setOnClickListener {
            viewModel.joinRoom(binding.etCode.text.toString())
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

    private fun render(ui: OnlineUi) {
        binding.tvOnlineNickname.text = ui.nickname

        val waiting = ui.waiting
        binding.groupWaiting.visibility = if (waiting != null) View.VISIBLE else View.GONE
        binding.groupForm.visibility = if (waiting == null) View.VISIBLE else View.GONE

        if (waiting != null) binding.tvWaitingCode.text = waiting.code

        binding.btnResume.visibility = if (ui.resume != null) View.VISIBLE else View.GONE

        val busy = ui.busy
        binding.btnCreate.isEnabled = !busy && ui.signedIn
        binding.btnJoin.isEnabled = !busy && ui.signedIn
        binding.btnCloseRoom.isEnabled = !busy

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
        val theme = Themes.byId(AppSettings(this).theme)
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