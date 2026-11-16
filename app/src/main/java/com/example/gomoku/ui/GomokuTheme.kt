package com.example.gomoku.ui

import android.graphics.Color
import androidx.annotation.ColorInt

/**
 * The three user-selectable color themes (TZ §6.2). Colors are applied
 * programmatically so switching is immediate and needs no Activity recreation.
 */
data class GomokuTheme(
    val id: Int,
    @ColorInt val background: Int,
    @ColorInt val boardLine: Int,
    @ColorInt val blackStone: Int,
    @ColorInt val whiteStone: Int,
    @ColorInt val whiteStoneBorder: Int,
    @ColorInt val accent: Int,
    @ColorInt val statusText: Int,
    @ColorInt val panelBackground: Int,
    @ColorInt val buttonText: Int,
    @ColorInt val numberOnBlackStone: Int,
    @ColorInt val numberOnWhiteStone: Int,
    @ColorInt val lastMoveRing: Int,
    val isDark: Boolean
)

object Themes {

    val LIGHT = GomokuTheme(
        id = 0,
        background = Color.parseColor("#FAFAFA"),
        boardLine = Color.parseColor("#B0B0B0"),
        blackStone = Color.parseColor("#1A1A1A"),
        whiteStone = Color.parseColor("#FFFFFF"),
        whiteStoneBorder = Color.parseColor("#9A9A9A"),
        accent = Color.parseColor("#4A90D9"),
        statusText = Color.parseColor("#1A1A1A"),
        panelBackground = Color.parseColor("#FFFFFF"),
        buttonText = Color.parseColor("#1A1A1A"),
        numberOnBlackStone = Color.parseColor("#FFFFFF"),
        numberOnWhiteStone = Color.parseColor("#1A1A1A"),
        lastMoveRing = Color.parseColor("#4A90D9"),
        isDark = false
    )

    val DARK = GomokuTheme(
        id = 1,
        background = Color.parseColor("#1C1C1E"),
        boardLine = Color.parseColor("#3A3A3C"),
        blackStone = Color.parseColor("#E5E5E5"),
        whiteStone = Color.parseColor("#2C2C2E"),
        whiteStoneBorder = Color.parseColor("#8E8E93"),
        accent = Color.parseColor("#5AC8FA"),
        statusText = Color.parseColor("#FFFFFF"),
        panelBackground = Color.parseColor("#2C2C2E"),
        buttonText = Color.parseColor("#FFFFFF"),
        numberOnBlackStone = Color.parseColor("#1C1C1E"),
        numberOnWhiteStone = Color.parseColor("#E5E5E5"),
        lastMoveRing = Color.parseColor("#5AC8FA"),
        isDark = true
    )

    val WARM = GomokuTheme(
        id = 2,
        background = Color.parseColor("#F5F0E8"),
        boardLine = Color.parseColor("#C8B89A"),
        blackStone = Color.parseColor("#2B2118"),
        whiteStone = Color.parseColor("#FDFBF7"),
        whiteStoneBorder = Color.parseColor("#C8B89A"),
        accent = Color.parseColor("#C0693E"),
        statusText = Color.parseColor("#2B2118"),
        panelBackground = Color.parseColor("#FDFBF7"),
        buttonText = Color.parseColor("#2B2118"),
        numberOnBlackStone = Color.parseColor("#FDFBF7"),
        numberOnWhiteStone = Color.parseColor("#2B2118"),
        lastMoveRing = Color.parseColor("#C0693E"),
        isDark = false
    )

    val ALL = listOf(LIGHT, DARK, WARM)

    fun byId(id: Int): GomokuTheme = ALL.firstOrNull { it.id == id } ?: LIGHT
}
