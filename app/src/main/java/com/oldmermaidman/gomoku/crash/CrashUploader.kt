package com.oldmermaidman.gomoku.crash

import android.content.Context
import android.os.Build
import com.oldmermaidman.gomoku.BuildConfig
import com.oldmermaidman.gomoku.GomokuApp
import com.oldmermaidman.gomoku.net.ApiException
import com.oldmermaidman.gomoku.net.dto.CrashReportRequest
import com.oldmermaidman.gomoku.prefs.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Sends the crash journal to the backend (FEATURES.md, B4).
 *
 * The journal stays local until the player allows sending, and even then it goes out on
 * the next launch — a crash report cannot be sent by the process that just died. What
 * the server accepts is deleted from the device; a failed send keeps the file for the
 * next attempt.
 */
object CrashUploader {

    /** Sends every stored report; [onDone] gets how many the server accepted. */
    fun sendPending(
        context: Context,
        store: CrashStore = CrashStore.inFilesDir(context.filesDir),
        onDone: (Int) -> Unit = {},
    ) {
        if (!AppSettings(context).sendCrashReports) return
        val files = store.list()
        if (files.isEmpty()) return
        val app = context.applicationContext as GomokuApp

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var sent = 0
            for (file in files) {
                val text = runCatching { file.readText() }.getOrNull() ?: continue
                try {
                    app.net.api.sendCrash(
                        CrashReportRequest(
                            appVersion = BuildConfig.VERSION_NAME,
                            versionCode = BuildConfig.VERSION_CODE,
                            device = "${Build.MANUFACTURER} ${Build.MODEL}",
                            android = Build.VERSION.RELEASE,
                            locale = Locale.getDefault().toString(),
                            stacktrace = text,
                        ),
                    )
                    file.delete()
                    sent++
                } catch (e: ApiException) {
                    // No network, or the server refused: keep the journal for next launch.
                    break
                }
            }
            if (sent > 0) {
                withContext(Dispatchers.Main) { onDone(sent) }
            }
        }
    }
}