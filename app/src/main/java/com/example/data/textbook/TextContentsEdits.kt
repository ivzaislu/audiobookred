package com.example.data.textbook

internal const val TEXT_CONTENTS_TITLE_LIMIT = 200
private const val CONTENTS_ENTRY_LIMIT = 1000

internal data class TextContentsMarker(val fragmentId: String, val offset: Int, val title: String) {
    val key: String get() = "mark:$fragmentId:$offset"
}

/** Reader-only overlay. Source text, chapter IDs and all audio timing remain immutable. */
internal data class TextContentsEdits(
    val chapterTitles: Map<String, String> = emptyMap(),
    val markers: List<TextContentsMarker> = emptyList(),
) {
    fun validate(chapters: List<TextChapterEntity>, fragments: List<TextFragmentEntity>): TextContentsEdits {
        require(chapterTitles.size <= CONTENTS_ENTRY_LIMIT && markers.size <= CONTENTS_ENTRY_LIMIT)
        val chapterIds = chapters.map { it.id }.toSet()
        require(chapterTitles.all { (id, title) -> id in chapterIds && validTitle(title) })
        val byId = fragments.associateBy { it.id }
        require(markers.map { it.key }.distinct().size == markers.size)
        require(markers.all { marker ->
            val fragment = byId[marker.fragmentId]
            fragment != null && validTitle(marker.title) && marker.offset in 0 until fragment.text.length &&
                !(marker.offset > 0 && fragment.text[marker.offset].isLowSurrogate() && fragment.text[marker.offset - 1].isHighSurrogate())
        })
        return this
    }

    fun rename(chapterId: String, title: String): TextContentsEdits {
        val clean = title.trim()
        require(clean.isEmpty() || validTitle(clean))
        return copy(chapterTitles = if (clean.isEmpty()) chapterTitles - chapterId else chapterTitles + (chapterId to clean))
    }

    fun putMarker(fragmentId: String, offset: Int, title: String): TextContentsEdits {
        val marker = TextContentsMarker(fragmentId, offset, title.trim())
        require(validTitle(marker.title))
        return copy(markers = markers.filterNot { it.key == marker.key } + marker)
    }

    fun removeMarker(fragmentId: String, offset: Int): TextContentsEdits =
        copy(markers = markers.filterNot { it.fragmentId == fragmentId && it.offset == offset })
}

private fun validTitle(title: String): Boolean = title.isNotBlank() && title == title.trim() &&
    title.length <= TEXT_CONTENTS_TITLE_LIMIT && title.none { it.isISOControl() }
