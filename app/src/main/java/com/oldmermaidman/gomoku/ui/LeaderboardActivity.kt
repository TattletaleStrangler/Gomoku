package com.oldmermaidman.gomoku.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.databinding.ActivityLeaderboardBinding
import com.oldmermaidman.gomoku.databinding.ItemLobbyGameBinding
import com.oldmermaidman.gomoku.net.dto.LeaderboardRowDto
import com.oldmermaidman.gomoku.online.LeaderboardUi
import com.oldmermaidman.gomoku.online.LeaderboardViewModel
import com.oldmermaidman.gomoku.prefs.AppSettings

/**
 * The top players by Elo (FEATURES.md, B2). The player's own row is accented, so it is
 * easy to find among the others.
 */
class LeaderboardActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, LeaderboardActivity::class.java)
    }

    private lateinit var binding: ActivityLeaderboardBinding
    private val viewModel: LeaderboardViewModel by viewModels()
    private var theme: GomokuTheme = Themes.LIGHT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLeaderboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyTheme()

        binding.btnLeadersBack.setOnClickListener { finish() }
        viewModel.state.observe(this) { render(it) }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
    }

    private fun render(ui: LeaderboardUi) {
        binding.leadersList.removeAllViews()

        val error = ui.error
        val message = when {
            ui.loading && ui.rows.isEmpty() -> getString(R.string.online_lobby_loading)
            error != null -> error
            ui.rows.isEmpty() -> getString(R.string.online_leaders_empty)
            else -> null
        }
        binding.tvLeadersEmpty.text = message
        binding.tvLeadersEmpty.visibility = if (message == null) View.GONE else View.VISIBLE
        binding.tvLeadersEmpty.setTextColor(if (error != null) theme.timeLow else theme.statusText)

        for (row in ui.rows) {
            val item = ItemLobbyGameBinding.inflate(layoutInflater, binding.leadersList, false)
            item.tvLobbyRow.text = rowText(row)
            val mine = ui.you.isNotEmpty() && row.nickname == ui.you
            item.tvLobbyRow.setTextColor(if (mine) theme.accent else theme.statusText)
            binding.leadersList.addView(item.root)
        }
    }

    /** "#1 ann · 1284 · W/L/D: 12/3/1". */
    private fun rowText(row: LeaderboardRowDto): String = listOf(
        getString(R.string.leaderboard_rank, row.rank, row.nickname),
        row.rating.toString(),
        getString(R.string.leaderboard_record, row.wins, row.losses, row.draws),
    ).joinToString(" · ")

    private fun applyTheme() {
        theme = Themes.byId(AppSettings(this).theme)
        ThemeSupport.applySystemBarInsets(binding.root)
        ThemeSupport.applyWindow(this, binding.root, theme)
        ThemeSupport.tintHierarchy(binding.root, theme)

        binding.leadersTopBar.setBackgroundColor(theme.background)
        binding.tvLeadersTitle.setTextColor(theme.statusText)
        binding.tvLeadersEmpty.setTextColor(theme.statusText)
        binding.btnLeadersBack.imageTintList = ColorStateList.valueOf(theme.statusText)
    }
}