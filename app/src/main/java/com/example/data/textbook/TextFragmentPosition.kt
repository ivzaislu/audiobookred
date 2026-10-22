package com.example.data.textbook

/** A transient audio target in the displayed text; never a saved reading bookmark. */
internal data class TextFragmentPosition(val fragmentId: String, val fraction: Double)

internal fun textPositionAtOffset(
    block: TextReadingBlock,
    fragments: Map<String, TextFragmentEntity>,
    characterOffset: Int,
): TextFragmentPosition? {
    val offset = characterOffset.coerceIn(0, block.text.length)
    var prefix = 0
    for ((index, id) in block.fragmentIds.withIndex()) {
        val fragment = fragments[id] ?: return null
        val length = fragment.text.length
        if (offset < prefix + length || index == block.fragmentIds.lastIndex) {
            return TextFragmentPosition(id, ((offset - prefix).toDouble() / length.coerceAtLeast(1)).coerceIn(0.0, 1.0))
        }
        prefix += length
    }
    return null
}

/** Selection can be dragged in either direction; playback starts at its earlier boundary. */
internal fun textSelectionPosition(
    block: TextReadingBlock,
    fragments: Map<String, TextFragmentEntity>,
    selectionStart: Int,
    selectionEnd: Int,
): TextFragmentPosition? {
    val length = block.text.removeSuffix("\n").length
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(0, length)
    return if (start < end) textPositionAtOffset(block, fragments, start) else null
}
