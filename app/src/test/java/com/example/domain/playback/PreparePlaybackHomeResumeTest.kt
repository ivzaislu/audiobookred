package com.example.domain.playback

import com.example.data.model.BookDetailDto
import com.example.data.model.ChapterDto
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PreparePlaybackHomeResumeTest {
    private val cachedBook = BookDetailDto(
        id = "audiopolka:42",
        title = "Книга",
        selectedSource = "audiopolka",
        chapters = listOf(
            ChapterDto(
                id = "chapter-1",
                position = 0,
                title = "Глава 1",
                durationSeconds = 120L,
                streamUrl = "https://example.test/1.mp3",
            )
        ),
    )

    @Test
    fun fullCachedDetailRemainsEligibleForOfflineResume() {
        assertSame(cachedBook, cachedResumeBook(cachedBook, "audiopolka"))
        assertSame(cachedBook, cachedResumeBook(cachedBook, null))
    }

    @Test
    fun incompleteOrWrongSourceCacheIsRejected() {
        assertNull(cachedResumeBook(cachedBook.copy(chapters = emptyList()), "audiopolka"))
        assertNull(cachedResumeBook(cachedBook, "uknig"))
    }
}
