package com.example.domain.playback

import com.example.data.local.DownloadStore
import com.example.data.local.LocalCacheStore
import com.example.data.model.BookDetailDto
import com.example.data.model.BookmarkDto
import com.example.data.model.BookmarkUiItem
import com.example.data.model.ProgressResponse
import com.example.data.player.PlaybackResumeStore
import com.example.data.repository.AudiobookRepository
import com.example.data.repository.BookDetailRepository
import com.example.data.settings.BookSourcePreferenceStore
import com.example.data.settings.PlayerSettingsStore
import com.example.data.torrserve.RuTrackerTorrServePlaybackResolver
import com.example.data.torrserve.TorrServePreparationStage
import com.example.data.torrserve.canResolveRuTrackerPlayback
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal fun shouldUseResumeStoreCheckpoint(
    snapshotSourceCode: String,
    sourceVariantCount: Int,
): Boolean = !snapshotSourceCode.equals("unknown", ignoreCase = true) || sourceVariantCount <= 1

internal fun cachedResumeBook(
    candidate: BookDetailDto?,
    sourceHint: String?,
): BookDetailDto? = candidate?.takeIf { book ->
    book.chapters.isNotEmpty() && bookmarkCachedSourceMatchesHint(book.selectedSource, sourceHint)
}

internal fun shouldResolveRuTrackerBookmarkSource(book: BookDetailDto): Boolean =
    canResolveRuTrackerPlayback(book)

internal fun shouldUseCompletedRuTrackerDownload(
    sourceCode: String,
    bookSourceId: String,
    downloadState: String?,
    deletedAtMs: Long?,
    expectedFilesCount: Int,
    fileStates: List<String>,
): Boolean =
    sourceCode.equals("rutracker", ignoreCase = true) &&
        bookSourceId.isNotBlank() &&
        downloadState == "completed" &&
        deletedAtMs == null &&
        expectedFilesCount > 0 &&
        fileStates.size == expectedFilesCount &&
        fileStates.all { it == "completed" }

internal fun shouldUseRuTrackerBookmarkOrdinalFallback(
    book: BookDetailDto,
    bookmarkChapterId: String?,
): Boolean =
    book.selectedSource.equals("rutracker", ignoreCase = true) &&
        !bookmarkChapterId.isNullOrBlank() &&
        bookmarkChapterId.startsWith("${book.id}:torrserve:") &&
        book.chapters.isNotEmpty()

/**
 * Resolves the data and business rules required before Media3 playback starts.
 *
 * The use case owns resume/source/speed selection. Playback checkpoints come
 * only from local SharedPreferences/Room state; no remote progress fallback is
 * consulted by the standalone APK.
 */
