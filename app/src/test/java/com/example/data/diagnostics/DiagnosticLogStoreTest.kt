package com.example.data.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rotationRetainsRecentEventsWithinTwoSegments() {
        val store = DiagnosticLogStore(temporary.newFolder(), segmentLimit = 128)
        repeat(100) { store.append(it.toLong(), "event=timeline value=$it") }
        assertTrue(store.logBytes() <= 256)
        assertTrue(store.logs().contains("value=99"))
        assertFalse(store.logs().contains("value=0\n"))
    }

    @Test fun concurrentWritersKeepCompleteBoundedLines() {
        val store = DiagnosticLogStore(temporary.newFolder(), segmentLimit = 1024)
        val writers = List(4) { worker -> Thread {
            repeat(100) { store.append(it.toLong(), "event=timeline value=$worker") }
        }.apply { start() } }
        writers.forEach { it.join() }
        assertTrue(store.logBytes() <= 2048)
        assertTrue(store.logs().lineSequence().filter(String::isNotEmpty).all {
            Regex("[0-9]+ event=timeline value=[0-3]").matches(it)
        })
    }

    @Test fun oversizedEntryCannotGrowStorage() {
        val store = DiagnosticLogStore(temporary.newFolder(), segmentLimit = 128)
        store.append(1, "x".repeat(129))
        store.append(1, "я".repeat(100))
        assertEquals(0L, store.logBytes())
    }

    @Test fun clearRemovesLogsCrashAndRecoveryFiles() {
        val folder = temporary.newFolder()
        val store = DiagnosticLogStore(folder)
        store.append(1, "event=timeline")
        store.crashFile.writeText("crash")
        java.io.File(folder, "last-crash.txt.bak").writeText("backup")
        store.clear()
        assertEquals("", store.logs())
        assertEquals(0L, store.logBytes())
        assertFalse(store.crashFile.exists())
        assertTrue(folder.listFiles()!!.isEmpty())
    }
}
