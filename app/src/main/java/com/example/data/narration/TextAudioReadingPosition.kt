package com.example.data.narration

import com.example.data.textbook.TextFragmentEntity

/** One-shot navigation target: never overwrite the independent reading bookmark automatically. */
internal data class TextAudioReadingPosition(
    val bookId: String,
    val chapterId: String,
    val fragmentId: String,
    val fractionInFragment: Double,
    val approximate: Boolean,
)

/** Saved chapter audio has no per-word timings, so interpolate over spoken text length. */
internal fun approximateAudioFragment(
    fragments: List<TextFragmentEntity>,
    positionMs: Long,
    durationMs: Long,
): Pair<String, Double>? {
    if (fragments.isEmpty()) return null
    if (durationMs <= 0L) return fragments.first().id to 0.0
    val fraction = (positionMs.toDouble() / durationMs).coerceIn(0.0, 0.999999)
    val lengths = fragments.map { it.text.length.coerceAtLeast(1) }
    val total = lengths.sumOf { it.toLong() }.coerceAtLeast(1L)
    val target = fraction * total
    var prefix = 0L
    for ((i, fragment) in fragments.withIndex()) {
        val end = prefix + lengths[i]
        if (target < end || i == fragments.lastIndex) {
            return fragment.id to ((target - prefix) / lengths[i]).coerceIn(0.0, 1.0)
        }
        prefix = end
    }
    return fragments.last().id to 1.0
}

/** Inverse of the legacy length-weighted estimate; never requires a TTS engine. */
internal fun approximateTextAudioTime(
    fragments: List<TextFragmentEntity>, fragmentId: String, fraction: Double, durationMs: Long,
): Long? {
    val index = fragments.indexOfFirst { it.id == fragmentId }
    if (index < 0) return null
    if (durationMs <= 0L) return 0L
    val lengths = fragments.map { it.text.length.coerceAtLeast(1).toLong() }
    val prefix = lengths.take(index).sum()
    return ((prefix + lengths[index] * fraction.coerceIn(0.0, 1.0)) * durationMs / lengths.sum()).toLong()
}
