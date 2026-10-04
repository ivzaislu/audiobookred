package com.example.data.repository

import com.example.data.local.LibraryCacheStore
import com.example.data.local.LocalCacheStore
import com.example.data.model.BookCardDto
import com.example.data.model.BookDetailDto
import com.example.data.model.BookmarkDto
import com.example.data.model.MySeriesDto
import com.example.data.model.SeriesProgressBookDto
import com.example.data.player.PlaybackResumeStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local library state derived from playback in selfapk.
 * History, bookmarks and listened source cycles are committed on-device and do
 * not enqueue legacy profile/backend synchronization.
 */
@Singleton
class PlaybackLibraryRepository @Inject constructor(
    private val cacheStore: LocalCacheStore,
    private val libraryStore: LibraryCacheStore,
    private val resumeStore: PlaybackResumeStore,
) {
    suspend fun recordPlaybackStarted(book: BookDetailDto, chapterIndex: Int) {
        recordLocalHistory(book)
        recordListenedSeries(book)
    }

    suspend fun addBookmark(
        book: BookDetailDto,
        chapterIndex: Int,
        positionMs: Long,
        note: String,
    ) {
        val bookmark = BookmarkDto(
            id = UUID.randomUUID().toString(),
            bookId = book.id,
            chapterId = book.chapters.getOrNull(chapterIndex)?.id,
            chapterIndex = chapterIndex,
            positionMs = positionMs,
            note = note,
        )
        cacheStore.upsertBookmarkState(bookmark)
    }

    private suspend fun recordLocalHistory(book: BookDetailDto) {
        cacheStore.recordHistoryState(localProgress(book.asCard()))
    }

    /**
     * A source cycle becomes one of "My cycles" as soon as playback of any
     * member actually starts. Existing membership is moved to the front and its
     * current book is refreshed instead of creating duplicates.
     */
    private suspend fun recordListenedSeries(book: BookDetailDto) {
        val memberships = book.audioSeries
            .filter { it.name.isNotBlank() && it.provider.isNotBlank() }
            .distinctBy { membership ->
                listOf(
                    membership.provider.lowercase(),
                    membership.externalId.ifBlank { membership.name.lowercase() },
                ).joinToString(":")
            }
        if (memberships.isEmpty()) return

        val librarySeries = libraryStore.read().library.series
        val updated = memberships.map { membership ->
                val existing = librarySeries.firstOrNull { series ->
                    series.provider.equals(membership.provider, ignoreCase = true) &&
                        when {
                            membership.externalId.isNotBlank() && series.externalId.isNotBlank() ->
                                series.externalId == membership.externalId
                            else -> series.name.equals(membership.name, ignoreCase = true)
                        }
                }

                val cachedDetail = runCatching {
                    cacheStore.readSeries(
                        if (membership.id.startsWith("source:")) {
                            LocalCacheStore.sourceSeriesKey(book.id, membership.provider)
                        } else {
                            LocalCacheStore.audioSeriesKey(membership.id)
                        }
                    )
                }.getOrNull()

                val knownBooks = buildList {
                    cachedDetail?.entries.orEmpty().mapNotNullTo(this) { it.book }
                    if (isEmpty()) addAll(cachedDetail?.books.orEmpty())
                    existing?.currentBook?.book?.let(::add)
                    existing?.nextBook?.book?.let(::add)
                    add(book.asCard())
                }
                    .distinctBy { it.id }
                    .map(::localProgress)

                val currentCard = localProgress(book.asCard()).copy(
                    sourceSeriesName = membership.name,
                    sourceSeriesPosition = membership.position?.toInt()
                        ?: book.sourceSeriesPosition
                        ?: book.seriesPosition,
                )
                val currentPosition = membership.position
                    ?: currentCard.sourceSeriesPosition?.toDouble()
                val currentProgress = currentCard.progressPercent.coerceIn(0.0, 100.0)

                val nextCard = knownBooks
                    .asSequence()
                    .filterNot { it.id == book.id }
                    .map { candidate ->
                        candidate to (
                            candidate.sourceSeriesPosition?.toDouble()
                                ?: candidate.audioSeries.firstOrNull {
                                    it.provider.equals(membership.provider, ignoreCase = true) &&
                                        (membership.externalId.isBlank() || it.externalId == membership.externalId)
                                }?.position
                            )
                    }
                    .filter { (_, position) ->
                        position != null && (currentPosition == null || position > currentPosition)
                    }
                    .minByOrNull { (_, position) -> position ?: Double.MAX_VALUE }

                val available = maxOf(
                    1,
                    existing?.availableCount ?: 0,
                    cachedDetail?.booksCount ?: 0,
                    knownBooks.size,
                )
                val total = maxOf(
                    available,
                    existing?.totalCount ?: 0,
                    cachedDetail?.totalCount ?: 0,
                )
                val completed = knownBooks.count { it.progressPercent >= COMPLETED_PROGRESS_PERCENT }
                    .coerceAtMost(available)
                val inProgress = knownBooks.count {
                    it.progressPercent > 0.0 && it.progressPercent < COMPLETED_PROGRESS_PERCENT
                }.coerceAtMost((available - completed).coerceAtLeast(0))
                val notStarted = (available - completed - inProgress).coerceAtLeast(0)

                MySeriesDto(
                    id = existing?.id ?: membership.id,
                    name = membership.name,
                    provider = membership.provider,
                    externalId = membership.externalId,
                    sourceName = membership.sourceName,
                    availableCount = available,
                    totalCount = total,
                    completedCount = completed,
                    inProgressCount = inProgress,
                    notStartedCount = notStarted,
                    status = if (available > 0 && completed >= available) "completed" else "active",
                    lastActivityAt = System.currentTimeMillis().toString(),
                    currentBook = SeriesProgressBookDto(
                        book = currentCard,
                        position = currentPosition,
                        state = if (currentProgress >= COMPLETED_PROGRESS_PERCENT) "completed" else "in_progress",
                        progressPercent = currentProgress,
                    ),
                    nextBook = nextCard?.let { (candidate, position) ->
                        SeriesProgressBookDto(
                            book = candidate,
                            position = position,
                            state = when {
                                candidate.progressPercent >= COMPLETED_PROGRESS_PERCENT -> "completed"
                                candidate.progressPercent > 0.0 -> "in_progress"
                                else -> "not_started"
                            },
                            progressPercent = candidate.progressPercent.coerceIn(0.0, 100.0),
                        )
                    },
                )
            }

        cacheStore.promoteSeriesState(updated)
    }

    private fun localProgress(card: BookCardDto): BookCardDto {
        val progress = resumeStore.get(card.id)?.progressPercent
            ?.takeIf { it > 0.0 }
            ?: card.progressPercent
        return card.copy(progressPercent = progress.coerceIn(0.0, 100.0))
    }

    private companion object {
        const val COMPLETED_PROGRESS_PERCENT = 99.5
    }
}
