package com.example.data.narration

import com.example.data.model.BookDetailDto
import com.example.data.textbook.TextLibraryRepository
import com.example.data.textbook.TextNarrationBook
import com.example.data.textbook.TextFragmentEntity
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One source book and one chapter map in memory; never query Room on each playback tick. */
class TextPlaybackTracker @Inject internal constructor(
    private val books: TextLibraryRepository,
    private val maps: TextAudioSyncStore,
) {
    private val mutex = Mutex()
    private var audioIdentity: BookDetailDto? = null
    private var cachedBook: TextNarrationBook? = null
    private var failedBookId: String? = null
    private var chapters: Map<String, List<TextFragmentEntity>> = emptyMap()
    private var mapKey: Pair<String, String>? = null
    private var map: TextAudioSyncMap? = null

    internal suspend fun resolve(book: BookDetailDto?, index: Int, positionMs: Long, durationMs: Long): TextPlaybackHighlight? = mutex.withLock {
        val bookId = when (book?.selectedSource) {
            TEXT_NARRATION_SOURCE -> book.id
            TEXT_AUDIO_SOURCE -> Regex("^text_audio:(txt_[0-9a-f]{64}):").find(book.selectedBookSourceId)?.groupValues?.get(1)
            else -> null
        }
        if (bookId == null || book == null) {
            audioIdentity = null; cachedBook = null; failedBookId = null; chapters = emptyMap(); mapKey = null; map = null
            return@withLock null
        }
        if (book !== audioIdentity) { audioIdentity = book; mapKey = null; map = null; failedBookId = null }
        val audioChapter = book.chapters.getOrNull(index) ?: return@withLock null
        try {
            if (cachedBook?.book?.id != bookId) {
                if (failedBookId == bookId) return@withLock null
                cachedBook = null; chapters = emptyMap(); mapKey = null; map = null
                val text = books.narrationBook(bookId)
                cachedBook = text
                chapters = text.fragments.groupBy { it.chapterId }
                failedBookId = null
            }
            val liveId = if (book.selectedSource == TEXT_NARRATION_SOURCE) textFragmentId(audioChapter.id) else null
            val chapterId = if (liveId != null) textChapterId(audioChapter.id) else audioChapter.id
            val fragments = chapters[chapterId] ?: return@withLock null
            val key = book.selectedBookSourceId to chapterId
            if (mapKey != key) {
                val next = if (liveId == null) maps.read(book.selectedBookSourceId, chapterId, fragments.map { it.id },
                    bookId, cachedBook?.book?.sourceSha256) else null
                map = next; mapKey = key
            }
            resolveTextPlaybackHighlight(bookId, chapterId, fragments, liveId, positionMs,
                durationMs.takeIf { it > 0 } ?: audioChapter.durationSeconds * 1000L, map)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failedBookId = bookId; null }
    }
}
