package com.oldmermaidman.gomoku.net

import androidx.annotation.StringRes
import com.oldmermaidman.gomoku.R
import kotlinx.serialization.json.JsonObject

/**
 * Every failure the client can show the player, mapped to a message in the current
 * language (TZ §11.3).
 *
 * The server's error code is the contract: the same list as `ApiError` in the backend,
 * plus the failures that happen before a response arrives. The server's human-readable
 * `message` is kept for logcat only — the player sees the translated text of the code.
 */
enum class ApiError(@param:StringRes val messageRes: Int) {

    /** The request never reached the server: no network, DNS failure, refused connection. */
    NETWORK(R.string.error_network),

    /** The connection was made but the server did not answer in time. */
    TIMEOUT(R.string.error_timeout),

    /** The server answered with something that is not the documented JSON. */
    MALFORMED(R.string.error_malformed),

    VALIDATION_FAILED(R.string.error_validation),
    NICKNAME_TAKEN(R.string.error_nickname_taken),
    BAD_CREDENTIALS(R.string.error_bad_credentials),
    TOKEN_EXPIRED(R.string.error_session_expired),
    TOKEN_INVALID(R.string.error_session_expired),
    ACCOUNT_DELETED(R.string.error_session_expired),
    UNAUTHORIZED(R.string.error_session_expired),
    NOT_A_PARTICIPANT(R.string.error_not_participant),
    NOT_FOUND(R.string.error_not_found),
    GAME_NOT_FOUND(R.string.error_game_not_found),
    ROOM_NOT_FOUND(R.string.error_room_not_found),
    ROOM_FULL(R.string.error_room_full),
    SELF_JOIN(R.string.error_self_join),
    ACTIVE_GAME_EXISTS(R.string.error_active_game_exists),
    GAME_FINISHED(R.string.error_game_finished),
    REMATCH_NOT_ALLOWED(R.string.error_rematch_not_allowed),
    NOT_YOUR_TURN(R.string.error_not_your_turn),
    WRONG_PLY(R.string.error_wrong_ply),
    CELL_OCCUPIED(R.string.error_cell_occupied),
    OUT_OF_BOARD(R.string.error_out_of_board),
    FORBIDDEN_MOVE(R.string.error_forbidden_move),
    UNDO_NOT_ALLOWED(R.string.error_undo_not_allowed),
    UNDO_TOO_LATE(R.string.error_undo_too_late),
    UNDO_LIMIT_REACHED(R.string.error_undo_limit),
    UNDO_ALREADY_REQUESTED(R.string.error_undo_already),
    UNDO_NOT_REQUESTED(R.string.error_undo_not_requested),
    DRAW_ALREADY_REQUESTED(R.string.error_draw_already),
    DRAW_NOT_REQUESTED(R.string.error_draw_not_requested),
    DRAW_LIMIT_REACHED(R.string.error_draw_limit),
    REQUEST_ALREADY_PENDING(R.string.error_request_pending),
    RATE_LIMITED(R.string.error_rate_limited),
    INTERNAL_ERROR(R.string.error_internal),

    /** A code this build does not know, or a response with no code at all. */
    UNKNOWN(R.string.error_unknown);

    /**
     * True for a refusal of an undo request. FEATURES.md says the reason is never shown:
     * the player sees a short "declined" instead, because "too late" or "limit reached"
     * would leak information the opponent can use.
     */
    val isUndoRefusal: Boolean
        get() = this == UNDO_NOT_ALLOWED ||
            this == UNDO_TOO_LATE ||
            this == UNDO_LIMIT_REACHED ||
            this == UNDO_ALREADY_REQUESTED ||
            this == UNDO_NOT_REQUESTED

    companion object {
        /** Maps the server's `code` to a known value; unknown codes become [UNKNOWN]. */
        fun fromWire(code: String?): ApiError =
            entries.firstOrNull { it.name == code } ?: UNKNOWN
    }
}

/**
 * A failed call: the mapped [error], the server's own message for the log, and the
 * optional `details` object the server attaches to validation failures.
 *
 * [httpStatus] is 0 when no response arrived at all.
 */
class ApiException(
    val error: ApiError,
    val serverMessage: String? = null,
    val details: JsonObject? = null,
    val httpStatus: Int = 0,
    cause: Throwable? = null,
) : Exception(serverMessage ?: error.name, cause) {

    /** True when the session is gone and the player has to sign in again. */
    val isAuthFailure: Boolean
        get() = error == ApiError.TOKEN_EXPIRED ||
            error == ApiError.TOKEN_INVALID ||
            error == ApiError.UNAUTHORIZED ||
            error == ApiError.ACCOUNT_DELETED
}
