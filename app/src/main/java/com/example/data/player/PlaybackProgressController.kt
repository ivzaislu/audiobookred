package com.example.data.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import com.example.data.backup.UserDataRestoreGate
import com.example.data.local.LocalCacheStore
import com.example.data.model.ProgressResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Owns durable playback checkpoints and whole-book progress calculation.
 *
 * PlaybackService owns the Player lifecycle; this controller only reads the
 * current timeline/position and mirrors checkpoints into the local stores.
 */
internal class PlaybackProgressController(
    private val player: Player,
    private val resumeStore: PlaybackResumeStore,
    private val localCacheStore: LocalCacheStore,
    private val scope: CoroutineScope,
) {
    private val progressWrites =
        CoalescingWriteQueue<ProgressWriteKey, ProgressCheckpoint> { write ->
            ProgressWriteKey(write.bookId, write.sourceCode.trim().lowercase())
        }
    private var timelineSnapshot: PlaybackTimelineSnapshot? = null
    private var writerStarted = false

    fun start() {
        if (writerStarted) return
        writerStarted = true
        scope.launch(Dispatchers.IO) {
            while (true) {
                val batch = progressWrites.nextBatch() ?: break
                for (write in batch) {
                    try {
                        persistProgress(write)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "Failed to mirror local playback progress", error)
                    }
                }
            }
        }
    }

    fun close() {
        progressWrites.close()
    }

    fun onTimelineChanged() {
        rebuildTimelineSnapshot()
    }

    fun saveProgressAsync(
        completedOverride: Boolean? = null,
        allowZero: Boolean = false,
    ) {
        if (player.mediaItemCount == 0) return
        val item = player.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return
        val bookId = extras.getString(PlaybackMetadata.EXTRA_BOOK_ID).orEmpty()
        if (bookId.isBlank()) return
        val playbackWriteEpoch = UserDataRestoreGate.capturePlaybackWriteEpoch() ?: return

        val chapterId = extras.getString(PlaybackMetadata.EXTRA_CHAPTER_ID)
        val sourceCode = extras.getString(PlaybackMetadata.EXTRA_SOURCE_CODE).orEmpty()
        val bookSourceId = extras.getString(PlaybackMetadata.EXTRA_BOOK_SOURCE_ID)
            ?.takeIf { it.isNotBlank() }
        val chapterIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        val speed = player.playbackParameters.speed.toDouble()
        val completed = completedOverride ?: (
            player.playbackState == Player.STATE_ENDED &&
                duration > 0L &&
                position >= duration - 1_500L &&
                chapterIndex == player.mediaItemCount - 1
            )

        if (position > 0L || allowZero) {
            val progressPercent = estimatePlaybackProgressPercent(
                bookId = bookId,
                chapterIndex = chapterIndex,
                positionMs = position,
                currentDurationMs = duration,
                completed = completed,
            )
            resumeStore.save(
                bookId = bookId,
                sourceCode = sourceCode,
                chapterId = chapterId,
                chapterIndex = chapterIndex,
                positionMs = position,
                speed = speed.toFloat(),
                progressPercent = progressPercent,
                allowZero = allowZero,
            )
        }

        // Do not overwrite a valid local checkpoint with an incidental 0 ms.
        // Only an explicit seek or real completion may persist zero.
        if (position <= 0L && completedOverride != true && !allowZero) return

        progressWrites.offer(
            ProgressCheckpoint(
                bookId = bookId,
                sourceCode = sourceCode,
                bookSourceId = bookSourceId,
                chapterId = chapterId,
                chapterIndex = chapterIndex,
                positionMs = position,
                playbackSpeed = speed,
                completed = completed,
                playbackWriteEpoch = playbackWriteEpoch,
            )
        )
    }

    fun saveAutoTransitionCheckpoint(oldPosition: Player.PositionInfo) {
        if (player.mediaItemCount == 0) return
        val oldIndex = oldPosition.mediaItemIndex
        if (oldIndex == C.INDEX_UNSET || oldIndex !in 0 until player.mediaItemCount) return
        val item = runCatching { player.getMediaItemAt(oldIndex) }.getOrNull() ?: return
        val extras = item.mediaMetadata.extras ?: return
        val bookId = extras.getString(PlaybackMetadata.EXTRA_BOOK_ID).orEmpty()
        if (bookId.isBlank()) return
        val playbackWriteEpoch = UserDataRestoreGate.capturePlaybackWriteEpoch() ?: return
        val sourceCode = extras.getString(PlaybackMetadata.EXTRA_SOURCE_CODE).orEmpty()
        val position = oldPosition.positionMs.coerceAtLeast(0L)
        if (position <= 0L) return
        val chapterId = extras.getString(PlaybackMetadata.EXTRA_CHAPTER_ID)
        val bookSourceId = extras.getString(PlaybackMetadata.EXTRA_BOOK_SOURCE_ID)
            ?.takeIf { it.isNotBlank() }
        val speed = player.playbackParameters.speed
        val duration = if (extras.containsKey(EXTRA_CHAPTER_DURATION_MS)) {
            extras.getLong(EXTRA_CHAPTER_DURATION_MS).coerceAtLeast(0L)
        } else {
            0L
        }
        val progressPercent = estimatePlaybackProgressPercent(
            bookId = bookId,
            chapterIndex = oldIndex,
            positionMs = position,
            currentDurationMs = duration,
            completed = false,
        )

        // The current player already points at the new item. Persist the position
        // supplied by Media3 for the item that actually ended.
        resumeStore.saveImmediate(
            bookId = bookId,
            sourceCode = sourceCode,
            chapterId = chapterId,
            chapterIndex = oldIndex,
            positionMs = position,
            speed = speed,
            progressPercent = progressPercent,
        )
        progressWrites.offer(
            ProgressCheckpoint(
                bookId = bookId,
                sourceCode = sourceCode,
                bookSourceId = bookSourceId,
                chapterId = chapterId,
                chapterIndex = oldIndex,
                positionMs = position,
                playbackSpeed = speed.toDouble(),
                completed = false,
                playbackWriteEpoch = playbackWriteEpoch,
            )
        )
    }

    fun saveLocalSnapshot(allowZero: Boolean = false) {
        if (player.mediaItemCount == 0) return
        val item = player.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return
        val bookId = extras.getString(PlaybackMetadata.EXTRA_BOOK_ID).orEmpty()
        if (bookId.isBlank()) return
        val position = player.currentPosition.coerceAtLeast(0L)
        if (position <= 0L && !allowZero) return
        val chapterIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val progressPercent = estimatePlaybackProgressPercent(
            bookId = bookId,
            chapterIndex = chapterIndex,
            positionMs = position,
            currentDurationMs = duration,
        )
        resumeStore.saveImmediate(
            bookId = bookId,
            sourceCode = extras.getString(PlaybackMetadata.EXTRA_SOURCE_CODE).orEmpty(),
            chapterId = extras.getString(PlaybackMetadata.EXTRA_CHAPTER_ID),
            chapterIndex = chapterIndex,
            positionMs = position,
            speed = player.playbackParameters.speed,
            progressPercent = progressPercent,
            allowZero = allowZero,
        )
    }

    private fun estimatePlaybackProgressPercent(
        bookId: String,
        chapterIndex: Int,
        positionMs: Long,
        currentDurationMs: Long,
        completed: Boolean = false,
    ): Double? {
        if (player.mediaItemCount <= 0 || chapterIndex !in 0 until player.mediaItemCount) return null

        var snapshot = timelineSnapshot
        if (
            snapshot == null ||
            snapshot.bookId != bookId ||
            snapshot.chapterCount != player.mediaItemCount
        ) {
            snapshot = rebuildTimelineSnapshot()
        }
        if (snapshot?.bookId != bookId) return null

        return snapshot.progressPercent(
            chapterIndex = chapterIndex,
            positionMs = positionMs,
            currentDurationMs = currentDurationMs,
            completed = completed,
        )
    }

    private fun rebuildTimelineSnapshot(): PlaybackTimelineSnapshot? {
        if (player.mediaItemCount <= 0) {
            timelineSnapshot = null
            return null
        }

        val items = ArrayList<PlaybackTimelineItem>(player.mediaItemCount)
        for (index in 0 until player.mediaItemCount) {
            val mediaItem = runCatching { player.getMediaItemAt(index) }.getOrNull()
                ?: return clearTimelineSnapshot()
            val extras = mediaItem.mediaMetadata.extras
                ?: return clearTimelineSnapshot()
            val bookId = extras.getString(PlaybackMetadata.EXTRA_BOOK_ID).orEmpty()
            if (
                bookId.isBlank() ||
                !extras.containsKey(EXTRA_CHAPTER_DURATION_MS) ||
                !extras.containsKey(EXTRA_BOOK_DURATION_MS)
            ) {
                return clearTimelineSnapshot()
            }

            items += PlaybackTimelineItem(
                bookId = bookId,
                chapterDurationMs = extras.getLong(EXTRA_CHAPTER_DURATION_MS),
                bookDurationMs = extras.getLong(EXTRA_BOOK_DURATION_MS),
            )
        }

        return PlaybackTimelineSnapshot.from(items).also {
            timelineSnapshot = it
        }
    }

    private fun clearTimelineSnapshot(): PlaybackTimelineSnapshot? {
        timelineSnapshot = null
        return null
    }

    private suspend fun persistProgress(checkpoint: ProgressCheckpoint) {
        localCacheStore.writeProgress(
            bookId = checkpoint.bookId,
            sourceCode = checkpoint.sourceCode,
            value = ProgressResponse(
                bookId = checkpoint.bookId,
                bookSourceId = checkpoint.bookSourceId,
                chapterId = checkpoint.chapterId,
                chapterIndex = checkpoint.chapterIndex,
                positionMs = checkpoint.positionMs,
                playbackSpeed = checkpoint.playbackSpeed,
                completed = checkpoint.completed,
            ),
            playbackWriteEpoch = checkpoint.playbackWriteEpoch,
        )
    }

    private data class ProgressWriteKey(
        val bookId: String,
        val sourceCode: String,
    )

    private data class ProgressCheckpoint(
        val bookId: String,
        val sourceCode: String,
        val bookSourceId: String?,
        val chapterId: String?,
        val chapterIndex: Int,
        val positionMs: Long,
        val playbackSpeed: Double,
        val completed: Boolean,
        val playbackWriteEpoch: Long,
    )

    private companion object {
        const val TAG = "PlaybackProgress"
    }
}
