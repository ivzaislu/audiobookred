package com.example.data.repository

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.AbredDatabase
import com.example.data.local.LibraryCacheStore
import com.example.data.local.LocalCacheStore
import com.example.data.local.NormalizedLibraryStore
import com.example.data.local.resetAbredDatabaseSingleton
import com.example.data.model.AudioSeriesBriefDto
import com.example.data.model.BookDetailDto
import com.example.data.player.PlaybackResumeStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PlaybackLibraryRepositoryInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        resetAbredDatabaseSingleton()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        resetAbredDatabaseSingleton()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun playbackStartWritesHistoryAndListenedSeriesToNormalizedRoom() = runBlocking {
        val cacheStore = LocalCacheStore(context)
        val normalizedStore = NormalizedLibraryStore(context)
        val libraryStore = LibraryCacheStore(
            context = context,
            cacheStore = cacheStore,
            normalizedStore = normalizedStore,
        )
        val repository = PlaybackLibraryRepository(
            cacheStore = cacheStore,
            libraryStore = libraryStore,
            resumeStore = PlaybackResumeStore(context),
        )
        val book = BookDetailDto(
            id = "playback-book",
            title = "Playback Book",
            audioSeries = listOf(
                AudioSeriesBriefDto(
                    id = "series-source",
                    name = "Cycle",
                    position = 2.0,
                    provider = "provider",
                    externalId = "cycle-42",
                    sourceName = "Source",
                )
            ),
        )

        repository.recordPlaybackStarted(book, chapterIndex = 0)

        val snapshot = normalizedStore.read().library
        assertEquals(listOf("playback-book"), snapshot.history.map { it.id })
        assertEquals(listOf("series-source"), snapshot.series.map { it.id })
        assertEquals("playback-book", snapshot.series.single().currentBook?.book?.id)
        assertEquals(2.0, snapshot.series.single().currentBook?.position ?: error("position missing"), 0.0)

        // Live playback writes never create the retired whole-library payload.
        assertNull(AbredDatabase.get(context).cachedPayloads().get(LIBRARY_KEY))
    }

    private companion object {
        const val DB_NAME = "abred-local-v1.db"
        const val LIBRARY_KEY = "library:v1"
    }
}
