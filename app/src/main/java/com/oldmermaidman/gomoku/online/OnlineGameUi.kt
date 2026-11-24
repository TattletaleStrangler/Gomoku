package com.oldmermaidman.gomoku.online

import com.oldmermaidman.gomoku.model.GomokuGame
import com.oldmermaidman.gomoku.model.Move
import com.oldmermaidman.gomoku.model.Stone
import com.oldmermaidman.gomoku.net.SocketState
import com.oldmermaidman.gomoku.net.dto.GameSnapshotDto
import java.time.Instant

/** What the result means for the player who is looking at the screen. */
enum class OnlineOutcome { NONE, YOU_WIN, YOU_LOSE, DRAW }

/** Why the game ended, as far as the screen cares. */
enum class OnlineFinish { NONE, FIVE, FORBIDDEN, TIMEOUT, RESIGN, ABANDON }

/** A pending undo request, with the server deadline converted to epoch milliseconds. */
data class PendingUndo(
    val requestedByMe: Boolean,
    val ply: Int,
    val deadlineMs: Long,
)

/**
 * Everything the online game screen draws, derived from one server snapshot.
 *
 * The board is a plain [GomokuGame] filled with [GomokuGame.replay]: no rule is judged
 * locally, because the server already did. Only presentation is derived here — the
 * winning line for the highlight, the ghost of an undone stone, whose turn it is — so
 * the same snapshot always produces the same screen.
 */
data class OnlineGameUi(
    val snapshot: GameSnapshotDto,
    val board: GomokuGame,
    val you: Stone,
    val opponent: String,
    val ghost: Move?,
    val winningLine: List<Move>,
    val yourTurn: Boolean,
    val finished: Boolean,
    val outcome: OnlineOutcome,
    val finish: OnlineFinish,
    /** The viewer's Elo change, set only after a ranked game has finished. */
    val ratingDelta: Int?,
    val undoRemaining: Int,
    val pendingUndo: PendingUndo?,
    val socket: SocketState,
    /** True while a command is on its way; the board ignores taps then. */
    val busy: Boolean,
)

/**
 * How long an undo request stays possible after a move (FEATURES.md). The server owns
 * the real deadline; this only decides when the button greys out.
 */
const val UNDO_WINDOW_MS = 2_000L

/** The colour a server wire value names; [Stone.EMPTY] for anything else. */
fun stoneOf(wire: String?): Stone = when (wire) {
    "BLACK" -> Stone.BLACK
    "WHITE" -> Stone.WHITE
    else -> Stone.EMPTY
}

/**
 * When the local undo window closes after [next].
 *
 * The window opens only when the snapshot shows **our own** stone as the last move and
 * the opponent is to answer — never for the player who still has to move. It closes as
 * soon as the opponent plays, and opens again after our next move, which is why this is
 * recomputed on every snapshot rather than once per game.
 *
 * [currentWindowUntilMs] is kept when the snapshot carries no new move (for example the
 * socket echo of the move we have just applied), so a duplicate does not restart the
 * two seconds.
 */
fun nextUndoWindow(
    previous: GameSnapshotDto?,
    next: GameSnapshotDto,
    receivedAtMs: Long,
    currentWindowUntilMs: Long,
    windowMs: Long = UNDO_WINDOW_MS,
): Long {
    val you = stoneOf(next.you)
    val last = next.moves.lastOrNull()
    val ownMoveIsLast = you != Stone.EMPTY && last != null && stoneOf(last.color) == you
    val opponentToMove = you != Stone.EMPTY && stoneOf(next.turn) != you
    if (next.status != "ACTIVE" || !ownMoveIsLast || !opponentToMove) return 0L
    val sameMoves = previous != null && previous.moves.size == next.moves.size
    return if (sameMoves) currentWindowUntilMs else receivedAtMs + windowMs
}

/**
 * Whether the local player may ask the opponent to take the last move back at [nowMs].
 *
 * FEATURES.md: the request is possible only in the two seconds after **our own** move,
 * when the opponent is the one to answer, once per move and at most three times per
 * game. The server re-checks every one of these; the client uses the same rule to decide
 * when the button is live, so the player who still has to move never sees it enabled.
 *
 * [undoWindowUntilMs] is the monotonic moment the local window closes, derived in
 * [com.oldmermaidman.gomoku.online.OnlineGameViewModel] from the snapshot's arrival.
 */
