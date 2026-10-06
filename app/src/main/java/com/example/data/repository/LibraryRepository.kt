package com.example.data.repository

import com.example.data.local.DownloadBookEntity
import com.example.data.local.DownloadStore
import com.example.data.local.LibraryCacheStore
import com.example.data.local.ListeningStateStore
import com.example.data.local.LocalCacheStore
import com.example.data.model.BookCardDto
import com.example.data.model.BookDetailDto
import com.example.data.model.BookmarkUiItem
import com.example.data.model.MySeriesDto
import com.example.data.player.PlaybackResumeStore
import com.example.data.player.estimateOverallProgressPercent
import com.example.data.player.playbackSourceOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class LibrarySnapshot(
    val favorites: List<BookCardDto> = emptyList(),
    val bookmarks: List<BookmarkUiItem> = emptyList(),
    val history: List<BookCardDto> = emptyList(),
    val series: List<MySeriesDto> = emptyList(),
    val downloads: List<DownloadBookEntity> = emptyList(),
)

/**
 * Local library boundary for selfapk.
 *
 * Favorites, history, bookmarks, listened cycles and downloads are owned by the
 * installation. No backend snapshot or SyncStore overlay participates in reads.
 */
class LibraryRepository(
    private val local: LibraryCacheStore,
    private val cacheStore: LocalCacheStore,
    private val downloadStore: DownloadStore,
    private val resumeStore: PlaybackResumeStore,
    private val listeningStateStore: ListeningStateStore,
) {
    fun observe(): Flow<LibrarySnapshot> {
        val localSnapshots = local.observe()
            .map { localSnapshot -> buildSnapshot(localSnapshot, emptyList()) }
        return combine(
            localSnapshots,
            downloadStore.observeBooks(),
        ) { localSnapshot, downloads ->
            localSnapshot.copy(downloads = downloads)
        }
    }

    suspend fun recordPlaybackStarted(book: BookDetailDto) {
        val card = applyResumeProgress(listOf(book.asCard())).first()
        cacheStore.recordHistoryState(card)
    }

    suspend fun removeBookmark(bookmarkId: String) {
        val bookmark = cacheStore.readAllBookmarks().firstOrNull { it.id == bookmarkId } ?: return
        cacheStore.removeBookmarkState(bookmark.id, bookmark.bookId)
    }

    suspend fun removeHistory(bookId: String) {
        if (cacheStore.removeHistoryState(bookId)) {
            listeningStateStore.forgetBookProgress(bookId)
        }
    }

    /** Removing a cycle is local and immediate; no 24-hour trash/grace timer. */
    suspend fun removeSeries(series: MySeriesDto) {
        cacheStore.removeMySeriesState(series)
    }

    private suspend fun buildSnapshot(
        snapshot: LibraryCacheStore.Snapshot,
        downloads: List<DownloadBookEntity>,
    ): LibrarySnapshot {
        val library = snapshot.library
        return LibrarySnapshot(
            favorites = applyResumeProgress(
                library.favorites.map { it.copy(isFavorite = true) }
            ).distinctBy(BookCardDto::id),
            bookmarks = resolveBookmarks(snapshot.bookmarks),
            history = applyResumeProgress(library.history).distinctBy(BookCardDto::id),
            series = library.series
                .distinctBy { seriesKey(it) }
                .map(::applyResumeProgress),
            downloads = downloads,
        )
    }

    private suspend fun applyResumeProgress(items: List<BookCardDto>): List<BookCardDto> = items.map { book ->
        val checkpoint = resumeStore.get(book.id)
        val localPercent = checkpoint?.progressPercent?.takeIf { it > 0.0 }
            ?: checkpoint?.positionMs?.takeIf { it > 0L }?.let { positionMs ->
                val source = checkpoint.sourceCode.let(::playbackSourceOrNull)
                val detail = cacheStore.readBook(book.id, source)
                detail?.let { cachedBook ->
                    val exactIndex = checkpoint.chapterId
                        ?.let { chapterId -> cachedBook.chapters.indexOfFirst { it.id == chapterId } }
                        ?: -1
                    val chapterIndex = if (exactIndex >= 0) {
                        exactIndex
                    } else {
                        checkpoint.chapterIndex.coerceIn(0, cachedBook.chapters.lastIndex.coerceAtLeast(0))
                    }
                    val currentDurationMs = cachedBook.chapters.getOrNull(chapterIndex)
                        ?.durationSeconds
                        ?.coerceAtLeast(0L)
                        ?.times(1_000L)
                        ?: 0L
                    estimateOverallProgressPercent(
                        book = cachedBook,
                        chapterIndex = chapterIndex,
                        positionMs = positionMs,
                        currentDurationMs = currentDurationMs,
                    ).takeIf { it > 0.0 }?.also { repairedPercent ->
                        resumeStore.updateProgressPercent(
                            bookId = checkpoint.bookId,
                            sourceCode = checkpoint.sourceCode,
                            progressPercent = repairedPercent,
                        )
                    }
                }
            }
        when {
            localPercent != null -> book.copy(progressPercent = localPercent)
            else -> book
        }
    }

    private fun applyResumeProgress(series: MySeriesDto): MySeriesDto = series.copy(
        currentBook = series.currentBook?.let { current ->
            val book = localProgress(current.book)
            current.copy(book = book, progressPercent = book.progressPercent)
        },
        nextBook = series.nextBook?.let { next ->
            val book = localProgress(next.book)
            next.copy(book = book, progressPercent = book.progressPercent)
        },
    )

    private suspend fun resolveBookmarks(bookmarks: List<com.example.data.model.BookmarkDto>): List<BookmarkUiItem> = coroutineScope {
        val bookIds = bookmarks.map { it.bookId }.distinct()
        val books = bookIds.map { id ->
            async { id to cacheStore.readBook(id) }
        }.awaitAll().toMap()
        val cards = linkedMapOf<String, BookCardDto?>()
        for (id in bookIds) {
            cards[id] = books[id]?.asCard() ?: cacheStore.readBookCard(id)
        }
        bookmarks.map { bookmark ->
            val book = books[bookmark.bookId]
            val card = cards[bookmark.bookId]
            BookmarkUiItem(
                bookmark = bookmark,
                bookTitle = book?.title ?: card?.title ?: "Книга",
                chapterTitle = book?.chapters?.getOrNull(bookmark.chapterIndex)?.title
                    ?: "Глава ${bookmark.chapterIndex + 1}",
                coverUrl = book?.coverUrl ?: card?.coverUrl.orEmpty(),
            )
        }
    }

    private fun localProgress(card: BookCardDto): BookCardDto {
        val progress = resumeStore.get(card.id)?.progressPercent
            ?.takeIf { it > 0.0 }
            ?: card.progressPercent
        return card.copy(progressPercent = progress.coerceIn(0.0, 100.0))
    }

    private fun seriesKey(series: MySeriesDto): String = buildString {
        append(series.provider.lowercase())
        append(':')
        append(series.externalId.ifBlank { series.name.lowercase() })
    }
}
