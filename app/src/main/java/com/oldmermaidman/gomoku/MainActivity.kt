package com.oldmermaidman.gomoku

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.oldmermaidman.gomoku.databinding.ActivityMainBinding
import com.oldmermaidman.gomoku.ui.GomokuTheme
import com.oldmermaidman.gomoku.ui.SettingsDialog
import com.oldmermaidman.gomoku.ui.Themes

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: GomokuViewModel by viewModels()

    /** True once the result dialog has been raised for the current finished game. */
    private var resultDialogShown = false

    /** Used to tell a fresh launch from a game that ended while the app ran. */
    private var firstState = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applySystemBarInsets()

        binding.boardView.onCellTapped = { x, y -> viewModel.onBoardTapped(x, y) }
        binding.btnNew.setOnClickListener { viewModel.startNewGame() }
        binding.btnUndo.setOnClickListener { viewModel.undo() }
        binding.btnResign.setOnClickListener { viewModel.resign() }
        binding.btnTheme.setOnClickListener { viewModel.cycleTheme() }
        binding.btnSettings.setOnClickListener { openSettings() }

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

            // The result dialog is raised when a game ends while the app is
            // running. A game that was already finished when the app started is
            // not announced again — its result is visible in the status line.
            if (firstState) {
                firstState = false
                resultDialogShown = state.game.gameOver
            }
            if (state.game.gameOver) {
                if (!resultDialogShown) {
                    resultDialogShown = true
                    showResultDialog(state)
                }
            } else {
                resultDialogShown = false
            }
        }
    }

    /**
     * Shown when a game is over. Besides the result it offers to take the last
     * move back: the computer can complete five unexpectedly, and the player
     * often wants to replay that move rather than start a new game.
     */
    private fun showResultDialog(state: GameUiState) {
        AlertDialog.Builder(this)
            .setTitle(state.resultTitle)
            .setMessage(state.status)
            .setPositiveButton(R.string.play_again) { _, _ -> viewModel.startNewGame() }
            .setNeutralButton(R.string.undo_move) { _, _ -> viewModel.undo() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /**
     * targetSdk 35+ enforces edge-to-edge: the window spans the whole screen and
     * the app must keep its content clear of the system bars itself. The root
     * view is padded by the merged system-bar / display-cutout insets, so the
     * board is never hidden behind the status or navigation bar.
     */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun openSettings() {
        SettingsDialog.show(this, viewModel.currentSettings) { result ->
            viewModel.applySettings(result)
        }
    }

    /**
     * Applies one of the three themes to every view (TZ §6.2). Called on each
     * state change, so switching the theme is immediate and needs no recreation.
     *
     * `statusBarColor` / `navigationBarColor` are deprecated on API 35+ (where
     * edge-to-edge ignores them) but still take effect on older devices, so they
     * are kept deliberately.
     */
    @Suppress("DEPRECATION")
    private fun applyTheme(theme: GomokuTheme) {
        binding.root.setBackgroundColor(theme.background)

        // The root is padded by the system-bar insets, so the window background
        // colours the bar areas behind it with the same theme colour.
        window.setBackgroundDrawable(theme.background.toDrawable())

        binding.topBar.setBackgroundColor(theme.background)
        binding.tvTitle.setTextColor(theme.statusText)
        binding.tvStatus.setTextColor(theme.statusText)
        binding.tvDifficulty.setTextColor(theme.accent)

        val tint = ColorStateList.valueOf(theme.statusText)
        binding.btnTheme.imageTintList = tint
        binding.btnSettings.imageTintList = tint

        styleButton(binding.btnNew, theme)
        styleButton(binding.btnUndo, theme)
        styleButton(binding.btnResign, theme)

        window.statusBarColor = theme.background
        window.navigationBarColor = theme.background
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !theme.isDark
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars = !theme.isDark
    }

    private fun styleButton(button: Button, theme: GomokuTheme) {
        val enabled = theme.buttonText
        val disabled = Color.argb(90, Color.red(enabled), Color.green(enabled), Color.blue(enabled))
        button.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(disabled, enabled)
            )
        )
        if (button is MaterialButton) {
            button.backgroundTintList = ColorStateList.valueOf(theme.panelBackground)
        } else {
            button.setBackgroundColor(theme.panelBackground)
        }
    }
}
