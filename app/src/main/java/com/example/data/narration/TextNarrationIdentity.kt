package com.example.data.narration

import com.example.data.player.PlaybackResumeStore

internal const val TEXT_NARRATION_SOURCE = "text_narration"
internal const val TEXT_NARRATION_SCHEME = "abred-tts"
internal const val EXTRA_TEXT_CONTRACT = "abred.text_contract"

/** The audio identity also contains the immutable pronunciation/model contract. */
internal fun textFragmentId(audioChapterId: String): String = audioChapterId.substringBefore('|')
internal fun textChapterId(audioChapterId: String): String = textFragmentId(audioChapterId).substringBeforeLast(":f")
internal fun textChapterStarts(ids: List<String>): List<Int> = ids.indices.filter { it == 0 || textChapterId(ids[it]) != textChapterId(ids[it - 1]) }

internal fun textChapterTarget(ids: List<String>, currentIndex: Int, next: Boolean): Int? {
    if (currentIndex !in ids.indices) return null
    val starts = textChapterStarts(ids)
    val currentStart = starts.last { it <= currentIndex }
    return if (next) starts.firstOrNull { it > currentStart } else starts.lastOrNull { it < currentStart } ?: currentStart
}

internal data class NarrationStyleSwitchPosition(val fragmentIndex: Int, val positionMs: Long)

/** Preserve the same fragment when changing voice style; never seek within a different fragment. */
internal fun textNarrationStyleSwitchPosition(
    nextIds: List<String>,
    previousChapterId: String?,
    fallbackIndex: Int,
    positionMs: Long,
): NarrationStyleSwitchPosition {
    require(nextIds.isNotEmpty())
    val previousFragment = previousChapterId?.let(::textFragmentId)
    val matching = if (previousFragment == null) -1 else nextIds.indexOfFirst { textFragmentId(it) == previousFragment }
    return if (matching >= 0) NarrationStyleSwitchPosition(matching, positionMs.coerceAtLeast(0L))
    else NarrationStyleSwitchPosition(fallbackIndex.coerceIn(0, nextIds.lastIndex), 0L)
}

/** A changed model/pronunciation can change duration; retain the text anchor but never reuse its milliseconds. */
internal fun normalizeTextNarrationResume(ids: List<String>, saved: PlaybackResumeStore.Snapshot?): PlaybackResumeStore.Snapshot? {
    if (saved == null || ids.isEmpty()) return null
    if (saved.progressPercent >= 100.0) return saved.copy(chapterId = ids.first(), chapterIndex = 0, positionMs = 0, progressPercent = 0.0)
    val exact = ids.indexOf(saved.chapterId)
    if (exact >= 0) return saved.copy(chapterIndex = exact)
    val fragment = saved.chapterId?.let(::textFragmentId)
    val index = ids.indexOfFirst { textFragmentId(it) == fragment }.coerceAtLeast(0)
    return saved.copy(chapterId = ids[index], chapterIndex = index, positionMs = 0)
}
