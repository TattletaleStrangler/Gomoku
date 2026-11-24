package com.oldmermaidman.gomoku.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.databinding.ActivityOnlineReplayBinding
import com.oldmermaidman.gomoku.model.Coordinates
import com.oldmermaidman.gomoku.model.Stone
import com.oldmermaidman.gomoku.online.OnlineReplayUi
import com.oldmermaidman.gomoku.online.OnlineReplayViewModel
import com.oldmermaidman.gomoku.prefs.AppSettings
import kotlin.math.abs

/**
 * Steps through a finished online game (FEATURES.md, B2): the same board as the live
 * screen, with a seek bar over the recorded moves. Read-only — there is nothing to
 * roll back online, unlike the offline history.
 */
class OnlineReplayActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_GAME_ID = "game_id"

        fun intent(context: Context, gameId: String): Intent =
            Intent(context, OnlineReplayActivity::class.java).putExtra(EXTRA_GAME_ID, gameId)
    }

    private lateinit var binding: ActivityOnlineReplayBinding
    private val viewModel: OnlineReplayViewModel by viewModels {
        OnlineReplayViewModel.Factory(
            application,
            intent.getStringExtra(EXTRA_GAME_ID).orEmpty(),
        )
    }
    private var theme: GomokuTheme = Themes.LIGHT
    private var current: OnlineReplayUi = OnlineReplayUi()

    /** Wire values and labels of the report reasons (FUTURE.md). */
    private val reportReasons = listOf(
        "NICKNAME" to R.string.report_reason_nickname,
        "STALLING" to R.string.report_reason_stalling,
        "CHEATING" to R.string.report_reason_cheating,
        "OTHER" to R.string.report_reason_other,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnlineReplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyTheme()

        binding.btnReplayBack.setOnClickListener { finish() }
        binding.btnBlock.setOnClickListener { viewModel.blockOpponent() }
        binding.btnReport.setOnClickListener { askReportReason() }
        binding.seekMove.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderPosition(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit

            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        viewModel.state.observe(this) { render(it) }
        viewModel.message.observe(this) { text ->
            if (text == null) return@observe
            viewModel.consumeMessage()
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.load()
    }

    private fun render(ui: OnlineReplayUi) {
        current = ui
        if (ui.snapshot == null) {
            binding.tvReplayMove.text = ui.error ?: getString(R.string.online_lobby_loading)
            binding.tvReplayResult.text = ""
            binding.seekMove.isEnabled = false
            return
        }
        binding.seekMove.isEnabled = true
        binding.seekMove.max = ui.moves.size
        binding.seekMove.progress = ui.moves.size
        renderPosition(ui.moves.size)
        binding.tvReplayResult.text = resultText(ui)
    }

    /**
     * A short list of reasons, with no free text: it is enough to act on and cannot say
     * more than the reporter meant (FUTURE.md). The replay screen is not the game, so a
     * dialog is allowed here — the game screen itself still has none.
     */
    private fun askReportReason() {
        val labels = reportReasons.map { getString(it.second) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.online_report_title)
            .setItems(labels) { _, which -> viewModel.report(reportReasons[which].first) }
            .show()
    }

    /** Shows the position after [count] moves; zero is the empty board. */
    private fun renderPosition(count: Int) {
        val board = current.board ?: return
        binding.replayBoard.game = board.positionAfter(count)
        binding.replayBoard.invalidate()

        val moves = current.moves
        binding.tvReplayMove.text = if (count <= 0 || count > moves.size) {
            getString(R.string.history_empty)
        } else {
            val move = moves[count - 1]
            getString(
                R.string.history_selected,
                count,
                Coordinates.label(move.x, move.y),
                getString(if (move.color == "BLACK") R.string.side_black else R.string.side_white),
            )
        }
    }

    /** "vs ann · 1216 · You win · rating +16 · Moves: 24". */
    private fun resultText(ui: OnlineReplayUi): String {
        val snapshot = ui.snapshot ?: return ""
        val opponent = if (ui.you == Stone.BLACK) snapshot.white else snapshot.black
        val me = if (ui.you == Stone.BLACK) snapshot.black else snapshot.white
        return listOfNotNull(
            getString(R.string.online_replay_vs, opponent?.nickname.orEmpty()),
            opponent?.rating?.toString(),
            getString(resultName(snapshot.result, ui.you)),
            me?.ratingDelta?.let { delta ->
                getString(R.string.online_rating_delta, if (delta >= 0) "+" else "−", abs(delta))
            },
            getString(R.string.history_moves_count, ui.moves.size),
        ).joinToString(" · ")
    }

    private fun applyTheme() {
        theme = Themes.byId(AppSettings(this).theme)
        ThemeSupport.applySystemBarInsets(binding.root)
        ThemeSupport.applyWindow(this, binding.root, theme)
        ThemeSupport.tintHierarchy(binding.root, theme)

        binding.replayTopBar.setBackgroundColor(theme.background)
        binding.tvReplayTitle.setTextColor(theme.statusText)
        binding.tvReplayMove.setTextColor(theme.statusText)
        binding.tvReplayResult.setTextColor(theme.statusText)
        binding.btnReplayBack.imageTintList = ColorStateList.valueOf(theme.statusText)

        binding.replayBoard.theme = theme
        binding.replayBoard.showMoveNumbers = AppSettings(this).showMoveNumbers
    }
}