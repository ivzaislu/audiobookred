package com.example.domain.playback

import com.example.data.model.BookDetailDto
import com.example.data.player.PlaybackResumeStore
import com.example.data.settings.PlayerSettingsStore
import javax.inject.Inject
import javax.inject.Singleton

internal data class RememberedPlaybackSpeedCheckpoint(
    val chapterId: String,
    val chapterIndex: Int,
    val positionMs: Long,
    val speed: Float,
)

internal fun resolveRememberedPlaybackSpeedCheckpoint(
    rememberBookSpeed: Boolean,
    chapterIds: List<String>,
    chapterIndex: Int,
    positionMs: Long,
    speed: Float,
): RememberedPlaybackSpeedCheckpoint? {
    if (!rememberBookSpeed || chapterIds.isEmpty() || !speed.isFinite()) return null
    val index = chapterIndex.coerceIn(0, chapterIds.lastIndex)
    return RememberedPlaybackSpeedCheckpoint(
        chapterId = chapterIds[index],
        chapterIndex = index,
        positionMs = positionMs.coerceAtLeast(0L),
        speed = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED),
    )
}

/** Persists an explicit user playback-speed change before the next periodic progress save. */
@Singleton
class RememberPlaybackSpeedUseCase @Inject constructor(
    private val resumeStore: PlaybackResumeStore,
    private val settingsStore: PlayerSettingsStore,
) {
    fun remember(
        book: BookDetailDto,
        chapterIndex: Int,
        positionMs: Long,
        speed: Float,
    ) {
        val checkpoint = resolveRememberedPlaybackSpeedCheckpoint(
            rememberBookSpeed = settingsStore.state.value.rememberBookSpeed,
            chapterIds = book.chapters.map { it.id },
            chapterIndex = chapterIndex,
            positionMs = positionMs,
            speed = speed,
        ) ?: return
        val existing = resumeStore.get(book.id, book.selectedSource)
        resumeStore.saveImmediate(
            bookId = book.id,
            sourceCode = book.selectedSource,
            chapterId = checkpoint.chapterId,
            chapterIndex = checkpoint.chapterIndex,
            positionMs = checkpoint.positionMs,
            speed = checkpoint.speed,
            progressPercent = existing?.progressPercent,
            allowZero = true,
        )
    }
}

private const val MIN_PLAYBACK_SPEED = 0.5f
private const val MAX_PLAYBACK_SPEED = 3.0f
