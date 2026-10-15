package com.example.data.local

import com.example.data.model.BookDetailDto

/** A topic refresh contains metadata/magnet only; its empty list is not a new playlist. */
internal fun mergeRuTrackerBookForCache(
    incoming: BookDetailDto,
    cached: BookDetailDto?,
): BookDetailDto {
    if (!incoming.selectedSource.equals("rutracker", ignoreCase = true) ||
        incoming.chapters.isNotEmpty() || cached == null || cached.chapters.isEmpty() ||
        incoming.id != cached.id ||
        !cached.selectedSource.equals("rutracker", ignoreCase = true) ||
        incoming.selectedBookSourceId.isBlank() ||
        incoming.selectedBookSourceId != cached.selectedBookSourceId
    ) return incoming

    fun selectedMagnet(book: BookDetailDto): String? = book.sourceVariants
        .firstOrNull {
            it.sourceCode.equals("rutracker", ignoreCase = true) &&
                it.bookSourceId == book.selectedBookSourceId
        }?.magnetUri?.trim()?.takeIf(String::isNotBlank)

    val magnet = selectedMagnet(incoming) ?: return incoming
    if (magnet != selectedMagnet(cached)) return incoming

    return incoming.copy(
        chapters = cached.chapters,
        durationSeconds = incoming.durationSeconds.takeIf { it > 0L } ?: cached.durationSeconds,
    )
}
