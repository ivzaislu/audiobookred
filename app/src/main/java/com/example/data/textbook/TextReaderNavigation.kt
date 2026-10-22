package com.example.data.textbook

internal data class TextContentsEntry(val chapterId: String, val title: String, val wholeBook: Boolean = false)

/** Export ranges are not the author's table of contents. Never rewrite stored chapter identities. */
internal fun readingContents(chapters: List<TextChapterEntity>): List<TextContentsEntry> {
    val authored = chapters.filterNot { it.automatic }
    if (authored.isNotEmpty()) return authored.map { TextContentsEntry(it.id, it.title) }
    return chapters.firstOrNull()?.let { listOf(TextContentsEntry(it.id, "Текст книги", wholeBook = true)) }.orEmpty()
}

/** Progress follows normalized text, not a count of pages, chunks or chapters. */
internal fun textReadingPercent(totalCharacters: Int, visibleCharacter: Int, atEnd: Boolean): Int {
    if (totalCharacters <= 0) return 0
    if (atEnd) return 100
    return ((visibleCharacter.coerceAtLeast(0).toLong() * 100) / totalCharacters).toInt().coerceIn(0, 99)
}
