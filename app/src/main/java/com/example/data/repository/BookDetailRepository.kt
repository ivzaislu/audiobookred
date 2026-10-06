package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.AbredDatabase
import com.example.data.local.DownloadBookEntity
import com.example.data.local.DownloadStore
import com.example.data.local.LocalCacheStore
import com.example.data.model.BookDetailDto
import com.example.data.model.BookmarkDto
import com.example.data.model.ChapterDto
import com.example.data.model.SeriesDetailDto
import com.example.data.model.SourceVariantDto
import com.example.data.parser.AndroidLiveParserLocator
import com.example.data.player.PlaybackResumeStore
import com.example.data.player.estimateOverallProgressPercent
import com.example.data.player.playbackSourceMatches
import com.example.data.settings.BookSourcePreferenceStore
import com.example.util.runCatchingCancellable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class BookDetailSnapshot(
    val book: BookDetailDto,
    val audioSeries: SeriesDetailDto? = null,
    val bookmarks: List<BookmarkDto> = emptyList(),
    val selectedDownload: DownloadBookEntity? = null,
)

/** Fresh book/source content comes only from the on-device parsers in selfapk. */
private class BookDetailRemoteDataSource {
    suspend fun book(bookId: String, source: String? = null): BookDetailDto {
        val local = AndroidLiveParserLocator.instanceOrNull()
            ?: error("Локальные парсеры ещё не инициализированы")
        require(local.canParseBook(bookId, source)) {
            "Книга $bookId недоступна локальным парсерам"
        }
        return local.book(bookId).also {
            Log.i(TAG, "live_parser=android operation=detail book_id=$bookId")
        }
    }

    suspend fun sourceSeries(bookId: String, provider: String? = null): SeriesDetailDto {
        val local = AndroidLiveParserLocator.instanceOrNull()
            ?: error("Локальные парсеры ещё не инициализированы")
        require(local.canLoadSourceSeries(bookId, provider)) {
            "Цикл для $bookId недоступен локальному parser-у"
        }
        return local.sourceSeries(bookId, provider, page = 1, limit = 30).also {
            Log.i(TAG, "live_parser=android operation=source_series book_id=$bookId")
        }
    }

    private companion object {
        const val TAG = "AbredLiveParser"
    }
}

/**
 * Book Detail keeps content refresh separate from user-owned local state.
 * Favorites, bookmarks, selected-source preference and resume progress are
 * authoritative on this installation. There is no backend fallback or API dependency.
 */
