package com.example.data.player

import com.example.data.source.StandaloneSourceRegistry

/**
 * Normalize source identity persisted in Media3 metadata or durable resume snapshots.
 * Blank and legacy "unknown" values mean that recovery is not constrained to a source.
 */
internal fun playbackSourceOrNull(sourceCode: String?): String? =
    sourceCode
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals(PLAYBACK_SOURCE_UNKNOWN, ignoreCase = true) }

/**
 * Current standalone book ids are source-qualified (for example `uknig:123`).
 * The prefix is a stronger identity signal than refreshable source metadata on a
 * cached/detail DTO, so resume lookup may safely fall back by book id for these ids.
 */
internal fun standalonePlaybackSourceFromBookId(bookId: String?): String? {
    val clean = bookId?.trim().orEmpty()
    val separator = clean.indexOf(':')
    if (separator <= 0 || separator >= clean.lastIndex) return null
    val source = clean.substring(0, separator).trim().lowercase()
    return source.takeIf(StandaloneSourceRegistry::isActive)
}

internal fun playbackSourceMatches(candidateSource: String?, requestedSource: String?): Boolean {
    val requested = playbackSourceOrNull(requestedSource) ?: return true
    val candidate = playbackSourceOrNull(candidateSource) ?: return false
    return candidate.equals(requested, ignoreCase = true)
}

private const val PLAYBACK_SOURCE_UNKNOWN = "unknown"
