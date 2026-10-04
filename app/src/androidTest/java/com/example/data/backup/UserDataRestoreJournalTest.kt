package com.example.data.backup

import androidx.test.platform.app.InstrumentationRegistry
import com.example.BuildConfig
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UserDataRestoreJournalTest {
    private lateinit var file: File
    private lateinit var journal: UserDataRestoreJournal

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "restore-journal-${UUID.randomUUID()}.json")
        journal = UserDataRestoreJournal(file)
    }

    @After
    fun tearDown() {
        journal.clear()
        file.delete()
        File("${file.path}.bak").delete()
        File("${file.path}.new").delete()
    }

    @Test
    fun journalPersistsPreparedAndTerminalStates() {
        val backup = UserDataBackup(
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            exportedAtMs = 123456789L,
            sourcePreferences = mapOf("book-1" to "source-a"),
        )

        assertFalse(journal.exists())
        assertTrue(journal.writePrepared(backup))
        assertTrue(journal.exists())

        val prepared = journal.read()
        assertNotNull(prepared)
        assertEquals(UserDataRestoreJournal.STATE_PREPARED, prepared?.state)
        assertEquals(backup.applicationId, prepared?.backup?.applicationId)
        assertEquals(backup.sourcePreferences, prepared?.backup?.sourcePreferences)

        assertTrue(journal.markCommitted(backup))
        assertEquals(UserDataRestoreJournal.STATE_COMMITTED, journal.read()?.state)

        assertTrue(journal.markAborted(backup))
        assertEquals(UserDataRestoreJournal.STATE_ABORTED, journal.read()?.state)

        assertTrue(journal.clear())
        assertFalse(journal.exists())
        assertEquals(null, journal.read())
    }
}
