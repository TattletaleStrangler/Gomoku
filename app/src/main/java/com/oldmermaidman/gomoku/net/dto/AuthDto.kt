package com.oldmermaidman.gomoku.net.dto

import kotlinx.serialization.Serializable

/**
 * Requests and responses of the account endpoints (TZ_BACKEND.md §7, `auth/dto`).
 *
 * Every field has a default, so a missing or extra key never breaks the app: the
 * server omits nulls, and a newer server may add fields the client does not know.
 */

/** Registration and login share one shape. */
@Serializable
data class CredentialsRequest(
    val nickname: String,
    val password: String,
)

/** The refresh token handed back by registration or login. */
@Serializable
data class RefreshTokenRequest(
    val refreshToken: String,
)

/** Confirms the password before the account is deleted. */
@Serializable
data class DeleteAccountRequest(
    val password: String,
)

/** The player as the client sees them. */
@Serializable
data class UserDto(
    val id: String = "",
    val nickname: String = "",
    val createdAt: String? = null,
)

/** Win/loss/draw record over finished games. */
@Serializable
data class StatsDto(
    val wins: Long = 0,
    val losses: Long = 0,
    val draws: Long = 0,
)

/** `GET /me`: the player plus their record. */
@Serializable
data class ProfileDto(
    val id: String = "",
    val nickname: String = "",
    /** Elo of ranked (quick-play) games. */
    val rating: Int = 1200,
    val createdAt: String? = null,
    val stats: StatsDto = StatsDto(),
)

/**
 * What registration, login and refresh return. `expiresIn` is the access token's
 * lifetime in seconds, so the client knows when to refresh without parsing the JWT.
 */
@Serializable
data class TokenDto(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresIn: Long = 0,
    val user: UserDto? = null,
)
