package com.oldmermaidman.gomoku.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.databinding.ActivityOnlineGameBinding
import com.oldmermaidman.gomoku.model.Stone
import com.oldmermaidman.gomoku.net.RemoteClock
import com.oldmermaidman.gomoku.net.SocketState
import com.oldmermaidman.gomoku.online.OnlineClockUi
import com.oldmermaidman.gomoku.online.OnlineFinish
import com.oldmermaidman.gomoku.online.OnlineGameUi
import com.oldmermaidman.gomoku.online.OnlineGameViewModel
import com.oldmermaidman.gomoku.online.OnlineOutcome
import com.oldmermaidman.gomoku.prefs.AppSettings

/**
 * The online game screen (TZ §11.4).
 *
 * The board is the same [BoardView] the offline game uses; only the source of the moves
 * differs. The server owns the rules and the result, so the screen never judges a
 * position — it replays the snapshot's moves and draws what the server says.
 *
 * The undo request is a panel over the board rather than a dialog: the project keeps
 * the game free of modal windows (FEATURES.md, "Undo in an online game").
 */
class OnlineGameActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_GAME_ID = "game_id"

        fun intent(context: Context, gameId: String): Intent =
            Intent(context, OnlineGameActivity::class.java).putExtra(EXTRA_GAME_ID, gameId)
    }

    private lateinit var binding: ActivityOnlineGameBinding
    private lateinit var theme: GomokuTheme

    private val gameId: String by lazy { intent.getStringExtra(EXTRA_GAME_ID).orEmpty() }

    private val viewModel: OnlineGameViewModel by viewModels {
        OnlineGameViewModel.Factory(application, gameId)
    }

    private var current: OnlineGameUi? = null
    private var clock = OnlineClockUi()
    private var flash: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (gameId.isEmpty()) {
            finish()
            return
        }

        binding = ActivityOnlineGameBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyTheme()

        // Until the first snapshot arrives the clocks are unknown: the button must not
        // look live just because the layout enables it by default.
        binding.btnUndo.isEnabled = false

        binding.btnGameBack.setOnClickListener { finish() }
        binding.boardView.onCellTapped = { x, y -> viewModel.move(x, y) }
        binding.btnUndo.setOnClickListener { viewModel.requestUndo() }
        binding.btnDraw.setOnClickListener { viewModel.offerDraw() }
        binding.btnResign.setOnClickListener { viewModel.resign() }
        binding.btnRematch.setOnClickListener { viewModel.rematch() }
        binding.btnBlock.setOnClickListener { confirmBlock() }
        // The same panel answers whichever request is waiting.
        binding.btnUndoAccept.setOnClickListener { decide(true) }
        binding.btnUndoDecline.setOnClickListener { decide(false) }

        viewModel.state.observe(this) { state ->
            current = state
            renderState(state)
            renderOverlay()
        }
        viewModel.clock.observe(this) { value ->
            clock = value
            renderClock()
            renderOverlay()
        }
        viewModel.flash.observe(this) { message ->
            flash = message
            renderOverlay()
        }
        viewModel.gone.observe(this) { gone ->
            if (gone) finish()
        }
        // The rematch answer belongs to another game: open it instead of applying it here.
        viewModel.rematchGame.observe(this) { game ->
            if (game == null) return@observe
            viewModel.consumeRematch()
            startActivity(
                if (game.status == "ACTIVE") {
                    OnlineGameActivity.intent(this, game.gameId)
                } else {
                    // The room is waiting: its screen shows the code and polls for the opponent.
                    OnlineActivity.intent(this)
                },
            )
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onScreenStarted()
    }

    override fun onStop() {
        // Leaving the screen does not stop the game: the server keeps the clocks running.
        viewModel.onScreenStopped()
        super.onStop()
    }

    // ---- rendering --------------------------------------------------------

    private fun renderState(state: OnlineGameUi?) {
        if (state == null) {
            binding.tvGameStatus.text = getString(R.string.account_working)
            return
        }

        binding.boardView.game = state.board
        binding.boardView.theme = theme
        binding.boardView.showMoveNumbers = AppSettings(this).showMoveNumbers
        binding.boardView.ghost = state.ghost
        binding.boardView.winningLine = state.winningLine
        binding.boardView.invalidate()

        binding.tvOpponent.text = getString(R.string.online_opponent, state.opponent)
        binding.tvRole.text = getString(
            if (state.you == Stone.BLACK) R.string.online_you_black else R.string.online_you_white
        )
        binding.tvGameStatus.text = statusText(state)

        binding.btnUndo.text = getString(R.string.online_undo, state.undoRemaining)
        renderUndoButton()
        binding.btnResign.isEnabled = !state.busy && !state.finished && state.snapshot.status == "ACTIVE"

        // The bottom bar changes over: while the game runs it is undo and resign, and
        // once it is over the same space offers a rematch and a block.
        val finished = state.finished
        binding.btnUndo.visibility = if (finished) View.GONE else View.VISIBLE
        binding.btnDraw.visibility = if (finished) View.GONE else View.VISIBLE
        binding.btnResign.visibility = if (finished) View.GONE else View.VISIBLE
        binding.btnRematch.visibility = if (finished) View.VISIBLE else View.GONE
        binding.btnBlock.visibility = if (finished) View.VISIBLE else View.GONE
        binding.btnRematch.isEnabled = !state.busy
        binding.btnBlock.isEnabled = !state.busy
        binding.btnDraw.isEnabled = !state.busy &&
            state.snapshot.status == "ACTIVE" &&
            state.pendingUndo == null &&
            state.pendingDraw == null &&
            state.drawRemaining > 0
        binding.btnRematch.text = getString(
            if (state.rematchOffered) R.string.online_rematch_accept else R.string.online_rematch,
        )

        val connected = state.socket == SocketState.OPEN
        binding.tvConnection.visibility = if (connected) View.GONE else View.VISIBLE
        binding.tvConnection.text = if (connected) "" else getString(R.string.online_connection_lost)
    }

    private fun renderClock() {
        // The undo window is measured in time, so the button follows the clock even when
        // the game itself has no clock.
        renderUndoButton()
        binding.clockRow.visibility = if (clock.timed) View.VISIBLE else View.GONE
        if (!clock.timed) return

        val me = current?.you ?: Stone.BLACK
        binding.tvClockYou.text = clock.youRemainingMs?.let { formatClock(it) }.orEmpty()
        binding.tvClockOpponent.text = clock.opponentRemainingMs?.let { formatClock(it) }.orEmpty()

        binding.tvClockYou.setTextColor(clockColor(clock.youRemainingMs, clock.running == me))
        binding.tvClockOpponent.setTextColor(
            clockColor(clock.opponentRemainingMs, clock.running == me.opponent())
        )
    }

    /**
     * The undo button follows the clock: it is live only in the two seconds after our own
     * move (FEATURES.md), never for the player who still has to make one.
     */
    private fun renderUndoButton() {
        binding.btnUndo.isEnabled = clock.canUndo
    }

    /** Accent while running, warning colour in the last seconds, plain otherwise. */
    private fun clockColor(remainingMs: Long?, running: Boolean): Int = when {
        remainingMs == null -> theme.statusText
        remainingMs <= RemoteClock.LOW_TIME_MS -> theme.timeLow
        running -> theme.accent
        else -> theme.statusText
    }

    private fun renderOverlay() {
        val state = current
        val message = flash
        when {
            message != null -> {
                binding.undoOverlay.visibility = View.VISIBLE
                binding.tvUndoText.text = message
                binding.tvUndoCountdown.text = ""
                binding.undoButtons.visibility = View.GONE
            }
            state?.pendingUndo != null -> {
                binding.undoOverlay.visibility = View.VISIBLE
                // FEATURES.md: both sides see the same text; only the asked side decides.
                binding.tvUndoText.text = getString(R.string.online_undo_request_text)
                binding.tvUndoCountdown.text = clock.undoSeconds
                    ?.let { getString(R.string.online_undo_seconds, it) }
                    .orEmpty()
                binding.undoButtons.visibility =
                    if (state.pendingUndo.requestedByMe) View.GONE else View.VISIBLE
            }
            state?.pendingDraw != null -> {
                binding.undoOverlay.visibility = View.VISIBLE
                binding.tvUndoText.text = getString(R.string.online_draw_offered)
                binding.tvUndoCountdown.text = clock.drawSeconds
                    ?.let { getString(R.string.online_undo_seconds, it) }
                    .orEmpty()
                binding.undoButtons.visibility =
                    if (state.pendingDraw.requestedByMe) View.GONE else View.VISIBLE
            }
            else -> binding.undoOverlay.visibility = View.GONE
        }
    }

    /**
     * Blocking lasts until the player finds the block list, and the button sits next to
     * a rematch on a screen the player is in a hurry to leave: ask first, and say where
     * the way back is.
     */
    private fun confirmBlock() {
        val nickname = current?.opponent.orEmpty()
        val text = if (nickname.isBlank()) {
            getString(R.string.block_confirm_text_generic)
        } else {
            getString(R.string.block_confirm_text, nickname)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.block_confirm_title)
            .setMessage(text)
            .setPositiveButton(R.string.block_confirm_action) { _, _ -> viewModel.blockOpponent() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The panel's "yes" and "no" answer whichever request is on it. */
    private fun decide(accept: Boolean) {        val state = current ?: return
        if (state.pendingDraw != null) {
            viewModel.decideDraw(accept)
        } else {
            viewModel.decideUndo(accept)
        }
    }

    private fun statusText(state: OnlineGameUi): String = when {
        state.finished -> resultText(state)
        state.snapshot.status == "WAITING" -> getString(R.string.online_waiting_opponent)
        state.yourTurn -> getString(R.string.online_turn_you)
        else -> getString(R.string.online_turn_opponent)
    }

    private fun resultText(state: OnlineGameUi): String {
        val outcome = when (state.outcome) {
            OnlineOutcome.YOU_WIN -> R.string.online_result_you_win
            OnlineOutcome.YOU_LOSE -> R.string.online_result_you_lose
            OnlineOutcome.DRAW -> R.string.online_result_draw
            OnlineOutcome.NONE -> R.string.online_game_over
        }
        val reason = when (state.finish) {
            OnlineFinish.FIVE -> R.string.online_finish_five
            OnlineFinish.FORBIDDEN -> R.string.online_finish_forbidden
            OnlineFinish.TIMEOUT -> R.string.online_finish_timeout
            OnlineFinish.RESIGN -> R.string.online_finish_resign
            OnlineFinish.ABANDON -> R.string.online_finish_abandon
            OnlineFinish.AGREE -> R.string.online_finish_agree
            OnlineFinish.NONE -> null
        }
        val result = if (reason == null) {
            getString(outcome)
        } else {
            getString(outcome) + " · " + getString(reason)
        }
        // A ranked game also moved the rating; showing the change here is the whole
        // point of playing quick games.
        val delta = state.ratingDelta ?: return result
        val sign = if (delta >= 0) "+" else "−"
        return result + " · " + getString(R.string.online_rating_delta, sign, kotlin.math.abs(delta))
    }

    /** mm:ss, rounded up, so a second is still on the display while any part of it is left. */
    private fun formatClock(remainingMs: Long): String {
        val seconds = (remainingMs + 999) / 1_000
        return getString(R.string.clock_format, seconds / 60, seconds % 60)
    }

    // ---- theme ------------------------------------------------------------

    private fun applyTheme() {
        theme = Themes.byId(AppSettings(this).theme)
        ThemeSupport.applySystemBarInsets(binding.root)
        ThemeSupport.applyWindow(this, binding.root, theme)
        ThemeSupport.tintHierarchy(binding.root, theme)

        binding.gameTopBar.setBackgroundColor(theme.background)
        binding.tvGameTitle.setTextColor(theme.statusText)
        binding.tvOpponent.setTextColor(theme.accent)
        binding.tvConnection.setTextColor(theme.timeLow)
        binding.tvGameStatus.setTextColor(theme.statusText)
        binding.btnGameBack.imageTintList = ColorStateList.valueOf(theme.statusText)

        // The undo request is a rounded panel in the theme's colours, not a dialog.
        val density = resources.displayMetrics.density
        binding.undoOverlay.background = GradientDrawable().apply {
            cornerRadius = 20f * density
            setColor(theme.panelBackground)
            setStroke((1.5f * density).toInt(), theme.boardLine)
        }

        binding.boardView.theme = theme
    }
}