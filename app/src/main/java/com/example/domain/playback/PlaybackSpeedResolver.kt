package com.example.domain.playback

internal data class PlaybackSpeedCheckpoint(
    val chapterId: String?,
    val speed: Float,
    val preferOverPersisted: Boolean = false,
)

/**
 * Pure playback-speed selection shared by preparation code and JVM tests.
 * `persisted` comes from Room; `immediate` comes from the source-aware resume
 * store and may take precedence when it represents a newer local checkpoint.
 */
internal fun resolvePlaybackSpeed(
    rememberBookSpeed: Boolean,
    defaultSpeed: Float,
    chapterIds: Set<String>,
    sourceVariantCount: Int,
    persisted: PlaybackSpeedCheckpoint?,
    immediate: PlaybackSpeedCheckpoint?,
): Float {
    val safeDefault = sanitizePlaybackSpeed(defaultSpeed) ?: 1f
    if (!rememberBookSpeed) return safeDefault

    val persistedMatches = persisted != null && (
        persisted.chapterId?.let(chapterIds::contains) == true ||
            (persisted.chapterId == null && sourceVariantCount <= 1)
        )
    val immediateSpeed = immediate?.speed?.let(::sanitizePlaybackSpeed)
    val persistedSpeed = persisted?.speed?.let(::sanitizePlaybackSpeed)

    return when {
        immediate?.preferOverPersisted == true && immediateSpeed != null -> immediateSpeed
        persistedMatches && persistedSpeed != null -> persistedSpeed
        immediateSpeed != null -> immediateSpeed
        else -> safeDefault
    }
}

private fun sanitizePlaybackSpeed(speed: Float): Float? = speed
    .takeIf { it.isFinite() }
    ?.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)

private const val MIN_PLAYBACK_SPEED = 0.5f
private const val MAX_PLAYBACK_SPEED = 3.0f
