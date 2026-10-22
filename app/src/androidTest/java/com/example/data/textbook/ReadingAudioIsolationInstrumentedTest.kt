package com.example.data.textbook

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.player.PlaybackResumeStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real repository write boundary and persisted Room / audio preferences. */
@RunWith(AndroidJUnit4::class)
class ReadingAudioIsolationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun open(name: String) = Room.databaseBuilder(context, TextLibraryDatabase::class.java, name).build()

    private suspend fun seed(db: TextLibraryDatabase) {
        db.library().insertBook(TextBookEntity("book", "Книга", "book.txt", "book.source", "hash", "UTF-8", 1, 1, 1, 20, 2, 2))
        db.library().insertChapters(listOf(
            TextChapterEntity("book:c0", "book", 0, "Первая", 0, 10, false),
            TextChapterEntity("book:c1", "book", 1, "Вторая", 10, 20, false),
        ))
        db.library().insertFragments(listOf(
            TextFragmentEntity("book:c0:f0", "book:c0", 0, 0, 10, "Начало\n"),
            TextFragmentEntity("book:c1:f0", "book:c1", 0, 10, 20, "Конец\n"),
        ))
        db.library().putPosition(TextReadingPositionEntity("book", "book:c0:f0", 43, 1))
    }

    @Test fun previewScrollChapterPauseDisposeAndReopenPreserveBothBookmarks() = runBlocking {
        val name = "sync-${UUID.randomUUID()}.db"
        val prefs = "sync-audio-${UUID.randomUUID()}"
        var db = open(name)
        try {
            seed(db)
            val audio = PlaybackResumeStore(context, prefs)
            audio.saveImmediate("audio_book", "text_audio", "book:c1", 1, 50_000, 1.25f)
            val audioBefore = audio.get("audio_book", "text_audio")
            val reader = TextLibraryRepository(context, db)
            val session = TextReaderSession()
            val old = session.open(false)
            val preview = session.open(true)
            assertEquals("book:c1", reader.open("book", "book:c1").chapter.id)
            repeat(4) {
                assertFalse(reader.savePosition("book", "book:c1:f0", 52) { session.canSave(preview) })
                assertFalse(reader.savePosition("book", "book:c1:f0", 52) { session.canSave(old) })
            }
            // Reopening a different chapter itself must be read-only too.
            reader.open("book", "book:c0")
            reader.open("book", "book:c1")
            session.close()
            db.close()
            db = open(name)
            val reopened = TextLibraryRepository(context, db).open("book")
            assertEquals("book:c0:f0", reopened.position?.fragmentId)
            assertEquals(43, reopened.position?.scrollOffsetPx)
            assertEquals(audioBefore, PlaybackResumeStore(context, prefs).get("audio_book", "text_audio"))
        } finally {
            db.close(); context.deleteDatabase(name)
            context.getSharedPreferences(prefs, 0).edit().clear().commit()
        }
    }

    @Test fun onlyExplicitCommitMovesReadingAndRollbackDoesNotWrite() = runBlocking {
        val name = "sync-commit-${UUID.randomUUID()}.db"
        val db = open(name)
        try {
            seed(db)
            val reader = TextLibraryRepository(context, db)
            val session = TextReaderSession()
            val preview = session.open(true)
            reader.open("book", "book:c1")
            assertEquals(43, reader.open("book").position?.scrollOffsetPx)
            assertTrue(session.beginCommit(preview))
            assertTrue(reader.savePosition("book", "book:c1:f0", 52) { session.canCommit(preview) })
            session.finishCommit(preview, true)
            assertEquals("book:c1:f0", reader.open("book").position?.fragmentId)
            assertEquals(52, reader.open("book").position?.scrollOffsetPx)
            assertFalse(reader.savePosition("book", "book:c0:f0", 99) { session.canSave(preview) })
            assertEquals(52, reader.open("book").position?.scrollOffsetPx)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun openingABookWithoutBookmarkDoesNotCreateOne() = runBlocking {
        val name = "sync-open-${UUID.randomUUID()}.db"
        val db = open(name)
        try {
            seed(db)
            // Seed a second book with no reading position.
            db.library().insertBook(TextBookEntity("new", "Новая", "new.txt", "new.source", "new-hash", "UTF-8", 1, 1, 1, 5, 1, 1))
            db.library().insertChapters(listOf(TextChapterEntity("new:c0", "new", 0, "Глава", 0, 5, false)))
            db.library().insertFragments(listOf(TextFragmentEntity("new:c0:f0", "new:c0", 0, 0, 5, "Текст")))
            assertNotNull(TextLibraryRepository(context, db).open("new").position)
            assertNull(db.library().position("new"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
