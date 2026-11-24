package com.oldmermaidman.gomoku.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.databinding.ActivityOnlineHistoryBinding
import com.oldmermaidman.gomoku.databinding.ItemLobbyGameBinding
import com.oldmermaidman.gomoku.net.dto.GameHistoryDto
import com.oldmermaidman.gomoku.online.OnlineHistoryUi
import com.oldmermaidman.gomoku.online.OnlineHistoryViewModel
import com.oldmermaidman.gomoku.online.stoneOf
import com.oldmermaidman.gomoku.prefs.AppSettings
import kotlin.math.abs

/**
 * The player's finished online games, newest first (FEATURES.md, B2).
 *
 * A row carries the opponent, the result and the rating change; tapping it opens the
 * replay of that game. The list is a plain set of rows: the server caps it at fifty.
 */
class OnlineHistoryActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, OnlineHistoryActivity::class.java)
    }

    private lateinit var binding: ActivityOnlineHistoryBinding
    private val viewModel: OnlineHistoryViewModel by viewModels()
    private var theme: GomokuTheme = Themes.LIGHT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnlineHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyTheme()

        binding.btnHistoryBack.setOnClickListener { finish() }
        viewModel.state.observe(this) { render(it) }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
    }

    private fun render(ui: OnlineHistoryUi) {
        binding.historyList.removeAllViews()

        val error = ui.error
        val message = when {
            ui.loading && ui.games.isEmpty() -> getString(R.string.online_lobby_loading)
            error != null -> error
            ui.games.isEmpty() -> getString(R.string.online_history_empty)
            else -> null
        }
        binding.tvHistoryEmpty.text = message
        binding.tvHistoryEmpty.visibility = if (message == null) View.GONE else View.VISIBLE
        binding.tvHistoryEmpty.setTextColor(if (error != null) theme.timeLow else theme.statusText)

        for (game in ui.games) {
            val item = ItemLobbyGameBinding.inflate(layoutInflater, binding.historyList, false)
            item.tvLobbyRow.text = rowText(game)
            item.tvLobbyRow.setTextColor(theme.statusText)
            item.root.setOnClickListener {
                startActivity(OnlineReplayActivity.intent(this, game.gameId))
            }
            binding.historyList.addView(item.root)
        }
    }

    /** "ann · 1216 · You win · Freestyle · Rapid · rating +16 · Moves: 24". */
    private fun rowText(game: GameHistoryDto): String = listOfNotNull(
        game.opponentNickname.ifEmpty { null },
        game.opponentRating?.toString(),
        getString(resultName(game.result, stoneOf(game.you))),
        getString(ruleName(game.rule)),
        getString(timeName(game.timeControl)),
        game.ratingDelta?.let { delta ->
            getString(R.string.online_rating_delta, if (delta >= 0) "+" else "−", abs(delta))
        },
        getString(R.string.history_moves_count, game.moveCount),
    ).joinToString(" · ")

    private fun applyTheme() {
        theme = Themes.byId(AppSettings(this).theme)
        ThemeSupport.applySystemBarInsets(binding.root)
        ThemeSupport.applyWindow(this, binding.root, theme)
        ThemeSupport.tintHierarchy(binding.root, theme)

        binding.historyTopBar.setBackgroundColor(theme.background)
        binding.tvHistoryTitle.setTextColor(theme.statusText)
        binding.tvHistoryEmpty.setTextColor(theme.statusText)
        binding.btnHistoryBack.imageTintList = ColorStateList.valueOf(theme.statusText)
    }
}