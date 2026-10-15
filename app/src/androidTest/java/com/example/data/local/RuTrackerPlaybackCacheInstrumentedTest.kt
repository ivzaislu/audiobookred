package com.example.data.local

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.backup.UserDataRestoreGate
import com.example.data.model.BookDetailDto
import com.example.data.model.ChapterDto
import com.example.data.model.ProgressResponse
import com.example.data.model.SourceVariantDto
import com.example.data.player.PlaybackBookRecoveryRepository
import com.example.data.player.PlaybackResumeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RuTrackerPlaybackCacheInstrumentedTest {
    private lateinit var context: Context
    private lateinit var store: LocalCacheStore
    private val topic = BookDetailDto(
        id = "rutracker:123", title = "Book", selectedSource = "rutracker",
        selectedBookSourceId = "live:rutracker:123",
        sourceVariants = listOf(SourceVariantDto(
            "live:rutracker:123", "rutracker", "RuTracker", magnetUri = "magnet:?xt=urn:btih:hash",
        )),
    )
    private val playlist = topic.copy(chapters = List(7) { index -> ChapterDto(
        id = "rutracker:123:torrserve:hash:${index + 1}", position = index,
        title = "Chapter ${index + 1}", streamUrl = "http://server/stream?index=${index + 1}",
    ) })

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        resetAbredDatabaseSingleton()
        context.deleteDatabase(DB_NAME)
        UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
        store = LocalCacheStore(context)
    }

    @After
    fun tearDown() {
        UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
        resetAbredDatabaseSingleton()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun playlistThenProactiveMetadataRefreshThenControllerRecoveryKeepsSevenChapters() = runBlocking {
        store.writeBook(playlist)
        val progress = ProgressResponse(
            bookId = topic.id, bookSourceId = topic.selectedBookSourceId,
            chapterId = playlist.chapters.last().id, chapterIndex = 6, positionMs = 42_000L,
        )
        store.writeProgress(topic.id, topic.selectedSource, progress)
        store.writeBook(topic.copy(title = "Refreshed title"))

        val recovery = PlaybackBookRecoveryRepository(store, PlaybackResumeStore(context))
        val recovered = requireNotNull(recovery.recoverBook(topic.id, topic.selectedSource))
        assertEquals(playlist.chapters, recovered.chapters)
        assertEquals(7, recovered.chapters.size)
        assertEquals("Refreshed title", recovered.title)
        assertEquals(playlist.chapters, store.readBook(topic.id)?.chapters)
        assertEquals(progress, store.readProgress(topic.id, topic.selectedSource))
    }

    @Test
    fun concurrentResolvedAndMetadataWritesCannotErasePlaylist() = runBlocking {
        coroutineScope {
            listOf(
                async(Dispatchers.IO) { store.writeBook(playlist) },
                async(Dispatchers.IO) { store.writeBook(topic) },
            ).awaitAll()
        }
        assertEquals(playlist.chapters, store.readBook(topic.id, topic.selectedSource)?.chapters)
    }

    @Test
    fun changedMagnetInvalidatesOldPlaylist() = runBlocking {
        store.writeBook(playlist)
        store.writeBook(topic.copy(sourceVariants = listOf(
            topic.sourceVariants.single().copy(magnetUri = "magnet:?xt=urn:btih:other"),
        )))
        assertEquals(emptyList<ChapterDto>(), store.readBook(topic.id, topic.selectedSource)?.chapters)
    }

    private companion object {
        const val DB_NAME = "abred-local-v1.db"
    }
}
