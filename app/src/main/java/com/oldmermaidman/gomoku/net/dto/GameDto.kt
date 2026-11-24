package com.oldmermaidman.gomoku.net.dto

import kotlinx.serialization.Serializable

/**
 * Rooms and games (TZ_BACKEND.md §7, `game/dto`).
 *
 * [GameSnapshotDto] is the one structure every game endpoint returns and every socket
 * event carries: the client replaces its copy with it instead of patching it. Nulls
 * are omitted by the server, so every optional field has a default here.
 */

/**
 * What the creator of a room chooses.
 *
 * [color] is `BLACK`, `WHITE`, or `ANY` (the opponent chooses when joining). A null is
 * omitted from the body and the server defaults the creator to black. [visibility] is
 * `PRIVATE` (by code, the default) or `PUBLIC` (listed in the lobby).
 */
@Serializable
data class CreateGameRequest(
    val rule: String,
    val timeControl: String,
    val color: String? = null,
    val visibility: String? = null,
)

/**
 * Quick match: the settings the player wants (FEATURES.md, B1).
 *
 * [color] is optional; with no preference the server opens the room as `ANY` and the
 * opponent decides when they join.
 */
@Serializable
data class QuickGameRequest(
    val rule: String,
    val timeControl: String,
    val color: String? = null,
)

/** One row of the open-games list. */
@Serializable
data class LobbyGameDto(
    val gameId: String,
    val code: String,
    val rule: String,
    val timeControl: String,
    val creatorColor: String = "ANY",
    val creatorNickname: String = "",
    val creatorRating: Int? = null,
    val ranked: Boolean = false,
    val createdAt: String? = null,
)

/**
 * Joining a room by the code shown to its creator.
 *
 * [color] is a wish that the server honours only when the creator left the colour open;
 * otherwise the room's own choice wins.
 */
@Serializable
data class JoinGameRequest(
    val code: String,
    val color: String? = null,
)

/**
 * One move. `ply` is the number the client expects to make, so a double tap or a
 * retry cannot place two stones.
 */
@Serializable
data class MoveRequest(
    val ply: Int,
    val x: Int,
    val y: Int,
)

/** The opponent's answer to an undo request. */
@Serializable
data class UndoDecisionRequest(
    val accept: Boolean,
)

/** A player as the snapshot shows them. */
@Serializable
data class PlayerDto(
    val userId: String? = null,
    val nickname: String = "",
    /** Current Elo, and the change a just-finished ranked game produced. */
    val rating: Int? = null,
    val ratingDelta: Int? = null,
)

/** One stone on the board. `color` is `BLACK` or `WHITE`. */
@Serializable
data class MoveDto(
    val ply: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
    val color: String = "BLACK",
)

/** Milliseconds left; both null for a game without clocks. */
@Serializable
data class ClockDto(
    val blackRemainingMs: Long? = null,
    val whiteRemainingMs: Long? = null,
    val running: String? = null,
)

/**
 * A pending undo request: who asked (as a colour), until when the opponent must
 * answer (server time), and which move is at stake.
 */
@Serializable
data class UndoDto(
    val requestedBy: String? = null,
    val deadline: String? = null,
    val ply: Int = 0,
)

/** One row of the player's finished games (`GET /games/history`). */
@Serializable
data class GameHistoryDto(
    val gameId: String = "",
    val rule: String = "FREESTYLE",
    val timeControl: String = "UNTIMED",
    val ranked: Boolean = false,
    val result: String = "NONE",
    val finishReason: String = "NONE",
    val you: String? = null,
    val opponentNickname: String = "",
    val opponentRating: Int? = null,
    val ratingDelta: Int? = null,
    val finishedAt: String? = null,
    val moveCount: Int = 0,
)

/**
 * The whole state of a game, as every game endpoint returns it.
 *
 * `you` is the caller's colour, `turn` is null when nobody is to move, and the clock
 * values are already the live ones at `serverTime`: the client counts down from them
 * instead of trusting the device clock.
 *
 * `undoRemaining` is how many undo requests the viewer still has, `undo` is the
 * pending request (or null), and `lastUndoneMove` is the stone an accepted undo
 * removed — shown as a ghost only to the player who asked.
 */
@Serializable
data class GameSnapshotDto(
    val gameId: String = "",
    val code: String = "",
    val rule: String = "FREESTYLE",
    val timeControl: String = "UNTIMED",
    val status: String = "WAITING",
    /** True for quick-play games, whose result settles the Elo rating. */
    val ranked: Boolean = false,
    val black: PlayerDto? = null,
    val white: PlayerDto? = null,
    val you: String? = null,
    val ply: Int = 0,
    val turn: String? = null,
    val moves: List<MoveDto> = emptyList(),
    val clocks: ClockDto? = null,
    val serverTime: String? = null,
    val result: String = "NONE",
    val finishReason: String = "NONE",
    val undoRemaining: Int = 0,
    val undo: UndoDto? = null,
    val lastUndoneMove: MoveDto? = null,
    /** The room offered for a rematch of this finished game, or null. */
    val rematchGameId: String? = null,
)
