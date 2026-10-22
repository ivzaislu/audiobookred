package com.example.data.textbook

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextLibraryPersistenceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun open(name: String) = Room.databaseBuilder(context, TextLibraryDatabase::class.java, name).build()

    private suspend fun seed(database: TextLibraryDatabase, bookId: String) {
        val dao = database.library()
        database.withTransaction {
            dao.insertBook(TextBookEntity(bookId, "Книга", "Книга.txt", "$bookId.txt", "hash", "UTF-8", 1, 1, 1, 5, 1, 1))
            dao.insertChapters(listOf(TextChapterEntity("$bookId:c0", bookId, 0, "Глава 1", 0, 5, false)))
            dao.insertFragments(listOf(TextFragmentEntity("$bookId:c0:f0", "$bookId:c0", 0, 0, 5, "Текст")))
        }
    }

    @Test fun bookChaptersFragmentsAndPositionSurviveDatabaseReopen() = runBlocking {
        val name = "text-test-${UUID.randomUUID()}.db"
        var database = open(name)
        try {
            seed(database, "first")
            database.library().putPosition(TextReadingPositionEntity("first", "first:c0:f0", 42, 9))
            database.close()
            database = open(name)
            assertEquals("Книга", database.library().book("first")?.title)
            assertEquals(1, database.library().chapters("first").size)
            assertEquals("Текст", database.library().fragments("first:c0").single().text)
            assertEquals(42, database.library().position("first")?.scrollOffsetPx)
            assertEquals("first:c0", database.library().chapterForFragment("first", "first:c0:f0"))
            assertNull(database.library().chapterForFragment("different-book", "first:c0:f0"))
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun interruptedImportRollsBackAndRemovalCascadesOnlyForItsBook() = runBlocking {
        val name = "text-test-${UUID.randomUUID()}.db"
        val database = open(name)
        try {
            try {
                database.withTransaction { seed(database, "cancelled"); throw IllegalStateException("simulated failure") }
                fail("Transaction should fail")
            } catch (_: IllegalStateException) { }
            assertNull(database.library().book("cancelled"))
            assertTrue(database.library().fragments("cancelled:c0").isEmpty())
            seed(database, "first"); seed(database, "second")
            database.library().putPosition(TextReadingPositionEntity("first", "first:c0:f0", 1, 9))
            database.library().delete("first")
            assertTrue(database.library().chapters("first").isEmpty())
            assertTrue(database.library().fragments("first:c0").isEmpty())
            assertNull(database.library().position("first"))
            assertNotNull(database.library().book("second"))
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