@Singleton
class BookDetailRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val cacheStore: LocalCacheStore,
    private val downloadStore: DownloadStore,
    private val sourcePreferenceStore: BookSourcePreferenceStore,
    private val resumeStore: PlaybackResumeStore,
) {
    private val database = AbredDatabase.get(context.applicationContext)
    private val remote = BookDetailRemoteDataSource()

    fun observe(routeId: String, downloaded: Boolean): Flow<BookDetailSnapshot?> =
        database.invalidationTracker
            .createFlow(
                "cached_payloads",
                "local_books",
                "book_retention",
                "library_favorites",
                "download_books",
                "download_files",
                emitInitialState = true,
            )
            .map { cachedSnapshot(routeId, downloaded) }

    suspend fun refresh(bookId: String): String {
        val preferred = resolvePreferredSource(bookId)
        val rawBook = if (preferred == null) {
            remote.book(bookId)
        } else {
            runCatchingCancellable { remote.book(bookId, preferred) }
                .getOrElse {
                    sourcePreferenceStore.clear(bookId)
                    remote.book(bookId)
                }
        }
        val book = applyEffectiveState(preserveKnownCover(rawBook))
        cacheStore.writeBook(book)
        if (book.selectedSource.isNotBlank()) {
            sourcePreferenceStore.set(book.id, book.selectedSource)
        }

        runCatchingCancellable {
            remote.sourceSeries(book.id, book.selectedSource.takeIf { it.isNotBlank() })
        }.onSuccess { series ->
            cacheStore.writeSeries(
                LocalCacheStore.sourceSeriesKey(book.id, book.selectedSource.takeIf { it.isNotBlank() }),
                series,
            )
        }
        return book.id
    }

    suspend fun selectSource(bookId: String, sourceCode: String) {
        if (bookId.isBlank() || sourceCode.isBlank()) return

        val cached = cacheStore.readBook(bookId, sourceCode)
            ?.takeIf { playbackSourceMatches(it.selectedSource, sourceCode) }
        if (cached != null) {
            sourcePreferenceStore.set(bookId, sourceCode)
        }

        val freshResult = runCatchingCancellable { remote.book(bookId, sourceCode) }
        val rawBook = freshResult.getOrNull()
        if (rawBook == null) {
            if (cached == null) throw freshResult.exceptionOrNull() ?: error("Не удалось сменить источник")
            return
        }

        val book = applyEffectiveState(preserveKnownCover(rawBook))
        cacheStore.writeBook(book)
        sourcePreferenceStore.set(book.id, sourceCode)

        runCatchingCancellable { remote.sourceSeries(book.id, sourceCode) }
            .onSuccess { series ->
                cacheStore.writeSeries(LocalCacheStore.sourceSeriesKey(book.id, sourceCode), series)
            }
    }

    suspend fun toggleFavorite(book: BookDetailDto) {
        // Persist the fresh detail first; the atomic point mutation below then
        // applies the authoritative favorite flag to every cached detail row.
        cacheStore.writeBook(book)
        cacheStore.toggleFavoriteState(book.id, book.asCard())
    }

    suspend fun removeBookmark(bookId: String, bookmarkId: String) {
        val bookmark = cacheStore.readBookmarks(bookId)
            .orEmpty()
            .firstOrNull { it.id == bookmarkId }
            ?: cacheStore.readAllBookmarks().firstOrNull { it.id == bookmarkId }
            ?: return
        cacheStore.removeBookmarkState(bookmark.id, bookmark.bookId)
    }

    suspend fun downloadedBook(bookSourceId: String): BookDetailDto =
        downloadedBookDetail(bookSourceId)

    private suspend fun cachedSnapshot(routeId: String, downloaded: Boolean): BookDetailSnapshot? {
        val rawBook = if (downloaded) {
            runCatchingCancellable { downloadedBookDetail(routeId) }.getOrNull()
        } else {
            val preferred = sourcePreferenceStore.get(routeId)
            cacheStore.readBook(routeId, preferred)
        } ?: return null

        val book = applyEffectiveState(rawBook)
        val bookmarks = cacheStore.readBookmarks(book.id).orEmpty()
        val series = cacheStore.readSeries(
            LocalCacheStore.sourceSeriesKey(book.id, book.selectedSource.takeIf { it.isNotBlank() })
        )
        val selectedDownload = book.selectedBookSourceId
            .takeIf { it.isNotBlank() }
            ?.let { downloadStore.book(it) }
        return BookDetailSnapshot(
            book = book,
            audioSeries = series,
            bookmarks = bookmarks,
            selectedDownload = selectedDownload,
        )
    }

    private suspend fun preserveKnownCover(book: BookDetailDto): BookDetailDto {
        if (book.coverUrl.isNotBlank()) return book
        val knownCover = cacheStore.readBook(book.id)
            ?.coverUrl
            ?.takeIf { it.isNotBlank() }
            ?: cacheStore.readBookCard(book.id)
                ?.coverUrl
                ?.takeIf { it.isNotBlank() }
        return knownCover?.let { book.copy(coverUrl = it) } ?: book
    }

    private fun resolvePreferredSource(bookId: String): String? =
        sourcePreferenceStore.get(bookId)?.takeIf { it.isNotBlank() }

    private suspend fun applyEffectiveState(book: BookDetailDto): BookDetailDto {
        val favorite = cacheStore.isFavoriteState(book.id)
        val resume = resumeStore.get(book.id, book.selectedSource)
        val localPercent = resume?.let { snapshot ->
            snapshot.progressPercent.takeIf { it > 0.0 }
                ?: snapshot.positionMs.takeIf { it > 0L }?.let { positionMs ->
                    val exactIndex = snapshot.chapterId
                        ?.let { chapterId -> book.chapters.indexOfFirst { it.id == chapterId } }
                        ?: -1
                    val chapterIndex = if (exactIndex >= 0) {
                        exactIndex
                    } else {
                        snapshot.chapterIndex.coerceIn(0, book.chapters.lastIndex.coerceAtLeast(0))
                    }
                    val currentDurationMs = book.chapters.getOrNull(chapterIndex)
                        ?.durationSeconds
                        ?.coerceAtLeast(0L)
                        ?.times(1_000L)
                        ?: 0L
                    estimateOverallProgressPercent(
                        book = book,
                        chapterIndex = chapterIndex,
                        positionMs = positionMs,
                        currentDurationMs = currentDurationMs,
                    ).takeIf { it > 0.0 }?.also { repairedPercent ->
                        resumeStore.updateProgressPercent(
                            bookId = snapshot.bookId,
                            sourceCode = snapshot.sourceCode,
                            progressPercent = repairedPercent,
                        )
                    }
                }
        }
        val progress = localPercent ?: book.progressPercent
        return book.copy(
            isFavorite = favorite,
            progressPercent = progress,
        )
    }

    private suspend fun downloadedBookDetail(bookSourceId: String): BookDetailDto {
        val stored = downloadStore.book(bookSourceId) ?: error("Загрузка не найдена")
        cacheStore.readBook(stored.bookId, stored.sourceCode)?.let { cached ->
            if (cached.selectedBookSourceId == bookSourceId && cached.chapters.isNotEmpty()) return cached
        }
        require(stored.state == "completed") {
            "Для незавершённой загрузки нужен сохранённый локальный кэш книги"
        }
        val files = downloadStore.files(bookSourceId)
            .sortedWith(compareBy({ it.chapterPosition }, { it.fileId }))
        require(files.isNotEmpty()) { "У скачанной книги нет сохранённых глав" }
        val duration = files.sumOf { it.durationSeconds.coerceAtLeast(0L) }
        return BookDetailDto(
            id = stored.bookId,
            title = stored.title,
            coverUrl = stored.coverUrl,
            durationSeconds = duration,
            sourceCodes = listOf(stored.sourceCode),
            primarySource = stored.sourceCode,
            selectedSource = stored.sourceCode,
            selectedBookSourceId = stored.bookSourceId,
            sourceVariants = listOf(
                SourceVariantDto(
                    bookSourceId = stored.bookSourceId,
                    sourceCode = stored.sourceCode,
                    sourceName = stored.sourceName,
                )
            ),
            chapters = files.map { file ->
                ChapterDto(
                    id = file.chapterId,
                    position = file.chapterPosition,
                    title = file.title,
                    durationSeconds = file.durationSeconds,
                    streamUrl = "",
                )
            },
        )
    }
}
