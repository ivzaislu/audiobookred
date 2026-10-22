package com.example.data.textbook

/** Half-open UTF-16 range in a rendered paragraph, which may contain several TTS fragments. */
internal fun textHighlightRange(block: TextReadingBlock, fragments: Map<String, TextFragmentEntity>, id: String?): IntRange? {
    if (id == null) return null
    var start = 0
    for (fragmentId in block.fragmentIds) {
        val length = fragments[fragmentId]?.text?.length ?: return null
        if (fragmentId == id) return start until (start + length).coerceAtMost(block.text.removeSuffix("\n").length)
        start += length
    }
    return null
}
