package com.oldmermaidman.gomoku.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.model.SoundVolume
import com.oldmermaidman.gomoku.model.Stone
import com.oldmermaidman.gomoku.prefs.AppSettings

/**
 * Settings dialog: colour, difficulty (which also selects the engine), forbidden
 * moves, move numbering, sound, theme and the statistics with a reset button.
 * Values are returned through [onApply].
 *
 * The board is always 15×15, so there is no size option.
 *
 * @param statsProvider formatted statistics of a level, e.g. for
 *   [AppSettings.LEVEL_NORMAL]; called again after the reset so the labels refresh
 * @param onResetStats clears the statistics of every level
 */
class SettingsDialog private constructor() {

    data class Result(
        val playerColor: Stone,
        val forbidden: Boolean,
        val showNumbers: Boolean,
        val aiLevel: Int,
        val theme: Int,
        val soundEnabled: Boolean,
        val soundVolumeStep: Int
    )

    companion object {
        fun show(
            context: Context,
            s: AppSettings,
            statsProvider: (Int) -> String,
            onResetStats: () -> Unit,
            onApply: (Result) -> Unit
        ) {
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_settings, null)

            val rgColor = view.findViewById<RadioGroup>(R.id.rgColor)
            val rgLevel = view.findViewById<RadioGroup>(R.id.rgLevel)
            val swForbidden = view.findViewById<SwitchCompat>(R.id.swForbidden)
            val swNumbers = view.findViewById<SwitchCompat>(R.id.swNumbers)
            val rgTheme = view.findViewById<RadioGroup>(R.id.rgTheme)
            val swSound = view.findViewById<SwitchCompat>(R.id.swSound)
            val sbVolume = view.findViewById<SeekBar>(R.id.sbVolume)
            val tvVolume = view.findViewById<TextView>(R.id.tvVolumeValue)
            val tvStatsNormal = view.findViewById<TextView>(R.id.tvStatsNormal)
            val tvStatsHard = view.findViewById<TextView>(R.id.tvStatsHard)
            val btnResetStats = view.findViewById<Button>(R.id.btnResetStats)

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

            // Volume is chosen in five steps of 20 %; the slider position is the
            // step, so the maximum comes from SoundVolume rather than the layout.
            sbVolume.max = SoundVolume.STEPS - 1
            sbVolume.progress = s.soundVolumeStep
            swSound.isChecked = s.soundEnabled
            sbVolume.isEnabled = s.soundEnabled
            showVolume(tvVolume, s.soundVolumeStep)

            swSound.setOnCheckedChangeListener { _, checked -> sbVolume.isEnabled = checked }
            sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    showVolume(tvVolume, progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })

            fun refreshStats() {
                tvStatsNormal.text = statsProvider(AppSettings.LEVEL_NORMAL)
                tvStatsHard.text = statsProvider(AppSettings.LEVEL_STRONG)
            }
            refreshStats()

            btnResetStats.setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle(R.string.reset_stats_title)
                    .setMessage(R.string.reset_stats_message)
                    .setPositiveButton(R.string.reset) { _, _ ->
                        onResetStats()
                        refreshStats()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            AlertDialog.Builder(context)
                .setTitle(R.string.settings_title)
                .setView(view)
                .setPositiveButton(R.string.apply) { _, _ ->
                    onApply(
                        Result(
                            playerColor = if (rgColor.checkedRadioButtonId == R.id.rbColorWhite) Stone.WHITE else Stone.BLACK,
                            forbidden = swForbidden.isChecked,
                            showNumbers = swNumbers.isChecked,
                            aiLevel = if (rgLevel.checkedRadioButtonId == R.id.rbLevelStrong) AppSettings.LEVEL_STRONG else AppSettings.LEVEL_NORMAL,
                            theme = when (rgTheme.checkedRadioButtonId) {
                                R.id.rbThemeDark -> AppSettings.THEME_DARK
                                R.id.rbThemeWarm -> AppSettings.THEME_WARM
                                else -> AppSettings.THEME_LIGHT
                            },
                            soundEnabled = swSound.isChecked,
                            soundVolumeStep = sbVolume.progress
                        )
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun showVolume(label: TextView, step: Int) {
            label.text = label.context.getString(
                R.string.volume_percent,
                SoundVolume.percentOf(step)
            )
        }
    }
}
