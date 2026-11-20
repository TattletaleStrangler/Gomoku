package com.oldmermaidman.gomoku.ui

import androidx.core.graphics.toColorInt
import androidx.annotation.ColorInt

/**
 * The three user-selectable color themes (TZ §6.2). Colors are applied
 * programmatically so switching is immediate and needs no Activity recreation.
 */
data class GomokuTheme(
    val id: Int,
    @param:ColorInt val background: Int,
    @param:ColorInt val boardLine: Int,
    @param:ColorInt val blackStone: Int,
    @param:ColorInt val whiteStone: Int,
    @param:ColorInt val whiteStoneBorder: Int,
    @param:ColorInt val accent: Int,
    @param:ColorInt val statusText: Int,
    @param:ColorInt val panelBackground: Int,
    @param:ColorInt val buttonText: Int,
    @param:ColorInt val numberOnBlackStone: Int,
    @param:ColorInt val numberOnWhiteStone: Int,
    @param:ColorInt val lastMoveRing: Int,
    /** Used for the player's clock in its last seconds. */
    @param:ColorInt val timeLow: Int,
    val isDark: Boolean
)

object Themes {

    val LIGHT = GomokuTheme(
        id = 0,
        background = "#FAFAFA".toColorInt(),
        boardLine = "#B0B0B0".toColorInt(),
        blackStone = "#1A1A1A".toColorInt(),
        whiteStone = "#FFFFFF".toColorInt(),
        whiteStoneBorder = "#9A9A9A".toColorInt(),
        accent = "#4A90D9".toColorInt(),
        statusText = "#1A1A1A".toColorInt(),
        panelBackground = "#FFFFFF".toColorInt(),
        buttonText = "#1A1A1A".toColorInt(),
        numberOnBlackStone = "#FFFFFF".toColorInt(),
        numberOnWhiteStone = "#1A1A1A".toColorInt(),
        lastMoveRing = "#4A90D9".toColorInt(),
        timeLow = "#D32F2F".toColorInt(),
        isDark = false
    )

    val DARK = GomokuTheme(
        id = 1,
        background = "#1C1C1E".toColorInt(),
        boardLine = "#3A3A3C".toColorInt(),
        blackStone = "#E5E5E5".toColorInt(),
        whiteStone = "#2C2C2E".toColorInt(),
        whiteStoneBorder = "#8E8E93".toColorInt(),
        accent = "#5AC8FA".toColorInt(),
        statusText = "#FFFFFF".toColorInt(),
        panelBackground = "#2C2C2E".toColorInt(),
        buttonText = "#FFFFFF".toColorInt(),
        numberOnBlackStone = "#1C1C1E".toColorInt(),
        numberOnWhiteStone = "#E5E5E5".toColorInt(),
        lastMoveRing = "#5AC8FA".toColorInt(),
        timeLow = "#FF6B6B".toColorInt(),
        isDark = true
    )

    val WARM = GomokuTheme(
        id = 2,
        background = "#F5F0E8".toColorInt(),
        boardLine = "#C8B89A".toColorInt(),
        blackStone = "#2B2118".toColorInt(),
        whiteStone = "#FDFBF7".toColorInt(),
        whiteStoneBorder = "#C8B89A".toColorInt(),
        accent = "#C0693E".toColorInt(),
        statusText = "#2B2118".toColorInt(),
        panelBackground = "#FDFBF7".toColorInt(),
        buttonText = "#2B2118".toColorInt(),
        numberOnBlackStone = "#FDFBF7".toColorInt(),
        numberOnWhiteStone = "#2B2118".toColorInt(),
        lastMoveRing = "#C0693E".toColorInt(),
        timeLow = "#C0392B".toColorInt(),
        isDark = false
    )

    val ALL = listOf(LIGHT, DARK, WARM)

    fun byId(id: Int): GomokuTheme = ALL.firstOrNull { it.id == id } ?: LIGHT
}
