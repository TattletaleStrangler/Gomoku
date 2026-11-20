package com.oldmermaidman.gomoku.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.databinding.ActivityHistoryBinding
import com.oldmermaidman.gomoku.model.Coordinates
import com.oldmermaidman.gomoku.model.GomokuGame
import com.oldmermaidman.gomoku.model.Move
import com.oldmermaidman.gomoku.model.Stone
import com.oldmermaidman.gomoku.prefs.AppSettings

/**
 * The move list of the current game, as a full screen rather than a dialog — the
 * same reason as [SettingsActivity]: a modal window interrupts the game.
 *
 * Browsing is read-only. The board above the list previews the position after the
 * selected move; **continue from this move** sends the number of moves to keep back
 * to [com.oldmermaidman.gomoku.MainActivity], which rolls the live game back to it.
 * The game itself is never modified here.
 */
class HistoryActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_GAME = "game_state"
        private const val STATE_SELECTED = "selected_index"

        /** Number of moves to keep; sent back when the player continues from a move. */
        const val EXTRA_ROLLBACK_TO = "rollback_to"

        /** Starts the screen for [game]; the game is passed by value, not by reference. */
        fun intent(context: Context, game: GomokuGame): Intent =
            Intent(context, HistoryActivity::class.java).putExtra(EXTRA_GAME, game.encode())
    }

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var theme: GomokuTheme
    private lateinit var game: GomokuGame
    private lateinit var adapter: MoveAdapter

    /** Position shown in the preview: an index into the move list, -1 for the empty board. */
    private var selectedIndex = -1

    private lateinit var blackDot: GradientDrawable
    private lateinit var whiteDot: GradientDrawable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // A game that cannot be decoded shows an empty history instead of crashing.
        game = intent.getStringExtra(EXTRA_GAME)?.let { GomokuGame.decode(it) }
            ?: GomokuGame(GomokuGame.BOARD_SIZE, Stone.BLACK)

        val settings = AppSettings(this)
        theme = Themes.byId(settings.theme)

        ThemeSupport.applySystemBarInsets(binding.root)
        ThemeSupport.applyWindow(this, binding.root, theme)
        // Runs before the adapter is attached, so the list rows keep the colours the
        // adapter assigns them.
        ThemeSupport.tintHierarchy(binding.root, theme)

        binding.historyBoard.theme = theme
        binding.historyBoard.showMoveNumbers = settings.showMoveNumbers

        blackDot = stoneDot(theme.blackStone)
        whiteDot = stoneDot(theme.whiteStone)

        binding.tvMovesCount.text = getString(R.string.history_moves_count, game.history.size)

        binding.historyList.divider = theme.boardLine.toDrawable()
        binding.historyList.dividerHeight = resources.displayMetrics.density.toInt().coerceAtLeast(1)
        adapter = MoveAdapter(game.history.toList())
        binding.historyList.adapter = adapter
        binding.historyList.setOnItemClickListener { _, _, position, _ -> select(position) }

        binding.btnClose.setOnClickListener { finish() }
        binding.btnContinue.setOnClickListener { continueFromSelection() }

        // Opens on the current position; a rotated screen keeps the browsed one.
        val restored = savedInstanceState?.getInt(STATE_SELECTED) ?: (game.history.size - 1)
        select(restored.coerceIn(-1, game.history.size - 1))
        if (selectedIndex >= 0) binding.historyList.setSelection(selectedIndex)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED, selectedIndex)
    }

    /** Shows the position after the move at [index] and updates the whole screen. */
    private fun select(index: Int) {
        selectedIndex = index

        val preview = game.positionAfter(index + 1)
        binding.historyBoard.game = preview
        binding.historyBoard.invalidate()

        val selected = if (index >= 0) {
            val move = game.history[index]
            getString(
                R.string.history_selected,
                index + 1,
                Coordinates.label(move.x, move.y),
                getString(sideName(move.color))
            )
        } else {
            getString(R.string.history_empty)
        }
        binding.tvSelectedMove.text = if (game.timeLoss) {
            selected + getString(R.string.history_no_rollback)
        } else {
            selected
        }

        // The current position has nothing to roll back to, and a game lost on time
        // cannot be continued from an earlier move either.
        binding.btnContinue.isEnabled = !game.timeLoss && index + 1 in 1 until game.history.size
        adapter.notifyDataSetChanged()
    }

    private fun continueFromSelection() {
        val keep = selectedIndex + 1
        if (keep !in 1 until game.history.size) return

        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ROLLBACK_TO, keep))
        finish()
    }

    private fun sideName(color: Stone): Int =
        if (color == Stone.BLACK) R.string.side_black else R.string.side_white

    /** A small filled circle standing for a stone of [fill]'s colour. */
    private fun stoneDot(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        val stroke = (1.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        setStroke(stroke, theme.whiteStoneBorder)
    }

    /** One row per move: number, stone colour, cell name and side. */
    private inner class MoveAdapter(private val moves: List<Move>) : BaseAdapter() {

        override fun getCount(): Int = moves.size

        override fun getItem(position: Int): Any = moves[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_move, parent, false)
            val move = moves[position]

            val index = row.findViewById<TextView>(R.id.tvMoveIndex)
            index.text = getString(R.string.move_index, position + 1)
            // The accent marks the move the game currently stands on.
            index.setTextColor(if (position == moves.size - 1) theme.accent else theme.statusText)

            row.findViewById<View>(R.id.stoneDot).background =
                if (move.color == Stone.BLACK) blackDot else whiteDot

            val coord = row.findViewById<TextView>(R.id.tvMoveCoord)
            coord.text = Coordinates.label(move.x, move.y)
            coord.setTextColor(theme.statusText)

            val side = row.findViewById<TextView>(R.id.tvMoveSide)
            side.text = getString(sideName(move.color))
            side.setTextColor(theme.statusText)

            row.setBackgroundColor(
                if (position == selectedIndex) theme.panelBackground else Color.TRANSPARENT
            )
            return row
        }
    }
}
