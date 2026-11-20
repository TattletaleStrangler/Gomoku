package com.oldmermaidman.gomoku.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/**
 * Shared theme plumbing for the two screens.
 *
 * The three themes are applied programmatically rather than through a theme
 * overlay, so the window, the buttons and the text colours of every view have to
 * be coloured by hand (TZ §6.2).
 */
object ThemeSupport {

    /**
     * targetSdk 35+ enforces edge-to-edge: the window spans the whole screen and
     * each screen must keep its content clear of the system bars itself.
     */
    fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * Colours the window itself: background behind the system bars and the
     * light/dark appearance of the bar icons.
     *
     * `statusBarColor` / `navigationBarColor` are deprecated on API 35+ (where
     * edge-to-edge ignores them) but still take effect on older devices, so they
     * are kept deliberately.
     */
    @Suppress("DEPRECATION")
    fun applyWindow(activity: AppCompatActivity, root: View, theme: GomokuTheme) {
        root.setBackgroundColor(theme.background)

        // The root is padded by the system-bar insets, so the window background
        // colours the bar areas behind it with the same theme colour.
        activity.window.setBackgroundDrawable(theme.background.toDrawable())
        activity.window.statusBarColor = theme.background
        activity.window.navigationBarColor = theme.background

        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = !theme.isDark
        controller.isAppearanceLightNavigationBars = !theme.isDark
    }

    /**
     * Applies [theme] to every view below [root]: text colour for labels, accent
     * for sliders and switches, panel colour for buttons.
     *
     * The order of the branches matters: `RadioButton` and `SwitchCompat` both
     * extend `Button`, so they must be matched before it — otherwise a radio
     * button would be painted as a panel-coloured button.
     */
    fun tintHierarchy(root: View, theme: GomokuTheme) {
        when (root) {
            is RadioButton -> root.setTextColor(theme.statusText)
            is SwitchCompat -> {
                root.setTextColor(theme.statusText)
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                val colors = intArrayOf(theme.accent, theme.whiteStoneBorder)
                root.thumbTintList = ColorStateList(states, colors)
                root.trackTintList = ColorStateList(states, colors)
            }
            is SeekBar -> {
                root.progressTintList = ColorStateList.valueOf(theme.accent)
                root.thumbTintList = ColorStateList.valueOf(theme.accent)
            }
            is Button -> styleButton(root, theme)
            is TextView -> root.setTextColor(theme.statusText)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) tintHierarchy(root.getChildAt(i), theme)
        }
    }

    /** Buttons keep the panel colour of the theme and dim their label when disabled. */
    fun styleButton(button: Button, theme: GomokuTheme) {
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
