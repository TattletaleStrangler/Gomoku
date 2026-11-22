package com.oldmermaidman.gomoku.net

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration of the network layer.
 *
 * - [Json.ignoreUnknownKeys] — a newer server may add fields; an older client must not
 *   fail on them.
 * - [Json.coerceInputValues] — a null where a default exists becomes the default, which
 *   is how the server's "nulls are omitted" rule is honoured.
 * - [Json.explicitNulls] is off, so a null field is not sent at all.
 * - [Json.encodeDefaults] is on: request bodies carry every field, including a
 *   `false` that would otherwise be dropped.
 */
val NetJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}
