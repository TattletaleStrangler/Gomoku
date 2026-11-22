package com.oldmermaidman.gomoku

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.oldmermaidman.gomoku.databinding.ActivityMainBinding
import com.oldmermaidman.gomoku.model.SoundVolume
import com.oldmermaidman.gomoku.ui.AccountActivity
import com.oldmermaidman.gomoku.ui.GomokuTheme
import com.oldmermaidman.gomoku.ui.HistoryActivity
import com.oldmermaidman.gomoku.ui.OnlineActivity
import com.oldmermaidman.gomoku.ui.SettingsActivity
import com.oldmermaidman.gomoku.ui.SoundPlayer
import com.oldmermaidman.gomoku.ui.ThemeSupport
import com.oldmermaidman.gomoku.ui.Themes

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: GomokuViewModel by viewModels()

    private val soundPlayer = SoundPlayer()

    /** History length seen on the previous state; used to detect a new stone. */
    private var lastHistorySize = -1

    /** True while the player's clock is in its last seconds. */
    private var clockLow = false

    /**
     * Online play needs an account: a signed-in player goes straight to the rooms,
     * everyone else lands on the sign-in screen first (TZ §11.4).
     */
    private fun openOnline() {
        val signedIn = (application as GomokuApp).net.tokens.hasSession()
        startActivity(
            if (signedIn) OnlineActivity.intent(this) else AccountActivity.intent(this)
        )
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) return@registerForActivityResult

        SettingsActivity.valuesFrom(data)?.let { viewModel.applySettings(it) }
        if (data.getBooleanExtra(SettingsActivity.EXTRA_STATS_RESET, false)) {
            viewModel.reloadStats()
        }
    }

    /**
     * The history screen only browses; the game is rolled back when the player
     * continues from a move, which comes back as the number of moves to keep.
     */
    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) return@registerForActivityResult

        val keep = data.getIntExtra(HistoryActivity.EXTRA_ROLLBACK_TO, -1)
        if (keep >= 0) viewModel.rollbackTo(keep)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeSupport.applySystemBarInsets(binding.root)

        binding.boardView.onCellTapped = { x, y -> viewModel.onBoardTapped(x, y) }
        binding.btnNew.setOnClickListener { viewModel.startNewGame() }
        binding.btnUndo.setOnClickListener { viewModel.undo() }
        binding.btnResign.setOnClickListener { viewModel.resign() }
        binding.btnTheme.setOnClickListener { viewModel.cycleTheme() }
        binding.btnOnline.setOnClickListener { openOnline() }
        binding.btnHistory.setOnClickListener {
            historyLauncher.launch(HistoryActivity.intent(this, viewModel.game))
        }
        binding.btnSettings.setOnClickListener {
            settingsLauncher.launch(SettingsActivity.intent(this))
        }

        viewModel.uiState.observe(this) { state ->
            val theme = Themes.byId(viewModel.currentSettings.theme)
            applyTheme(theme)

            binding.boardView.game = state.game
            binding.boardView.theme = theme
            binding.boardView.showMoveNumbers = viewModel.currentSettings.showMoveNumbers
            binding.boardView.winningLine = state.game.winningLine
            binding.boardView.invalidate()

            binding.tvStatus.text = state.status
            binding.tvDifficulty.text = state.difficultyLabel

            // A game lost on time cannot be taken back (see GomokuViewModel.undo).
            binding.btnUndo.isEnabled = !state.thinking && state.game.history.isNotEmpty() &&
                !state.game.timeLoss
            binding.btnResign.isEnabled = !state.thinking && !state.game.gameOver

            // Click for every stone that appears, the computer's included. The
            // first state after launch is skipped, so a restored game stays silent.
            val historySize = state.game.history.size
            if (lastHistorySize in 0 until historySize) playMoveSound()
            lastHistorySize = historySize
        }

        // The clock ticks on its own, so it has its own observer: a new state is
        // published only when something about the game actually changes.
        viewModel.clockUi.observe(this) { clock ->
            clockLow = clock.low
            binding.tvClock.visibility = if (clock.timed) View.VISIBLE else View.GONE
            binding.tvClock.text = if (clock.timed) formatClock(clock.remainingMs) else null
            styleClock()
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onScreenStarted()
    }

    override fun onStop() {
        // Time spent off screen is not thinking time: the clock freezes, and what is
        // left of it is stored with the game.
        viewModel.onScreenStopped()
        super.onStop()
    }

    override fun onDestroy() {
        soundPlayer.release()
        super.onDestroy()
    }

    private fun playMoveSound() {
        val settings = viewModel.currentSettings
        if (!settings.soundEnabled) return
        soundPlayer.play(SoundVolume.percentOf(settings.soundVolumeStep))
    }

    /**
     * The player's clock the way a chess clock shows it: mm:ss, rounded up, so a
     * second is still on the display while any part of it is left.
     */
    private fun formatClock(remainingMs: Long): String {
        val seconds = (remainingMs + 999) / 1000
        return getString(R.string.clock_format, seconds / 60, seconds % 60)
    }

    /** Accent normally, warning colour in the last seconds. */
    private fun styleClock() {
        val theme = Themes.byId(viewModel.currentSettings.theme)
        binding.tvClock.setTextColor(if (clockLow) theme.timeLow else theme.accent)
    }

    /**
     * Applies one of the three themes to the main screen (TZ §6.2). Called on each
     * state change, so switching the theme is immediate and needs no recreation.
     */
    private fun applyTheme(theme: GomokuTheme) {
        ThemeSupport.applyWindow(this, binding.root, theme)

        binding.topBar.setBackgroundColor(theme.background)
        binding.tvTitle.setTextColor(theme.statusText)
        binding.tvStatus.setTextColor(theme.statusText)
        binding.tvDifficulty.setTextColor(theme.accent)

        val tint = ColorStateList.valueOf(theme.statusText)
        binding.btnOnline.imageTintList = tint
        binding.btnHistory.imageTintList = tint
        binding.btnTheme.imageTintList = tint
        binding.btnSettings.imageTintList = tint

        styleButtons(theme)
    }

    private fun styleButtons(theme: GomokuTheme) {
        val buttons: List<Button> = listOf(binding.btnNew, binding.btnUndo, binding.btnResign)
        buttons.forEach { ThemeSupport.styleButton(it, theme) }
    }
}
