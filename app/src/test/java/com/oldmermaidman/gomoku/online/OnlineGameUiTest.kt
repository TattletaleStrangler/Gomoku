package com.oldmermaidman.gomoku.online

import com.oldmermaidman.gomoku.model.Move
import com.oldmermaidman.gomoku.model.Stone
import com.oldmermaidman.gomoku.net.SocketState
import com.oldmermaidman.gomoku.net.dto.ClockDto
import com.oldmermaidman.gomoku.net.dto.GameSnapshotDto
import com.oldmermaidman.gomoku.net.dto.MoveDto
import com.oldmermaidman.gomoku.net.dto.PlayerDto
import com.oldmermaidman.gomoku.net.dto.UndoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildOnlineGameUi] — the pure translation from a server snapshot to what the screen
 * draws. No Android types are involved, so the rules the screen depends on are checked
 * here rather than through the UI.
 */
class OnlineGameUiTest {

    private fun snapshot(
        you: String? = "BLACK",
        status: String = "ACTIVE",
        turn: String? = "BLACK",
        moves: List<MoveDto> = emptyList(),
        result: String = "NONE",
        finishReason: String = "NONE",
        undoRemaining: Int = 3,
        undo: UndoDto? = null,
        lastUndoneMove: MoveDto? = null,
        rule: String = "FREESTYLE",
        black: PlayerDto? = null,
        white: PlayerDto? = null,
    ) = GameSnapshotDto(
        gameId = "g",
        code = "ABC123",
        rule = rule,
        timeControl = "RAPID",
        status = status,
        you = you,
        ply = moves.size + 1,
        turn = turn,
        moves = moves,
        black = black,
        white = white,
        clocks = ClockDto(60_000L, 60_000L, turn),
        serverTime = "2026-11-21T03:20:11.482Z",
        result = result,
        finishReason = finishReason,
        undoRemaining = undoRemaining,
        undo = undo,
        lastUndoneMove = lastUndoneMove,
    )

    @Test
    fun fiveInARowIsAWinningLine() {
        val moves = (0 until 5).map { MoveDto(ply = it + 1, x = it, y = 7, color = "BLACK") }

        val ui = buildOnlineGameUi(
            snapshot(moves = moves, status = "FINISHED", turn = null, result = "BLACK_WIN", finishReason = "FIVE"),
            SocketState.OPEN,
        )!!

        assertEquals(OnlineOutcome.YOU_WIN, ui.outcome)
        assertEquals(OnlineFinish.FIVE, ui.finish)
        assertEquals(5, ui.winningLine.size)
        assertTrue(ui.finished)
        assertFalse(ui.yourTurn)
    }

    @Test
    fun theGhostIsDrawnOnlyOnAFreeCell() {
        val ghost = MoveDto(ply = 1, x = 7, y = 7, color = "BLACK")

        val free = buildOnlineGameUi(
            snapshot(moves = emptyList(), lastUndoneMove = ghost),
            SocketState.OPEN,
        )!!
        assertEquals(Move(7, 7, Stone.BLACK), free.ghost)

        val occupied = buildOnlineGameUi(
            snapshot(moves = listOf(ghost), lastUndoneMove = ghost),
            SocketState.OPEN,
        )!!
        assertNull(occupied.ghost)
    }

    @Test
    fun aPendingUndoBelongsToTheAsker() {
        val undo = UndoDto(requestedBy = "BLACK", deadline = "2026-11-21T03:20:14.482Z", ply = 1)

        val asker = buildOnlineGameUi(snapshot(you = "BLACK", undo = undo), SocketState.OPEN)!!
        assertTrue(asker.pendingUndo!!.requestedByMe)
        assertEquals(1, asker.pendingUndo!!.ply)

        val asked = buildOnlineGameUi(snapshot(you = "WHITE", undo = undo), SocketState.OPEN)!!
        assertFalse(asked.pendingUndo!!.requestedByMe)
    }

    @Test
    fun theTurnFollowsTheSnapshot() {
        assertTrue(buildOnlineGameUi(snapshot(turn = "BLACK"), SocketState.OPEN)!!.yourTurn)
        assertFalse(buildOnlineGameUi(snapshot(turn = "WHITE"), SocketState.OPEN)!!.yourTurn)
    }

    @Test
    fun aWaitingRoomHasNoTurn() {
        val ui = buildOnlineGameUi(
            snapshot(status = "WAITING", turn = null, moves = emptyList()),
            SocketState.OPEN,
        )!!

        assertFalse(ui.finished)
        assertFalse(ui.yourTurn)
        assertEquals("WAITING", ui.snapshot.status)
    }

    @Test
    fun renjuTurnsOnForbiddenChecks() {
        val ui = buildOnlineGameUi(snapshot(rule = "RENJU"), SocketState.OPEN)!!
        assertTrue(ui.board.forbiddenMoves)
    }

