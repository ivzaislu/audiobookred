package com.example.ui

/**
 * Playback identity is source-aware for UI progress rendering. The same canonical
 * book may expose independent audio sources in legacy cached data.
 */
internal fun isSamePlaybackSource(
    activeBookId: String?,
    activeBookSourceId: String?,
    activeSourceCode: String?,
    targetBookId: String,
    targetBookSourceId: String?,
    targetSourceCode: String?,
): Boolean {
    if (activeBookId == null || activeBookId != targetBookId) return false

    val activeSourceId = activeBookSourceId?.trim().orEmpty()
    val targetSourceId = targetBookSourceId?.trim().orEmpty()
    if (activeSourceId.isNotEmpty() && targetSourceId.isNotEmpty()) {
        return activeSourceId == targetSourceId
    }

    val activeCode = activeSourceCode?.trim().orEmpty()
    val targetCode = targetSourceCode?.trim().orEmpty()
    if (activeCode.isNotEmpty() && targetCode.isNotEmpty()) {
        return activeCode == targetCode
    }

    return false
}

/**
 * Do not rebuild an already active book when the user opens it from Home's
 * «Продолжить» rail and presses «Продолжить слушать».
 *
 * Standalone live book ids are source-qualified (for example `uknig:...` or
 * `audioboo:...`), so equality of book ids already identifies the playback
 * source. Source metadata on a freshly refreshed/cached detail may temporarily
 * be missing or stale; treating that as another source used to call resume()
 * again and could replace the active Media3 queue from position zero.
 *
 * Source-aware comparison is intentionally retained in [isSamePlaybackSource]
 * for progress rendering, but it must not force a restart of the active book.
 */
internal fun shouldResumeBookPlayback(
    activeBookId: String?,
    targetBookId: String,
): Boolean = activeBookId != targetBookId

/**
 * Открытие full player является явным возвратом к playback UI, поэтому ранее
 * скрытый mini-player той же книги после выхода должен снова стать видимым.
 */
internal fun shouldRestoreMiniPlayerAfterFullPlayerOpen(
    dismissedBookId: String?,
    activeBookId: String?
): Boolean = dismissedBookId != null && dismissedBookId == activeBookId


internal fun shouldDeferPlayerNavigationForPreparation(
    requiresResume: Boolean,
    usesRuTrackerTorrServe: Boolean,
): Boolean = requiresResume && usesRuTrackerTorrServe

internal fun shouldOpenPlayerForReadyPreparation(
    preparationActive: Boolean,
    preparationReady: Boolean,
    hasPreparedBook: Boolean,
): Boolean = preparationActive && preparationReady && hasPreparedBook


internal fun shouldMarkTorrServePreparationReady(
    pendingBookId: String?,
    startedBookId: String,
    startedSourceCode: String?,
): Boolean =
    pendingBookId != null &&
        pendingBookId == startedBookId &&
        startedSourceCode.equals("rutracker", ignoreCase = true)
