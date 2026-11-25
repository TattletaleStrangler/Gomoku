package com.oldmermaidman.gomoku.net.dto

import kotlinx.serialization.Serializable

/**
 * A crash the client sends on the next launch (FEATURES.md, B4).
 *
 * Only the stack trace is required; the rest is context for the admin panel. Nothing
 * secret belongs here — no tokens, no passwords, no board state.
 */
@Serializable
data class CrashReportRequest(
    val appVersion: String? = null,
    val versionCode: Int? = null,
    val device: String? = null,
    val android: String? = null,
    val locale: String? = null,
    val stacktrace: String,
)