package com.example.data.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.local.LocalCacheStore
import com.example.data.model.BookDetailDto
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Read-only playback snapshot for data-layer consumers.
 *
 * Playback itself remains owned by PlaybackService. This reader connects through
 * Media3 MediaController only long enough to read the current session and then
 * releases the controller. When no live session is available, the most recent
 * durable resume checkpoint identifies the cached book/source instead.
 */
data class PlaybackReadSnapshot(
    val book: BookDetailDto,
    val chapterIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val progressPercent: Double,
    val completed: Boolean,
)

class PlaybackReadRepository(
    context: Context,
    private val cacheStore: LocalCacheStore,
    private val resumeStore: PlaybackResumeStore,
) {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    suspend fun current(): PlaybackReadSnapshot? {
        val controllerState = withTimeoutOrNull(CONTROLLER_READ_TIMEOUT_MS) {
            readControllerState()
        }
        val durableSnapshot = if (controllerState == null) resumeStore.latestSnapshot() else null
        val bookId = controllerState?.bookId ?: durableSnapshot?.bookId ?: return null
        val requestedSource = playbackSourceOrNull(
            controllerState?.sourceCode ?: durableSnapshot?.sourceCode
        )

        val sourceMatchedBook = cacheStore.readBook(bookId, requestedSource)
            ?.takeIf { cached -> playbackSourceMatches(cached.selectedSource, requestedSource) }
        val book = sourceMatchedBook
            ?: if (requestedSource == null) cacheStore.readBook(bookId) else null
        if (book == null || book.chapters.isEmpty()) return null

        val sourceCode = requestedSource ?: book.selectedSource
        val resume = durableSnapshot
            ?.takeIf { snapshot ->
                snapshot.bookId == book.id &&
                    playbackSourceMatches(snapshot.sourceCode, requestedSource)
            }
            ?: resumeStore.get(book.id, sourceCode)
        val chapterIndex = (controllerState?.chapterIndex ?: resume?.chapterIndex ?: 0)
            .coerceIn(0, book.chapters.lastIndex)
        val positionMs = (controllerState?.positionMs ?: resume?.positionMs ?: 0L)
            .coerceAtLeast(0L)
        val durationMs = controllerState?.durationMs
            ?.takeIf { it > 0L }
            ?: book.chapters.getOrNull(chapterIndex)
                ?.durationSeconds
                ?.coerceAtLeast(0L)
                ?.times(1_000L)
                ?: 0L
        val completed = controllerState?.completed
            ?: (cacheStore.readProgress(book.id, sourceCode)?.completed == true)
        val progressPercent = estimateOverallProgressPercent(
            book = book,
            chapterIndex = chapterIndex,
            positionMs = positionMs,
            currentDurationMs = durationMs,
            completed = completed,
        )
        return PlaybackReadSnapshot(
            book = book,
            chapterIndex = chapterIndex,
            positionMs = positionMs,
            durationMs = durationMs,
            progressPercent = progressPercent,
            completed = completed,
        )
    }

    private suspend fun readControllerState(): ControllerState? {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = try {
            MediaController.Builder(appContext, token).buildAsync()
        } catch (_: Exception) {
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                mainExecutor.execute { MediaController.releaseFuture(future) }
            }
            future.addListener(
                controllerListener@{
                    if (!continuation.isActive) {
                        MediaController.releaseFuture(future)
                        return@controllerListener
                    }
                    val result = try {
                        val controller = future.get()
                        val item = controller.currentMediaItem
                        val index = controller.currentMediaItemIndex
                        if (item == null || index == C.INDEX_UNSET) {
                            null
                        } else {
                            val extras = item.mediaMetadata.extras
                            val bookId = extras?.getString(PlaybackMetadata.EXTRA_BOOK_ID).orEmpty()
                            if (bookId.isBlank()) {
                                null
                            } else {
                                val duration = controller.duration
                                    .takeIf { it != C.TIME_UNSET && it > 0L }
                                    ?: 0L
                                ControllerState(
                                    bookId = bookId,
                                    sourceCode = extras?.getString(PlaybackMetadata.EXTRA_SOURCE_CODE).orEmpty(),
                                    chapterIndex = index.coerceAtLeast(0),
                                    positionMs = controller.currentPosition.coerceAtLeast(0L),
                                    durationMs = duration,
                                    completed = controller.playbackState == Player.STATE_ENDED &&
                                        index == controller.mediaItemCount - 1,
                                )
                            }
                        }
                    } catch (_: Exception) {
                        null
                    }
                    if (continuation.isActive) continuation.resume(result)
                    MediaController.releaseFuture(future)
                },
                mainExecutor,
            )
        }
    }

    private data class ControllerState(
        val bookId: String,
        val sourceCode: String,
        val chapterIndex: Int,
        val positionMs: Long,
        val durationMs: Long,
        val completed: Boolean,
    )

    private companion object {
        const val CONTROLLER_READ_TIMEOUT_MS = 2_000L
    }
}
