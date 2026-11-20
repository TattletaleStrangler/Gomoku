package com.oldmermaidman.gomoku.crash

import java.io.File

/**
 * The crash journal on disk: one text file per crash inside [dir], newest first.
 *
 * The directory is passed in rather than looked up, so the store works on any
 * `File` and is covered by JVM tests. Only [maxReports] entries are kept — a crash
 * loop must not be able to fill the storage — and every write is UTF-8, so a device
 * name or a message in Cyrillic survives.
 */
class CrashStore(private val dir: File, private val maxReports: Int = MAX_REPORTS) {

    /** Saves [report] as a new entry and returns the file it was written to. */
    @Synchronized
    fun save(report: String, timeMs: Long): File {
        dir.mkdirs()

        // Two crashes in the same millisecond must not overwrite each other.
        var file = File(dir, "$PREFIX$timeMs$SUFFIX")
        var n = 1
        while (file.exists()) {
            file = File(dir, "$PREFIX$timeMs-$n$SUFFIX")
            n++
        }
        file.writeText(report)
        prune()
        return file
    }

    /** Stored reports, newest first. */
    @Synchronized
    fun list(): List<File> = entries().sortedWith(
        compareByDescending<File> { timestampOf(it) }.thenByDescending { it.name }
    )

    /** Every stored report as one text, newest first; empty when there are none. */
    @Synchronized
    fun readAll(): String =
        list().joinToString(SEPARATOR) { it.readText().trimEnd() }

    @Synchronized
    fun count(): Int = entries().size

    /** Empties the journal and returns how many reports were removed. */
    @Synchronized
    fun clear(): Int {
        val files = entries()
        files.forEach { it.delete() }
        return files.size
    }

    /** Crash files only: anything else in the directory is left alone. */
    private fun entries(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.toList()
            ?: emptyList()

    private fun prune() {
        list().drop(maxReports).forEach { it.delete() }
    }

    /** The `1699999999999` of `crash-1699999999999-1.txt`; 0 when unreadable. */
    private fun timestampOf(file: File): Long =
        file.name.removePrefix(PREFIX).substringBefore('-').substringBefore(SUFFIX).toLongOrNull() ?: 0L

    companion object {
        /** How many reports are kept before the oldest ones are dropped. */
        const val MAX_REPORTS = 10

        /** Name of the journal directory inside the app's private storage. */
        const val DIR_NAME = "crashes"

        private const val PREFIX = "crash-"
        private const val SUFFIX = ".txt"
        private const val SEPARATOR = "\n\n----- ----- -----\n\n"

        /** The journal inside [filesDir]; the only place the directory name is known. */
        fun inFilesDir(filesDir: File): CrashStore = CrashStore(File(filesDir, DIR_NAME))
    }
}
