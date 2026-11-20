package com.oldmermaidman.gomoku.crash

import android.os.Build

/**
 * Writes every uncaught exception into the journal before the process dies, then
 * passes it on to the handler installed before — the system's one — so the crash is
 * still reported the usual way.
 *
 * Writing a report must never throw: a failure here would replace the original crash
 * with a confusing one, so every error is swallowed.
 */
class CrashHandler(
    private val store: CrashStore,
    private val versionName: String,
    private val previous: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            val now = System.currentTimeMillis()
            store.save(
                CrashReport.format(
                    error = error,
                    timeMs = now,
                    versionName = versionName,
                    device = deviceName(),
                    threadName = thread.name
                ),
                now
            )
        } catch (ignored: Throwable) {
            // Never mask the original crash with a failure of the journal.
        }
        previous?.uncaughtException(thread, error)
    }

    private fun deviceName(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT})"
}
