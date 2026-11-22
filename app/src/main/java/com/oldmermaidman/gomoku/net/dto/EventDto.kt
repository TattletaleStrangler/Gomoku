package com.oldmermaidman.gomoku.net.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Messages on the socket (TZ_BACKEND.md §8).
 *
 * Every event carries the full snapshot, so a client never mixes events with its own
 * guesses: whatever arrives, it can replace its state. `PONG` is the only message
 * without a game.
 */
@Serializable
data class GameEventDto(
    val type: String = "",
    val game: GameSnapshotDto? = null,
)

/** The one error shape the API returns. */
@Serializable
data class ErrorDto(
    val code: String? = null,
    val message: String? = null,
    val details: JsonObject? = null,
)
