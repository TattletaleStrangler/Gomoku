package com.oldmermaidman.gomoku

import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.oldmermaidman.gomoku.databinding.ActivityMainBinding
import com.oldmermaidman.gomoku.model.SoundVolume
import com.oldmermaidman.gomoku.ui.GomokuTheme
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

            binding.btnUndo.isEnabled = !state.thinking && state.game.history.isNotEmpty()
            binding.btnResign.isEnabled = !state.thinking && !state.game.gameOver

            // Click for every stone that appears, the computer's included. The
            // first state after launch is skipped, so a restored game stays silent.
            val historySize = state.game.history.size
            if (lastHistorySize in 0 until historySize) playMoveSound()
            lastHistorySize = historySize
        }
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
        binding.btnTheme.imageTintList = tint
        binding.btnSettings.imageTintList = tint

        styleButtons(theme)
    }

    private fun styleButtons(theme: GomokuTheme) {
        val buttons: List<Button> = listOf(binding.btnNew, binding.btnUndo, binding.btnResign)
        buttons.forEach { ThemeSupport.styleButton(it, theme) }
    }
}
