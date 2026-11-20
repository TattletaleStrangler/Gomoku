package com.oldmermaidman.gomoku.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Tests for the text of a crash report; the format is pure JVM code. */
class CrashReportTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** 2023-11-14 22:13:20 UTC. */
    private val timeMs = 1_700_000_000_000L

    private val error = IllegalStateException("engine died", RuntimeException("no process"))

    private fun report(): String = CrashReport.format(
        error = error,
        timeMs = timeMs,
        versionName = "1.12 (13)",
        device = "Google Pixel 7, Android 14 (API 34)",
        threadName = "main",
        zone = utc
    )

    @Test
    fun theHeaderCarriesTimeVersionDeviceAndThread() {
        val text = report()

        assertTrue(text.contains("time: 2023-11-14 22:13:20 +0000"))
        assertTrue(text.contains("app: 1.12 (13)"))
        assertTrue(text.contains("device: Google Pixel 7, Android 14 (API 34)"))
        assertTrue(text.contains("thread: main"))
    }

    @Test
    fun theStackTraceCarriesTheExceptionAndItsCause() {
        val text = report()

        assertTrue(text.contains("java.lang.IllegalStateException: engine died"))
        assertTrue(text.contains("Caused by: java.lang.RuntimeException: no process"))
        assertTrue("the frames of this test are in the trace", text.contains("CrashReportTest"))
    }

    @Test
    fun theTimestampFollowsTheZoneItIsGiven() {
        assertEquals("2023-11-14 22:13:20 +0000", CrashReport.timestamp(timeMs, utc))
        assertEquals(
            "2023-11-15 03:13:20 +0500",
            CrashReport.timestamp(timeMs, TimeZone.getTimeZone("Etc/GMT-5"))
        )
    }

    @Test
    fun anEndlessMessageIsCutOff() {
        val huge = IllegalStateException("x".repeat(CrashReport.MAX_CHARS * 2))

        val text = CrashReport.format(huge, timeMs, "1.12", "device", "main", utc)

        assertTrue("the report must stay small", text.length < CrashReport.MAX_CHARS + 64)
        assertTrue(text.endsWith("report truncated\n"))
    }
}