fun canRequestUndo(
    screen: OnlineGameUi,
    nowMs: Long,
    undoWindowUntilMs: Long,
): Boolean =
    !screen.busy &&
        !screen.finished &&
        screen.snapshot.status == "ACTIVE" &&
        screen.pendingUndo == null &&
        screen.undoRemaining > 0 &&
        !screen.yourTurn &&
        screen.snapshot.moves.isNotEmpty() &&
        nowMs <= undoWindowUntilMs

/** Parses an ISO-8601 instant; null when the server sent something unexpected. */
fun instantOf(wire: String?): Instant? =
    wire?.let { runCatching { Instant.parse(it) }.getOrNull() }

/**
 * Builds the screen state from a snapshot. Returns null when the snapshot belongs to a
 * viewer whose colour is unknown — the app never has a use for that, but it keeps the
 * builder total.
 */
fun buildOnlineGameUi(
    snapshot: GameSnapshotDto,
    socket: SocketState,
    busy: Boolean = false,
): OnlineGameUi? {
    val you = stoneOf(snapshot.you)
    if (you == Stone.EMPTY) return null

    val board = GomokuGame(GomokuGame.BOARD_SIZE, you, snapshot.rule == "RENJU")
    for (move in snapshot.moves) {
        val color = stoneOf(move.color)
        if (color == Stone.EMPTY) continue
        if (move.x !in 0 until board.size || move.y !in 0 until board.size) continue
        board.replay(move.x, move.y, color)
    }

    val finished = snapshot.status == "FINISHED" || snapshot.status == "ABANDONED"
    val outcome = outcomeOf(snapshot.result, you)
    val finish = finishOf(snapshot.finishReason)
    val winningLine = if (outcome != OnlineOutcome.NONE && finish == OnlineFinish.FIVE) {
        board.winningLineOf(if (outcome == OnlineOutcome.YOU_WIN) you else you.opponent())
    } else {
        emptyList()
    }

    // The ghost is the requester's own stone; it is drawn only while the cell is free,
    // because once they play again the server forgets it.
    val ghost = snapshot.lastUndoneMove
        ?.takeIf { it.x in 0 until board.size && it.y in 0 until board.size }
        ?.takeIf { board.board[it.y][it.x] == Stone.EMPTY }
        ?.let { Move(it.x, it.y, stoneOf(it.color)) }
        ?.takeIf { it.color != Stone.EMPTY }

    val pending = snapshot.undo?.let { undo ->
        val deadline = instantOf(undo.deadline)?.toEpochMilli() ?: return@let null
        PendingUndo(
            requestedByMe = stoneOf(undo.requestedBy) == you,
            ply = undo.ply,
            deadlineMs = deadline,
        )
    }

    return OnlineGameUi(
        snapshot = snapshot,
        board = board,
        you = you,
        opponent = if (you == Stone.BLACK) snapshot.white?.nickname.orEmpty() else snapshot.black?.nickname.orEmpty(),
        ghost = ghost,
        winningLine = winningLine,
        yourTurn = snapshot.status == "ACTIVE" && stoneOf(snapshot.turn) == you,
        finished = finished,
        outcome = outcome,
        finish = finish,
        ratingDelta = when (you) {
            Stone.BLACK -> snapshot.black?.ratingDelta
            Stone.WHITE -> snapshot.white?.ratingDelta
            else -> null
        },
        undoRemaining = snapshot.undoRemaining.coerceAtLeast(0),
        pendingUndo = pending,
        socket = socket,
        busy = busy,
    )
}

private fun outcomeOf(result: String, you: Stone): OnlineOutcome = when {
    result == "DRAW" -> OnlineOutcome.DRAW
    result.isEmpty() || result == "NONE" -> OnlineOutcome.NONE
    result == if (you == Stone.BLACK) "BLACK_WIN" else "WHITE_WIN" -> OnlineOutcome.YOU_WIN
    else -> OnlineOutcome.YOU_LOSE
}

private fun finishOf(reason: String?): OnlineFinish = when (reason) {
    "FIVE" -> OnlineFinish.FIVE
    "FORBIDDEN" -> OnlineFinish.FORBIDDEN
    "TIMEOUT" -> OnlineFinish.TIMEOUT
    "RESIGN" -> OnlineFinish.RESIGN
    "ABANDON" -> OnlineFinish.ABANDON
    else -> OnlineFinish.NONE
}