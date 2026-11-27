package com.oldmermaidman.gomoku.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.oldmermaidman.gomoku.BuildConfig
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.crash.CrashStore
import com.oldmermaidman.gomoku.model.ScoreBoard
import com.oldmermaidman.gomoku.model.SoundVolume
import com.oldmermaidman.gomoku.model.Stone
import com.oldmermaidman.gomoku.model.TimeControl
import com.oldmermaidman.gomoku.prefs.AppSettings

/**
 * Settings as a full screen rather than a modal dialog: a dialog interrupts the
 * game and cramps its content, while a screen has room for the whole list —
 * including the statistics table.
 *
 * The chosen values travel back to [com.oldmermaidman.gomoku.MainActivity] as an
 * activity result, so the game state itself stays owned by the view model.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PLAYER_COLOR = "player_color"
        private const val EXTRA_FORBIDDEN = "forbidden"
        private const val EXTRA_SHOW_NUMBERS = "show_numbers"
        private const val EXTRA_AI_LEVEL = "ai_level"
        private const val EXTRA_THEME = "theme"
        private const val EXTRA_SOUND_ENABLED = "sound_enabled"
        private const val EXTRA_SOUND_VOLUME = "sound_volume"
        private const val EXTRA_GAME_MODE = "game_mode"

        /** Set when the statistics were cleared on this screen. */
        const val EXTRA_STATS_RESET = "stats_reset"

        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)

        /** Reads the chosen values back; null when the intent carries none. */
        fun valuesFrom(data: Intent): SettingsValues? {
            if (!data.hasExtra(EXTRA_AI_LEVEL)) return null
            return SettingsValues(
                playerColor = if (data.getStringExtra(EXTRA_PLAYER_COLOR) == Stone.WHITE.name) {
                    Stone.WHITE
                } else {
                    Stone.BLACK
                },
                forbidden = data.getBooleanExtra(EXTRA_FORBIDDEN, false),
                showNumbers = data.getBooleanExtra(EXTRA_SHOW_NUMBERS, true),
                aiLevel = data.getIntExtra(EXTRA_AI_LEVEL, AppSettings.LEVEL_NORMAL),
                theme = data.getIntExtra(EXTRA_THEME, AppSettings.THEME_LIGHT),
                soundEnabled = data.getBooleanExtra(EXTRA_SOUND_ENABLED, true),
                soundVolumeStep = data.getIntExtra(EXTRA_SOUND_VOLUME, SoundVolume.DEFAULT_STEP),
                gameMode = data.getIntExtra(EXTRA_GAME_MODE, TimeControl.NONE.ordinal)
            )
        }
    }

    private lateinit var settings: AppSettings

    /** True when the statistics were cleared here and must be reloaded by the caller. */
    private var statsReset = false

    /** Journal button; its label carries the number of saved reports. */
    private lateinit var crashLogButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)
        val root: View = findViewById(R.id.settingsRoot)
        ThemeSupport.applySystemBarInsets(root)

        val rgColor = findViewById<RadioGroup>(R.id.rgColor)
        val rgLevel = findViewById<RadioGroup>(R.id.rgLevel)
        val rgTheme = findViewById<RadioGroup>(R.id.rgTheme)
        val rgMode = findViewById<RadioGroup>(R.id.rgMode)
        val rbModeNone = findViewById<RadioButton>(R.id.rbModeNone)
        val rbModeClassic = findViewById<RadioButton>(R.id.rbModeClassic)
        val rbModeRapid = findViewById<RadioButton>(R.id.rbModeRapid)
        val rbModeBlitz = findViewById<RadioButton>(R.id.rbModeBlitz)
        val swForbidden = findViewById<SwitchCompat>(R.id.swForbidden)
        val swNumbers = findViewById<SwitchCompat>(R.id.swNumbers)
        val swSound = findViewById<SwitchCompat>(R.id.swSound)
        val sbVolume = findViewById<SeekBar>(R.id.sbVolume)
        val tvVolume = findViewById<TextView>(R.id.tvVolumeValue)

        val tvNormalWins = findViewById<TextView>(R.id.tvNormalWins)
        val tvNormalLosses = findViewById<TextView>(R.id.tvNormalLosses)
        val tvNormalDraws = findViewById<TextView>(R.id.tvNormalDraws)
        val tvHardWins = findViewById<TextView>(R.id.tvHardWins)
        val tvHardLosses = findViewById<TextView>(R.id.tvHardLosses)
        val tvHardDraws = findViewById<TextView>(R.id.tvHardDraws)

        // ---- local helpers -------------------------------------------------

        fun showVolume(step: Int) {
            tvVolume.text = getString(R.string.volume_percent, SoundVolume.percentOf(step))
        }

        /** Name of a language in its own language, or the "follow the device" option. */
        fun languageLabel(tag: String): String = if (tag == AppSettings.LANGUAGE_SYSTEM) {
            getString(R.string.language_system)
        } else {
            getString(Locales.labelRes(tag))
        }

        fun refreshStats() {
            val board: ScoreBoard = settings.loadScores()
            val normal = board.scoreOf(AppSettings.LEVEL_NORMAL)
            val hard = board.scoreOf(AppSettings.LEVEL_STRONG)
            tvNormalWins.text = getString(R.string.stats_value, normal.wins)
            tvNormalLosses.text = getString(R.string.stats_value, normal.losses)
            tvNormalDraws.text = getString(R.string.stats_value, normal.draws)
            tvHardWins.text = getString(R.string.stats_value, hard.wins)
            tvHardLosses.text = getString(R.string.stats_value, hard.losses)
            tvHardDraws.text = getString(R.string.stats_value, hard.draws)
        }

        fun selectedTheme(): GomokuTheme = Themes.byId(
            when (rgTheme.checkedRadioButtonId) {
                R.id.rbThemeDark -> AppSettings.THEME_DARK
                R.id.rbThemeWarm -> AppSettings.THEME_WARM
                else -> AppSettings.THEME_LIGHT
            }
        )

        fun controlName(control: TimeControl): Int = when (control) {
            TimeControl.CLASSIC -> R.string.mode_classic
            TimeControl.RAPID -> R.string.mode_rapid
            TimeControl.BLITZ -> R.string.mode_blitz
            TimeControl.NONE -> R.string.mode_none
        }

        /** «Классика — 30 мин + 30 с»: the numbers come from the control itself. */
        fun modeLabel(control: TimeControl): String = if (!control.timed) {
            getString(R.string.mode_none)
        } else {
            getString(
                R.string.mode_timing,
                getString(controlName(control)),
                (control.mainTimeMs / TimeControl.MINUTE_MS).toInt(),
                (control.incrementMs / TimeControl.SECOND_MS).toInt()
            )
        }

        fun checkedMode(): Int = when (rgMode.checkedRadioButtonId) {
            R.id.rbModeClassic -> TimeControl.CLASSIC.ordinal
            R.id.rbModeRapid -> TimeControl.RAPID.ordinal
            R.id.rbModeBlitz -> TimeControl.BLITZ.ordinal
            else -> TimeControl.NONE.ordinal
        }

        // The theme is previewed as soon as it is picked, but only saved on Apply.
        fun previewTheme() {
            val theme = selectedTheme()
            ThemeSupport.applyWindow(this, root, theme)
            ThemeSupport.tintHierarchy(root, theme)
        }

        fun applyAndFinish() {
            val data = Intent().apply {
                putExtra(
                    EXTRA_PLAYER_COLOR,
                    if (rgColor.checkedRadioButtonId == R.id.rbColorWhite) Stone.WHITE.name
                    else Stone.BLACK.name
                )
                putExtra(EXTRA_FORBIDDEN, swForbidden.isChecked)
                putExtra(EXTRA_SHOW_NUMBERS, swNumbers.isChecked)
                putExtra(
                    EXTRA_AI_LEVEL,
                    if (rgLevel.checkedRadioButtonId == R.id.rbLevelStrong) AppSettings.LEVEL_STRONG
                    else AppSettings.LEVEL_NORMAL
                )
                putExtra(
                    EXTRA_THEME,
                    when (rgTheme.checkedRadioButtonId) {
                        R.id.rbThemeDark -> AppSettings.THEME_DARK
                        R.id.rbThemeWarm -> AppSettings.THEME_WARM
                        else -> AppSettings.THEME_LIGHT
                    }
                )
                putExtra(EXTRA_SOUND_ENABLED, swSound.isChecked)
                putExtra(EXTRA_SOUND_VOLUME, sbVolume.progress)
                putExtra(EXTRA_GAME_MODE, checkedMode())
                putExtra(EXTRA_STATS_RESET, statsReset)
            }
            setResult(Activity.RESULT_OK, data)
            finish()
        }

        // ---- initial values ------------------------------------------------

        rgColor.check(if (settings.playerColor == Stone.WHITE) R.id.rbColorWhite else R.id.rbColorBlack)
        rgLevel.check(
            if (settings.aiLevel == AppSettings.LEVEL_STRONG) R.id.rbLevelStrong
            else R.id.rbLevelNormal
        )
        rgTheme.check(
            when (settings.theme) {
                AppSettings.THEME_DARK -> R.id.rbThemeDark
                AppSettings.THEME_WARM -> R.id.rbThemeWarm
                else -> R.id.rbThemeLight
            }
        )
        rbModeNone.text = modeLabel(TimeControl.NONE)
        rbModeClassic.text = modeLabel(TimeControl.CLASSIC)
        rbModeRapid.text = modeLabel(TimeControl.RAPID)
        rbModeBlitz.text = modeLabel(TimeControl.BLITZ)
        rgMode.check(
            when (TimeControl.byId(settings.gameMode)) {
                TimeControl.CLASSIC -> R.id.rbModeClassic
                TimeControl.RAPID -> R.id.rbModeRapid
                TimeControl.BLITZ -> R.id.rbModeBlitz
                TimeControl.NONE -> R.id.rbModeNone
            }
        )
        swForbidden.isChecked = settings.forbiddenMoves
        swNumbers.isChecked = settings.showMoveNumbers

        // Volume is chosen in five steps of 20 %; the slider position is the step,
        // so the maximum comes from SoundVolume rather than from the layout.
        sbVolume.max = SoundVolume.STEPS - 1
        sbVolume.progress = settings.soundVolumeStep
        swSound.isChecked = settings.soundEnabled
        sbVolume.isEnabled = settings.soundEnabled
        showVolume(sbVolume.progress)

        refreshStats()

        // ---- language ------------------------------------------------------

        // Every shipped language in its own language, plus handing the choice back to
        // the device. Picking one applies it immediately: this screen is recreated in
        // the new language, and the game in the background survives the recreation
        // because its state lives in a view model.
        val btnLanguage = findViewById<Button>(R.id.btnLanguage)
        btnLanguage.text = languageLabel(settings.language)
        btnLanguage.setOnClickListener {
            val tags = listOf(AppSettings.LANGUAGE_SYSTEM) + Locales.SUPPORTED
            AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(
                    tags.map { languageLabel(it) }.toTypedArray(),
                    tags.indexOf(settings.language).coerceAtLeast(0)
                ) { dialog, which ->
                    dialog.dismiss()
                    val tag = tags[which]
                    if (tag != settings.language) {
                        settings.language = tag
                        Locales.apply(tag)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // ---- listeners -----------------------------------------------------

        swSound.setOnCheckedChangeListener { _, checked -> sbVolume.isEnabled = checked }
        sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) =
                showVolume(progress)

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        rgTheme.setOnCheckedChangeListener { _, _ -> previewTheme() }

        findViewById<Button>(R.id.btnResetStats).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.reset_stats_title)
                .setMessage(R.string.reset_stats_message)
                .setPositiveButton(R.string.reset) { _, _ ->
                    settings.saveScores(ScoreBoard(AppSettings.LEVEL_COUNT))
                    statsReset = true
                    refreshStats()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        crashLogButton = findViewById(R.id.btnCrashLog)
        crashLogButton.setOnClickListener { startActivity(CrashLogActivity.intent(this)) }

        // The policy lives on the game server next to the data it describes.
        findViewById<Button>(R.id.btnPrivacy).setOnClickListener {
            val url = BuildConfig.API_BASE_URL.trimEnd('/') + "/legal/privacy"
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnApply).setOnClickListener { applyAndFinish() }

        previewTheme()
    }

    override fun onResume() {
        super.onResume()
        // The journal can be cleared on its own screen.
        val count = CrashStore.inFilesDir(filesDir).count()
        crashLogButton.text = if (count == 0) {
            getString(R.string.crash_log)
        } else {
            getString(R.string.crash_log_with_count, getString(R.string.crash_log), count)
        }
    }
}
