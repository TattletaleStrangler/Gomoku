package com.oldmermaidman.gomoku.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.model.Stone
import com.oldmermaidman.gomoku.prefs.AppSettings

/**
 * Bottom-style settings dialog (board size, color, difficulty, forbidden moves,
 * move numbering, theme). Values are returned through [onApply].
 */
class SettingsDialog private constructor() {

    data class Result(
        val boardSize: Int,
        val playerColor: Stone,
        val forbidden: Boolean,
        val showNumbers: Boolean,
        val aiLevel: Int,
        val theme: Int
    )

    companion object {
        fun show(context: Context, s: AppSettings, onApply: (Result) -> Unit) {
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_settings, null)

            val rgSize = view.findViewById<RadioGroup>(R.id.rgSize)
            val rgColor = view.findViewById<RadioGroup>(R.id.rgColor)
            val rgLevel = view.findViewById<RadioGroup>(R.id.rgLevel)
            val swForbidden = view.findViewById<SwitchCompat>(R.id.swForbidden)
            val swNumbers = view.findViewById<SwitchCompat>(R.id.swNumbers)
            val rgTheme = view.findViewById<RadioGroup>(R.id.rgTheme)

            rgSize.check(
                when (s.boardSize) {
                    13 -> R.id.rbSize13
                    19 -> R.id.rbSize19
                    else -> R.id.rbSize15
                }
            )
            rgColor.check(if (s.playerColor == Stone.WHITE) R.id.rbColorWhite else R.id.rbColorBlack)
            rgLevel.check(if (s.aiLevel == AppSettings.LEVEL_STRONG) R.id.rbLevelStrong else R.id.rbLevelNormal)
            swForbidden.isChecked = s.forbiddenMoves
            swNumbers.isChecked = s.showMoveNumbers
            rgTheme.check(
                when (s.theme) {
                    AppSettings.THEME_DARK -> R.id.rbThemeDark
                    AppSettings.THEME_WARM -> R.id.rbThemeWarm
                    else -> R.id.rbThemeLight
                }
            )

            AlertDialog.Builder(context)
                .setTitle(R.string.settings_title)
                .setView(view)
                .setPositiveButton(R.string.apply) { _, _ ->
                    onApply(
                        Result(
                            boardSize = when (rgSize.checkedRadioButtonId) {
                                R.id.rbSize13 -> 13
                                R.id.rbSize19 -> 19
                                else -> 15
                            },
                            playerColor = if (rgColor.checkedRadioButtonId == R.id.rbColorWhite) Stone.WHITE else Stone.BLACK,
                            forbidden = swForbidden.isChecked,
                            showNumbers = swNumbers.isChecked,
                            aiLevel = if (rgLevel.checkedRadioButtonId == R.id.rbLevelStrong) AppSettings.LEVEL_STRONG else AppSettings.LEVEL_NORMAL,
                            theme = when (rgTheme.checkedRadioButtonId) {
                                R.id.rbThemeDark -> AppSettings.THEME_DARK
                                R.id.rbThemeWarm -> AppSettings.THEME_WARM
                                else -> AppSettings.THEME_LIGHT
                            }
                        )
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
