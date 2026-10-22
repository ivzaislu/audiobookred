package com.example.data.textbook

/** Reader layout is independent of the small audio fragments. A block may span several of them. */
internal data class TextReadingBlock(
    val fragmentIds: List<String>, val chapterId: String, val text: String,
) {
    val anchorId get() = fragmentIds.first()
}

internal fun readingBlocks(fragments: List<TextFragmentEntity>): List<TextReadingBlock> = buildList {
    val ids = mutableListOf<String>()
    val text = StringBuilder()
    var chapter = ""
    fun flush() {
        if (ids.isEmpty()) return
        add(TextReadingBlock(ids.toList(), chapter, text.toString()))
        ids.clear(); text.clear()
    }
    for (fragment in fragments) {
        if (chapter != fragment.chapterId) flush()
        chapter = fragment.chapterId
        ids += fragment.id
        text.append(fragment.text)
        // Preserve paragraph continuation across synthesis boundaries. Bound pathological paragraphs.
        if (text.endsWith('\n') || text.length >= 16_000) flush()
    }
    flush()
}
