package com.oldmermaidman.gomoku.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Tests for the journal on disk: ordering, the size cap and clearing. */
class CrashStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(max: Int = CrashStore.MAX_REPORTS) = CrashStore(folder.root, max)

    @Test
    fun aReportIsWrittenAndReadBack() {
        val store = store()

        store.save("first", 1_000)

        assertEquals(1, store.count())
        assertEquals("first", store.readAll())
    }

    @Test
    fun reportsAreListedNewestFirst() {
        val store = store()
        store.save("old", 1_000)
        store.save("new", 2_000)

        assertEquals(listOf("crash-2000.txt", "crash-1000.txt"), store.list().map { it.name })
        assertTrue(store.readAll().startsWith("new"))
        assertTrue(store.readAll().endsWith("old"))
    }

    @Test
    fun twoCrashesInTheSameMillisecondAreBothKept() {
        val store = store()

        val first = store.save("one", 5_000)
        val second = store.save("two", 5_000)

        assertNotEquals(first.name, second.name)
        assertEquals(2, store.count())
        assertTrue(store.readAll().contains("one"))
        assertTrue(store.readAll().contains("two"))
    }

    @Test
    fun onlyTheNewestReportsSurvive() {
        val store = store(max = 3)
        for (i in 1..6) store.save("report $i", i * 1_000L)

        assertEquals(3, store.count())
        assertEquals(
            listOf("crash-6000.txt", "crash-5000.txt", "crash-4000.txt"),
            store.list().map { it.name }
        )
    }

    @Test
    fun clearingRemovesEveryReport() {
        val store = store()
        store.save("a", 1_000)
        store.save("b", 2_000)

        assertEquals(2, store.clear())

        assertEquals(0, store.count())
        assertEquals("", store.readAll())
    }

    @Test
    fun foreignFilesInTheDirectoryAreLeftAlone() {
        val store = store()
        store.save("crash", 1_000)
        val stray = File(folder.root, "notes.txt").apply { writeText("keep me") }

        assertEquals(1, store.count())
        store.clear()

        assertTrue("a file that is not a report must survive", stray.exists())
    }

    @Test
    fun anEmptyJournalReadsAsEmptyText() {
        val store = store()

        assertEquals(0, store.count())
        assertEquals("", store.readAll())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun theDirectoryIsCreatedOnDemand() {
        val store = CrashStore(File(folder.root, "nested/crashes"))

        store.save("report", 1_000)

        assertEquals(1, store.count())
        assertFalse(store.readAll().isEmpty())
    }
}
