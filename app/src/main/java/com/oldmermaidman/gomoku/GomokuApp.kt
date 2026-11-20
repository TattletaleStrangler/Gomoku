package com.oldmermaidman.gomoku

import android.app.Application
import com.oldmermaidman.gomoku.crash.CrashHandler
import com.oldmermaidman.gomoku.crash.CrashStore

/**
 * Installs the crash journal before any other component runs (see README.md,
 * "Crash journal").
 *
 * The journal is a directory of text files in the app's own private storage: it
 * needs no permission, nothing is sent anywhere by itself, and the player decides
 * whether to share a report from the settings screen.
 */
class GomokuApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        Thread.setDefaultUncaughtExceptionHandler(
            CrashHandler(CrashStore.inFilesDir(filesDir), version, previous)
        )
    }
}
