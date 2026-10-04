package com.example.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.backup.UserDataRestoreGate
import com.example.data.model.ProgressResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LocalCacheStoreRestoreRaceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        resetAbredDatabaseSingleton()
        context.deleteDatabase(DB_NAME)
        UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
    }

    @After
    fun tearDown() {
        UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
        resetAbredDatabaseSingleton()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun queuedProgressWriteCannotCrossRestoreGate() = runBlocking {
        val database = AbredDatabase.get(context)
        val store = LocalCacheStore(context)
        val transactionStarted = CompletableDeferred<Unit>()
        val releaseTransaction = CompletableDeferred<Unit>()

        val blocker = async(Dispatchers.IO) {
            database.withTransaction {
                transactionStarted.complete(Unit)
                releaseTransaction.await()
            }
        }
        transactionStarted.await()

        // UNDISPATCHED makes the writer run synchronously until Room suspends it
        // behind the transaction above. With the old implementation this point
        // was reached only after the restore gate had already been checked.
        val writer = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            store.writeProgress(
                bookId = BOOK_ID,
                sourceCode = SOURCE_CODE,
                value = ProgressResponse(
                    bookId = BOOK_ID,
                    chapterId = "chapter-2",
                    chapterIndex = 1,
                    positionMs = 42_000L,
                    playbackSpeed = 1.25,
                ),
            )
        }
        assertFalse(writer.isCompleted)

        UserDataRestoreGate.blockPlaybackWrites()
        releaseTransaction.complete(Unit)

        blocker.await()
        writer.await()

        assertNull(store.readProgress(BOOK_ID, SOURCE_CODE))
        assertNull(database.localCatalog().retention(BOOK_ID))
    }

    private companion object {
        const val DB_NAME = "abred-local-v1.db"
        const val BOOK_ID = "restore-race-book"
        const val SOURCE_CODE = "restore-race-source"
    }
}
