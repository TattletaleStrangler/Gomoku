package com.oldmermaidman.gomoku

import android.app.Application
import com.oldmermaidman.gomoku.crash.CrashHandler
import com.oldmermaidman.gomoku.crash.CrashStore
import com.oldmermaidman.gomoku.net.NetContainer

/**
 * Installs the crash journal before any other component runs (see README.md,
 * "Crash journal"), and owns the one network layer of the process.
 *
 * The journal is a directory of text files in the app's own private storage: it
 * needs no permission, nothing is sent anywhere by itself, and the player decides
 * whether to share a report from the settings screen.
 *
 * [net] is lazy: an offline game never touches it, so the app still starts with no
 * network stack until the player opens the online section.
 */
class GomokuApp : Application() {

    /** The REST client, the token store and the socket factory, shared by all screens. */
    val net: NetContainer by lazy { NetContainer(this) }

    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        Thread.setDefaultUncaughtExceptionHandler(
            CrashHandler(CrashStore.inFilesDir(filesDir), version, previous)
        )
    }
}
