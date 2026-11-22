package com.oldmermaidman.gomoku.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping from the server's error codes to the client's enum (TZ_BACKEND.md §7.3).
 *
 * A code the client does not know must not crash it or be shown raw: it becomes
 * [ApiError.UNKNOWN], and the generic message is used.
 */
class ApiErrorTest {

    @Test
    fun knownCodesAreMapped() {
        assertEquals(ApiError.CELL_OCCUPIED, ApiError.fromWire("CELL_OCCUPIED"))
        assertEquals(ApiError.UNDO_TOO_LATE, ApiError.fromWire("UNDO_TOO_LATE"))
        assertEquals(ApiError.UNDO_LIMIT_REACHED, ApiError.fromWire("UNDO_LIMIT_REACHED"))
        assertEquals(ApiError.NICKNAME_TAKEN, ApiError.fromWire("NICKNAME_TAKEN"))
    }

    @Test
    fun anUnknownCodeBecomesUnknown() {
        assertEquals(ApiError.UNKNOWN, ApiError.fromWire("SOMETHING_NEW"))
        assertEquals(ApiError.UNKNOWN, ApiError.fromWire(null))
        assertEquals(ApiError.UNKNOWN, ApiError.fromWire(""))
    }

    @Test
    fun everyCodeHasAMessage() {
        ApiError.entries.forEach { error ->
            assertTrue("$error has no message", error.messageRes != 0)
        }
    }

    @Test
    fun onlySessionFailuresAreAuthFailures() {
        assertTrue(ApiException(ApiError.TOKEN_EXPIRED).isAuthFailure)
        assertTrue(ApiException(ApiError.TOKEN_INVALID).isAuthFailure)
        assertTrue(ApiException(ApiError.UNAUTHORIZED).isAuthFailure)
        assertTrue(ApiException(ApiError.ACCOUNT_DELETED).isAuthFailure)
        assertFalse(ApiException(ApiError.CELL_OCCUPIED).isAuthFailure)
        assertFalse(ApiException(ApiError.NETWORK).isAuthFailure)
    }
}