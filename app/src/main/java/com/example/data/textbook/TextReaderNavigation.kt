package com.example.data.textbook

internal data class TextContentsEntry(
    val chapterId: String, val title: String, val wholeBook: Boolean = false,
    val fragmentId: String? = null, val offset: Int = 0,
) {
    val key: String get() = fragmentId?.let { "mark:$it:$offset" } ?: chapterId
}

/** Export ranges are not the author's table of contents. Never rewrite stored chapter identities. */
internal fun readingContents(
    chapters: List<TextChapterEntity>, edits: TextContentsEdits = TextContentsEdits(),
    fragments: List<TextFragmentEntity> = emptyList(),
): List<TextContentsEntry> {
    val authored = chapters.filter { !it.automatic || it.id in edits.chapterTitles }
    val base = if (authored.isNotEmpty()) authored.map { it.startOffset to TextContentsEntry(it.id, edits.chapterTitles[it.id] ?: it.title) }
    else chapters.firstOrNull()?.let { listOf(it.startOffset to TextContentsEntry(it.id, "Текст книги", wholeBook = true)) }.orEmpty()
    val byId = fragments.associateBy { it.id }
    val markers = edits.markers.mapNotNull { marker -> byId[marker.fragmentId]?.let { fragment ->
        (fragment.startOffset + marker.offset) to TextContentsEntry(fragment.chapterId, marker.title,
            fragmentId = marker.fragmentId, offset = marker.offset)
    } }
    return (base + markers).sortedBy { it.first }.map { it.second }
}

/** Progress follows normalized text, not a count of pages, chunks or chapters. */
internal fun textReadingPercent(totalCharacters: Int, visibleCharacter: Int, atEnd: Boolean): Int {
    if (totalCharacters <= 0) return 0
    if (atEnd) return 100
    return ((visibleCharacter.coerceAtLeast(0).toLong() * 100) / totalCharacters).toInt().coerceIn(0, 99)
}
