package com.example.domain.playback

import com.example.data.model.BookDetailDto
import com.example.data.torrserve.canResolveRuTrackerPlayback

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