@Singleton
class PreparePlaybackUseCase @Inject constructor(
    private val repository: AudiobookRepository,
    private val bookDetailRepository: BookDetailRepository,
    private val downloadStore: DownloadStore,
    private val settingsStore: PlayerSettingsStore,
    private val resumeStore: PlaybackResumeStore,
    private val cacheStore: LocalCacheStore,
    private val sourcePreferenceStore: BookSourcePreferenceStore,
    private val ruTrackerPlaybackResolver: RuTrackerTorrServePlaybackResolver,
) {
    suspend fun resume(
        book: BookDetailDto,
        onTorrServePreparationStage: (TorrServePreparationStage) -> Unit = {},
    ): PreparedPlayback {
        val downloadedBook = completedDownloadedBookOrNull(book)
        val playableBook = if (downloadedBook != null) {
            downloadedBook
        } else if (canResolveRuTrackerPlayback(book)) {
            val persistedBeforeResolution = loadProgress(book)
            val immediateBeforeResolution = localResume(book)
            ensurePlayableBook(
                book = book,
                onTorrServePreparationStage = onTorrServePreparationStage,
                warmChapterIndexSelector = { resolvedBook ->
                    previewResumeChapterIndex(
                        book = resolvedBook,
                        saved = persistedBeforeResolution,
                        immediate = immediateBeforeResolution,
                    )
                },
            )
        } else {
            ensurePlayableBook(book, onTorrServePreparationStage)
        }
        val saved = loadProgress(playableBook)
        val point = resolveResumePoint(playableBook, saved)
        return prepared(playableBook, point.first, point.second, saved)
    }

    /**
     * Home's Continue action only has a book id. Prefer the already cached full
     * detail so playback can start immediately and still works offline. Network
     * parsing is only a fallback when the local detail is missing/incomplete.
     */
    suspend fun resume(
        bookId: String,
        onTorrServePreparationStage: (TorrServePreparationStage) -> Unit = {},
    ): PreparedPlayback? {
        val cleanBookId = bookId.trim()
        if (cleanBookId.isBlank()) return null

        val sourceHint = sourcePreferenceStore.get(cleanBookId)?.takeIf { it.isNotBlank() }
            ?: resumeStore.get(cleanBookId)?.sourceCode?.takeIf { source ->
                source.isNotBlank() && !source.equals("unknown", ignoreCase = true)
            }

        val cached = cachedResumeBook(
            candidate = cacheStore.readBook(cleanBookId, sourceHint),
            sourceHint = sourceHint,
        )
        if (cached != null) return resume(cached, onTorrServePreparationStage)

        completedDownloadedBookForBookIdOrNull(cleanBookId, sourceHint)?.let { downloaded ->
            return resume(downloaded, onTorrServePreparationStage)
        }

        val fresh = try {
            repository.book(cleanBookId, sourceHint).also { cacheStore.writeBook(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (fresh != null && canPreparePlayback(fresh)) return resume(fresh, onTorrServePreparationStage)

        if (sourceHint == null) return null

        val fallbackCached = cachedResumeBook(
            candidate = cacheStore.readBook(cleanBookId),
            sourceHint = null,
        )
        if (fallbackCached != null) return resume(fallbackCached, onTorrServePreparationStage)

        val fallbackFresh = try {
            repository.book(cleanBookId).also { cacheStore.writeBook(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return fallbackFresh?.takeIf(::canPreparePlayback)?.let { resume(it, onTorrServePreparationStage) }
    }

    /**
     * Resumes one exact audio source. Android Auto/system resumption already has
     * a source-aware checkpoint, so falling back to another narrator/provider
     * would be worse than reporting that the saved source is temporarily
     * unavailable. Unknown legacy checkpoints keep the normal book-id behavior.
     */
    suspend fun resume(
        bookId: String,
        sourceCode: String,
        onTorrServePreparationStage: (TorrServePreparationStage) -> Unit = {},
    ): PreparedPlayback? {
        val cleanBookId = bookId.trim()
        if (cleanBookId.isBlank()) return null
        val sourceHint = sourceCode.trim().takeIf { source ->
            source.isNotBlank() && !source.equals("unknown", ignoreCase = true)
        } ?: return resume(cleanBookId, onTorrServePreparationStage)

        val cached = cachedResumeBook(
            candidate = cacheStore.readBook(cleanBookId, sourceHint),
            sourceHint = sourceHint,
        )
        if (cached != null) return resume(cached, onTorrServePreparationStage)

        completedDownloadedBookForBookIdOrNull(cleanBookId, sourceHint)?.let { downloaded ->
            return resume(downloaded, onTorrServePreparationStage)
        }

        val fresh = try {
            repository.book(cleanBookId, sourceHint)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val exactFresh = fresh?.takeIf { candidate ->
            bookmarkCachedSourceMatchesHint(candidate.selectedSource, sourceHint) &&
                canPreparePlayback(candidate)
        } ?: return null
        cacheStore.writeBook(exactFresh)
        return resume(exactFresh, onTorrServePreparationStage)
    }

    private fun canPreparePlayback(book: BookDetailDto): Boolean =
        book.chapters.isNotEmpty() || canResolveRuTrackerPlayback(book)

    private suspend fun ensurePlayableBook(
        book: BookDetailDto,
        onTorrServePreparationStage: (TorrServePreparationStage) -> Unit,
        warmChapterIndexSelector: (BookDetailDto) -> Int = { 0 },
    ): BookDetailDto {
        if (!canResolveRuTrackerPlayback(book)) {
            if (book.chapters.isNotEmpty()) return book
            throw IllegalStateException("Для этой книги нет доступных аудиоглав")
        }

        // RuTracker stream URLs are tied to the currently configured TorrServe
        // origin and credentials. Refresh them before each prepared playback so
        // a changed server address never leaves a stale cached /stream URL.
        val resolved = try {
            ruTrackerPlaybackResolver.resolve(
                book = book,
                warmChapterIndexSelector = warmChapterIndexSelector,
                onPreparationStage = onTorrServePreparationStage,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val detail = error.message?.takeIf(String::isNotBlank)
                ?: error::class.java.simpleName
            throw IllegalStateException("RuTracker/TorrServe: не удалось подготовить поток: $detail", error)
        }
        if (resolved.chapters.isEmpty()) {
            throw IllegalStateException("RuTracker/TorrServe: сервер не вернул аудиофайлы для этой книги")
        }

        // Generated /stream URLs are ephemeral and tied to the currently
        // configured TorrServe origin. A Room/cache failure must never prevent
        // otherwise valid playback from starting.
        try {
            cacheStore.writeBook(resolved)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
        return resolved
    }

    suspend fun chapter(book: BookDetailDto, chapterIndex: Int): PreparedPlayback {
        val saved = loadProgress(book)
        val requested = chapterIndex.coerceIn(0, book.chapters.lastIndex.coerceAtLeast(0))
        val local = localResume(book)
        val requestedChapter = book.chapters.getOrNull(requested)
        val position = resolveExplicitChapterStartPosition(
            requestedChapterId = requestedChapter?.id,
            requestedChapterIndex = requested,
            chapterDurationMs = requestedChapter
                ?.durationSeconds
                ?.coerceAtLeast(0L)
                ?.times(1_000L)
                ?: 0L,
            sourceVariantCount = book.sourceVariants.size,
            persisted = saved?.let { progress ->
                PlaybackResumeCandidate(
                    chapterId = progress.chapterId,
                    chapterIndex = progress.chapterIndex,
                    positionMs = progress.positionMs,
                )
            },
            immediate = local?.let { snapshot ->
                PlaybackResumeCandidate(
                    chapterId = snapshot.chapterId,
                    chapterIndex = snapshot.chapterIndex,
                    positionMs = snapshot.positionMs,
                    preferOverPersisted = snapshot.dirty,
                )
            },
        )
        return prepared(book, requested, position, saved)
    }

    suspend fun bookmark(
        item: BookmarkUiItem,
        onTorrServePreparationStage: (TorrServePreparationStage) -> Unit = {},
    ): PreparedPlayback? {
        val bookId = item.bookmark.bookId
        val sourceHint = resolveBookmarkPlaybackSource(
            preferredSource = sourcePreferenceStore.get(bookId),
            resumeSource = resumeStore.get(bookId)?.sourceCode,
        )
        val initialBook = loadBookmarkBook(bookId, sourceHint) ?: return null
        return bookmark(
            book = initialBook,
            bookmark = item.bookmark,
            onTorrServePreparationStage = onTorrServePreparationStage,
        )
    }

    suspend fun bookmark(
        book: BookDetailDto,
        bookmark: BookmarkDto,
        onTorrServePreparationStage: (TorrServePreparationStage) -> Unit = {},
    ): PreparedPlayback {
        val resolved = resolveBookSourceForChapter(
            book = book,
            chapterId = bookmark.chapterId,
            bookmarkChapterIndex = bookmark.chapterIndex,
            onTorrServePreparationStage = onTorrServePreparationStage,
        )
        if (resolved.selectedSource.isNotBlank()) {
            sourcePreferenceStore.set(resolved.id, resolved.selectedSource)
        }
        val saved = loadProgress(resolved)
        val index = resolveBookmarkChapterIndex(
            chapterIds = resolved.chapters.map { it.id },
            bookmarkChapterId = bookmark.chapterId,
            bookmarkChapterIndex = bookmark.chapterIndex,
        )
        return prepared(
            book = resolved,
            chapterIndex = index,
            positionMs = bookmark.positionMs,
            saved = saved,
        )
    }

    suspend fun downloaded(bookSourceId: String): PreparedPlayback {
        val book = bookDetailRepository.downloadedBook(bookSourceId)
        val saved = loadProgress(book)
        val point = resolveResumePoint(book, saved)
        return prepared(book, point.first, point.second, saved)
    }

    private suspend fun loadBookmarkBook(bookId: String, sourceHint: String?): BookDetailDto? {
        completedDownloadedBookForBookIdOrNull(bookId, sourceHint)?.let { return it }

        val cached = cacheStore.readBook(bookId, sourceHint)
        try {
            return repository.book(bookId, sourceHint).also { cacheStore.writeBook(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (cached != null && bookmarkCachedSourceMatchesHint(cached.selectedSource, sourceHint)) {
                return cached
            }
        }

        if (sourceHint == null) return null
        val fallbackCached = cacheStore.readBook(bookId)
        return try {
            repository.book(bookId).also { cacheStore.writeBook(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            fallbackCached
        }
    }

    private suspend fun loadProgress(book: BookDetailDto): ProgressResponse? {
        val bookSourceId = book.selectedBookSourceId.takeIf { it.isNotBlank() }
        val source = book.selectedSource.takeIf { it.isNotBlank() }
            ?: bookSourceId?.let { cacheStore.sourceCodeForBookSource(book.id, it) }
        return cacheStore.readProgress(book.id, source)
    }

    private suspend fun resolveBookSourceForChapter(
        book: BookDetailDto,
        chapterId: String?,
        bookmarkChapterIndex: Int,
        onTorrServePreparationStage: (TorrServePreparationStage) -> Unit,
    ): BookDetailDto {
        val warmBookmarkChapter: (BookDetailDto) -> Int = { resolvedBook ->
            resolveBookmarkChapterIndex(
                chapterIds = resolvedBook.chapters.map { it.id },
                bookmarkChapterId = chapterId,
                bookmarkChapterIndex = bookmarkChapterIndex,
            )
        }
        val selectedBook = completedDownloadedBookOrNull(book) ?: if (shouldResolveRuTrackerBookmarkSource(book)) {
            ensurePlayableBook(
                book = book,
                onTorrServePreparationStage = onTorrServePreparationStage,
                warmChapterIndexSelector = warmBookmarkChapter,
            )
        } else {
            book
        }
        if (
            chapterId.isNullOrBlank() ||
            selectedBook.chapters.any { it.id == chapterId }
        ) {
            return selectedBook
        }

        if (shouldUseRuTrackerBookmarkOrdinalFallback(selectedBook, chapterId)) {
            val fallbackIndex = bookmarkChapterIndex.coerceIn(
                0,
                selectedBook.chapters.lastIndex.coerceAtLeast(0),
            )
            if (fallbackIndex in selectedBook.chapters.indices) {
                return selectedBook
            }
        }

        for (variant in selectedBook.sourceVariants) {
            if (variant.sourceCode == selectedBook.selectedSource) continue
            val cached = cacheStore.readBook(selectedBook.id, variant.sourceCode)
            val rawCandidate = try {
                repository.book(selectedBook.id, variant.sourceCode).also { cacheStore.writeBook(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (cached != null && bookmarkCachedSourceMatchesHint(cached.selectedSource, variant.sourceCode)) {
                    cached
                } else {
                    continue
                }
            }
            val candidate = try {
                if (shouldResolveRuTrackerBookmarkSource(rawCandidate)) {
                    ensurePlayableBook(
                        book = rawCandidate,
                        onTorrServePreparationStage = onTorrServePreparationStage,
                        warmChapterIndexSelector = warmBookmarkChapter,
                    )
                } else {
                    rawCandidate
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
            if (candidate.chapters.any { it.id == chapterId }) {
                sourcePreferenceStore.set(candidate.id, candidate.selectedSource)
                return candidate
            }
        }

        throw IllegalStateException("Не удалось найти главу для этой закладки")
    }

    private suspend fun completedDownloadedBookOrNull(book: BookDetailDto): BookDetailDto? {
        if (!book.selectedSource.equals("rutracker", ignoreCase = true)) return null
        val bookSourceId = book.selectedBookSourceId.trim().takeIf(String::isNotBlank) ?: return null
        return completedDownloadedBookForSourceIdOrNull(bookSourceId)
    }

    private suspend fun completedDownloadedBookForBookIdOrNull(
        bookId: String,
        sourceHint: String?,
    ): BookDetailDto? {
        if (!sourceHint.equals("rutracker", ignoreCase = true)) return null
        val candidates = try {
            downloadStore.books()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }

        for (download in candidates) {
            if (
                download.bookId == bookId &&
                download.sourceCode.equals("rutracker", ignoreCase = true)
            ) {
                completedDownloadedBookForSourceIdOrNull(download.bookSourceId)?.let { return it }
            }
        }
        return null
    }

    private suspend fun completedDownloadedBookForSourceIdOrNull(
        bookSourceId: String,
    ): BookDetailDto? {
        val download = try {
            downloadStore.book(bookSourceId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        } ?: return null

        val files = try {
            downloadStore.files(bookSourceId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }

        if (
            !shouldUseCompletedRuTrackerDownload(
                sourceCode = download.sourceCode,
                bookSourceId = download.bookSourceId,
                downloadState = download.state,
                deletedAtMs = download.deletedAtMs,
                expectedFilesCount = download.filesCount,
                fileStates = files.map { it.state },
            )
        ) {
            return null
        }

        return try {
            bookDetailRepository.downloadedBook(bookSourceId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun previewResumeChapterIndex(
        book: BookDetailDto,
        saved: ProgressResponse?,
        immediate: PlaybackResumeStore.Snapshot?,
    ): Int = resolvePlaybackResumePoint(
        chapterIds = book.chapters.map { it.id },
        chapterDurationsMs = book.chapters.map { chapter ->
            chapter.durationSeconds.coerceAtLeast(0L).times(1_000L)
        },
        sourceVariantCount = book.sourceVariants.size,
        persisted = saved?.let { progress ->
            PlaybackResumeCandidate(
                chapterId = progress.chapterId,
                chapterIndex = progress.chapterIndex,
                positionMs = progress.positionMs,
                completed = progress.completed,
            )
        },
        immediate = immediate?.let { snapshot ->
            PlaybackResumeCandidate(
                chapterId = snapshot.chapterId,
                chapterIndex = snapshot.chapterIndex,
                positionMs = snapshot.positionMs,
                preferOverPersisted = snapshot.dirty,
            )
        },
    ).chapterIndex

    private fun prepared(
        book: BookDetailDto,
        chapterIndex: Int,
        positionMs: Long,
        saved: ProgressResponse?,
    ): PreparedPlayback = PreparedPlayback(
        book = book,
        chapterIndex = chapterIndex,
        positionMs = positionMs,
        speed = preferredSpeed(saved, book),
    )

    private fun preferredSpeed(saved: ProgressResponse?, book: BookDetailDto): Float {
        val local = localResume(book)
        val settings = settingsStore.state.value
        return resolvePlaybackSpeed(
            rememberBookSpeed = settings.rememberBookSpeed,
            defaultSpeed = settings.defaultSpeed,
            chapterIds = book.chapters.mapTo(mutableSetOf()) { it.id },
            sourceVariantCount = book.sourceVariants.size,
            persisted = saved?.let { progress ->
                PlaybackSpeedCheckpoint(
                    chapterId = progress.chapterId,
                    speed = progress.playbackSpeed.toFloat(),
                )
            },
            immediate = local?.let { snapshot ->
                PlaybackSpeedCheckpoint(
                    chapterId = snapshot.chapterId,
                    speed = snapshot.speed,
                    preferOverPersisted = snapshot.dirty,
                )
            },
        )
    }

    private fun resolveResumePoint(book: BookDetailDto, saved: ProgressResponse?): Pair<Int, Long> {
        if (book.chapters.isEmpty()) return 0 to 0L

        val local = localResume(book)
        val resolution = resolvePlaybackResumePoint(
            chapterIds = book.chapters.map { it.id },
            chapterDurationsMs = book.chapters.map { chapter ->
                chapter.durationSeconds.coerceAtLeast(0L).times(1_000L)
            },
            sourceVariantCount = book.sourceVariants.size,
            persisted = saved?.let { progress ->
                PlaybackResumeCandidate(
                    chapterId = progress.chapterId,
                    chapterIndex = progress.chapterIndex,
                    positionMs = progress.positionMs,
                    completed = progress.completed,
                )
            },
            immediate = local?.let { snapshot ->
                PlaybackResumeCandidate(
                    chapterId = snapshot.chapterId,
                    chapterIndex = snapshot.chapterIndex,
                    positionMs = snapshot.positionMs,
                    preferOverPersisted = snapshot.dirty,
                )
            },
        )

        if (resolution.clearResumeStore) {
            resumeStore.clear(book.id, book.selectedSource)
        } else if (resolution.persistedChapterIndexToMirror != null && saved != null) {
            // Mirror the normalized Room checkpoint into the fast source-aware
            // resume store so subsequent playback starts can use the same index.
            resumeStore.cachePersistedCheckpoint(
                bookId = book.id,
                sourceCode = book.selectedSource,
                chapterId = saved.chapterId,
                chapterIndex = resolution.persistedChapterIndexToMirror,
                positionMs = saved.positionMs.coerceAtLeast(0L),
                speed = saved.playbackSpeed.toFloat(),
            )
        }

        return resolution.chapterIndex to resolution.positionMs
    }

    private fun localResume(book: BookDetailDto): PlaybackResumeStore.Snapshot? =
        resumeStore.get(book.id, book.selectedSource)?.takeIf { snapshot ->
            shouldUseResumeStoreCheckpoint(
                snapshotSourceCode = snapshot.sourceCode,
                sourceVariantCount = book.sourceVariants.size,
            )
        }
}
