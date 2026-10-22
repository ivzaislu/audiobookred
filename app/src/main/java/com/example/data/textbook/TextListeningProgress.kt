package com.example.data.textbook

/** A single exported chapter must not appear as 100% of the original book. */
internal fun textChapterListeningPercent(characterCount: Int, startOffset: Int, endOffset: Int, chapterPercent: Double): Double {
    if (characterCount <= 0 || !chapterPercent.isFinite()) return 0.0
    val start = startOffset.coerceIn(0, characterCount)
    val end = endOffset.coerceIn(start, characterCount)
    return ((start + (end - start) * chapterPercent.coerceIn(0.0, 100.0) / 100.0) * 100.0 / characterCount)
        .coerceIn(0.0, 100.0)
}