    @Test
    fun theRatingDeltaBelongsToTheViewer() {
        val black = PlayerDto(nickname = "ann", rating = 1184, ratingDelta = -16)
        val white = PlayerDto(nickname = "bob", rating = 1216, ratingDelta = 16)

        val winner = buildOnlineGameUi(
            snapshot(
                you = "WHITE",
                status = "FINISHED",
                turn = null,
                result = "WHITE_WIN",
                finishReason = "RESIGN",
                black = black,
                white = white,
            ),
            SocketState.OPEN,
        )!!
        assertEquals(16, winner.ratingDelta)

        val casual = buildOnlineGameUi(
            snapshot(you = "WHITE", status = "FINISHED", turn = null, result = "WHITE_WIN"),
            SocketState.OPEN,
        )!!
        assertNull(casual.ratingDelta)
    }

    @Test
    fun theRematchOfferIsVisibleOnTheFinishedScreen() {
        val offered = buildOnlineGameUi(
            snapshot(you = "BLACK", status = "FINISHED", turn = null, result = "BLACK_WIN")
                .copy(rematchGameId = "r"),
            SocketState.OPEN,
        )!!
        assertTrue(offered.rematchOffered)

        val none = buildOnlineGameUi(
            snapshot(you = "BLACK", status = "FINISHED", turn = null, result = "BLACK_WIN"),
            SocketState.OPEN,
        )!!
        assertFalse(none.rematchOffered)
    }

    /**
     * The regression this test guards: the button used to be enabled for everyone with
     * attempts left, so the player who still had to move could tap it and only got a
     * refusal from the server. It belongs to the player whose move is the last one.
     */
    @Test
    fun onlyThePlayerWhoJustMovedMayAskForAnUndo() {
        val blackMoved = listOf(MoveDto(ply = 1, x = 7, y = 7, color = "BLACK"))
        val windowUntil = 10_000L

        // Black has just moved and White is to answer: the button is live for Black.
        val black = buildOnlineGameUi(
            snapshot(you = "BLACK", turn = "WHITE", moves = blackMoved),
            SocketState.OPEN,
        )!!
        assertTrue(canRequestUndo(black, nowMs = 5_000L, undoWindowUntilMs = windowUntil))

        // White is the one to move, so White never sees it live.
        val white = buildOnlineGameUi(
            snapshot(you = "WHITE", turn = "WHITE", moves = blackMoved),
            SocketState.OPEN,
        )!!
        assertFalse(canRequestUndo(white, nowMs = 5_000L, undoWindowUntilMs = windowUntil))

        // After the two seconds the request is too late for Black as well.
        assertFalse(canRequestUndo(black, nowMs = 10_001L, undoWindowUntilMs = windowUntil))
    }

    /**
     * The regression this test guards: the window used to open only for the first move,
     * so after the second move of the same player the button never came back.
     */
    @Test
    fun theUndoWindowOpensAfterEveryOwnMoveAndClosesWhenTheOpponentMoves() {
        val blackMove1 = MoveDto(ply = 1, x = 7, y = 7, color = "BLACK")
        val whiteMove = MoveDto(ply = 2, x = 8, y = 8, color = "WHITE")
        val blackMove2 = MoveDto(ply = 3, x = 9, y = 9, color = "BLACK")

        val start = snapshot(you = "BLACK", turn = "BLACK", moves = emptyList())
        val afterBlack1 = snapshot(you = "BLACK", turn = "WHITE", moves = listOf(blackMove1))
        val afterWhite = snapshot(you = "BLACK", turn = "BLACK", moves = listOf(blackMove1, whiteMove))
        val afterBlack2 = snapshot(
            you = "BLACK",
            turn = "WHITE",
            moves = listOf(blackMove1, whiteMove, blackMove2),
        )

        assertEquals(0L, nextUndoWindow(null, start, 1_000L, 0L))
        assertEquals(3_000L, nextUndoWindow(start, afterBlack1, 1_000L, 0L))
        assertEquals(0L, nextUndoWindow(afterBlack1, afterWhite, 5_000L, 3_000L))
        assertEquals(7_000L, nextUndoWindow(afterWhite, afterBlack2, 5_000L, 0L))

        // The socket echo of the same move does not restart the two seconds.
        assertEquals(7_000L, nextUndoWindow(afterBlack2, afterBlack2, 9_000L, 7_000L))
    }

    @Test
    fun anUndoRequestNeedsAttemptsAndNoPendingOne() {
        val blackMoved = listOf(MoveDto(ply = 1, x = 7, y = 7, color = "BLACK"))
        val undo = UndoDto(requestedBy = "BLACK", deadline = "2026-11-21T03:20:14.482Z", ply = 1)

        val spent = buildOnlineGameUi(
            snapshot(you = "BLACK", turn = "WHITE", moves = blackMoved, undoRemaining = 0),
            SocketState.OPEN,
        )!!
        assertFalse(canRequestUndo(spent, 5_000L, 10_000L))

        val pending = buildOnlineGameUi(
            snapshot(you = "BLACK", turn = "WHITE", moves = blackMoved, undo = undo),
            SocketState.OPEN,
        )!!
        assertFalse(canRequestUndo(pending, 5_000L, 10_000L))

        val busy = buildOnlineGameUi(
            snapshot(you = "BLACK", turn = "WHITE", moves = blackMoved),
            SocketState.OPEN,
            busy = true,
        )!!
        assertFalse(canRequestUndo(busy, 5_000L, 10_000L))
    }
}