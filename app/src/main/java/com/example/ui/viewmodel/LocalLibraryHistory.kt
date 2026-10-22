package com.example.ui.viewmodel

import com.example.data.local.DownloadBookEntity
import com.example.data.model.BookCardDto
import com.example.data.textbook.textLibraryPlaybackBookId

internal fun homeContinueBooks(history: List<BookCardDto>, limit: Int): List<BookCardDto> =
    history.distinctBy { it.id }.filter { it.progressPercent < 99.5 }.take(limit.coerceAtLeast(0))

private fun textExportLinks(downloads: List<DownloadBookEntity>, textIds: Set<String>): Map<String, String> =
    downloads.mapNotNull { row ->
        textLibraryPlaybackBookId(row.sourceCode, row.bookId, row.bookSourceId)?.takeIf { it in textIds }
            ?.let { row.bookId to it }
    }.toMap()

/** Both Home and Library use the same canonical identities, visibility and ordering. */
internal fun mergeLocalLibraryHistory(
    audio: List<BookCardDto>, audioTimes: Map<String, Long>, downloads: List<DownloadBookEntity>,
    textIds: Set<String>, text: TextLibraryMembership,
): List<BookCardDto> {
    val exports = textExportLinks(downloads, textIds)
    return (text.history + audio.filterNot { it.id in textIds || it.id in exports })
        .distinctBy { it.id }
        .sortedByDescending { text.activityTimes[it.id] ?: audioTimes[it.id] ?: 0L }
}

internal fun legacyTextHistoryActivity(
    history: List<BookCardDto>, audioTimes: Map<String, Long>, downloads: List<DownloadBookEntity>, textIds: Set<String>,
): Map<String, Long> {
    val exports = textExportLinks(downloads, textIds)
    return history.mapNotNull { card ->
        val localId = card.id.takeIf { it in textIds } ?: exports[card.id]
        localId?.let { it to (audioTimes[card.id]?.takeIf { time -> time > 0 } ?: 1L) }
    }.groupBy({ it.first }, { it.second }).mapValues { it.value.max() }
}
