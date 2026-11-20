package com.oldmermaidman.gomoku.ui

import com.oldmermaidman.gomoku.model.Stone

/**
 * Everything the settings screen can change. It travels back to the main screen
 * as an activity result, so the game state stays owned by the view model.
 */
data class SettingsValues(
    val playerColor: Stone,
    val forbidden: Boolean,
    val showNumbers: Boolean,
    val aiLevel: Int,
    val theme: Int,
    val soundEnabled: Boolean,
    val soundVolumeStep: Int
)
