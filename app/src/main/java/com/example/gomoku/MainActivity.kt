package com.example.gomoku

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.gomoku.databinding.ActivityMainBinding
import com.example.gomoku.ui.GomokuTheme
import com.example.gomoku.ui.SettingsDialog
import com.example.gomoku.ui.Themes
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: GomokuViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            binding.boardView.invalidate()

            binding.tvStatus.text = state.status
            binding.tvEngine.text = state.engineBadge

            binding.btnUndo.isEnabled = !state.thinking && state.game.history.isNotEmpty()
            binding.btnResign.isEnabled = !state.thinking && !state.game.gameOver
        }
    }

    private fun openSettings() {
        SettingsDialog.show(this, viewModel.currentSettings) { result ->
            viewModel.applySettings(result)
        }
    }

    private fun applyTheme(theme: GomokuTheme) {
        binding.root.setBackgroundColor(theme.background)
        binding.topBar.setBackgroundColor(theme.background)
        binding.tvTitle.setTextColor(theme.statusText)
        binding.tvStatus.setTextColor(theme.statusText)
        binding.tvEngine.setTextColor(theme.accent)

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
