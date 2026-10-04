package com.example.domain.playback

/** Resolves a bookmark chapter against the currently selected source. */
internal fun resolveBookmarkChapterIndex(
    chapterIds: List<String>,
    bookmarkChapterId: String?,
    bookmarkChapterIndex: Int,
): Int {
    val byId = bookmarkChapterId?.let(chapterIds::indexOf) ?: -1
    if (byId >= 0) return byId
    return bookmarkChapterIndex.coerceIn(0, chapterIds.lastIndex.coerceAtLeast(0))
}

/**
 * Bookmark rows do not carry audio-source identity themselves. Prefer the
 * explicit book source selected by the user and fall back to the source of the
 * latest durable checkpoint so bookmark playback resumes with the same
 * source-specific progress and speed.
 */
internal fun resolveBookmarkPlaybackSource(
    preferredSource: String?,
    resumeSource: String?,
): String? = normalizeBookmarkSource(preferredSource)
    ?: normalizeBookmarkSource(resumeSource)

/**
 * LocalCacheStore.readBook() intentionally falls back to another cached variant.
 * That is useful generally, but a bookmark source hint must not silently accept
 * a different variant or it can restore another source's progress and speed.
 */
internal fun bookmarkCachedSourceMatchesHint(
    cachedSource: String?,
    sourceHint: String?,
): Boolean {
    val normalizedHint = normalizeBookmarkSource(sourceHint) ?: return true
    val normalizedCached = normalizeBookmarkSource(cachedSource) ?: return false
    return normalizedCached.equals(normalizedHint, ignoreCase = true)
}

private fun normalizeBookmarkSource(value: String?): String? = value
    ?.trim()
    ?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
