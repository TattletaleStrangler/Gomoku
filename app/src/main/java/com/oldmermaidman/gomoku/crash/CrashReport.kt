package com.oldmermaidman.gomoku.crash

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns a crash into the text of the journal.
 *
 * Nothing here touches Android: the caller passes the facts it can gather (version,
 * device, thread) and the zone, which keeps the format testable and lets a report
 * be written from a crashing thread without going through the framework.
 */
object CrashReport {

    /** A report is cut off at this length: a very deep stack is not worth the storage. */
    const val MAX_CHARS = 32_000

    private const val TRUNCATED = "\n… report truncated\n"

    /**
     * The whole report: a short header, then the stack trace of [error] including
     * every cause.
     */
    fun format(
        error: Throwable,
        timeMs: Long,
        versionName: String,
        device: String,
        threadName: String,
        zone: TimeZone = TimeZone.getDefault()
    ): String {
        val text = buildString {
            append("time: ").append(timestamp(timeMs, zone)).append('\n')
            append("app: ").append(versionName).append('\n')
            append("device: ").append(device).append('\n')
            append("thread: ").append(threadName).append('\n')
            append('\n')
            append(stackTrace(error))
        }
        return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS) + TRUNCATED
    }

    /** Local time of the crash, `yyyy-MM-dd HH:mm:ss Z`. */
    fun timestamp(timeMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
            .apply { timeZone = zone }
            .format(Date(timeMs))

    private fun stackTrace(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString()
    }
}
