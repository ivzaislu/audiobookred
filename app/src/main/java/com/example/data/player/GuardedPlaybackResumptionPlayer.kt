package com.example.data.player

import androidx.annotation.OptIn
import com.example.data.narration.*
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
internal data class SessionPlaybackResumption(
    val mediaItems: MediaSession.MediaItemsWithStartPosition,
    val isAuthorized: () -> Boolean = { true },
    val onApplied: () -> Unit = {},
)

/**
 * Media3 1.11 calls play even when its asynchronous resumption future fails.
 * Keep that callback synchronous/empty, then own empty-player play here so a
 * stale result or failure cannot seek/replay whatever book has since ended.
 * All Player access and final application happen on the application thread.
 */
@OptIn(UnstableApi::class)
internal class GuardedPlaybackResumptionPlayer(
    private val underlyingPlayer: Player,
    private val scope: CoroutineScope,
    private val onPlaybackIntentChanged: () -> Unit = {},
    private val shouldIgnoreQueuedPlayback: () -> Boolean = { false },
    private val prepareResumption: suspend () -> SessionPlaybackResumption,
) : ForwardingPlayer(underlyingPlayer) {
    private val guard = PlaybackRecoveryGuard()
    private var closed = false
    internal var pendingResumptionJob: Job? = null
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // SDK prepare(empty) can dispatch after play() starts our request.
            // It has no book to finish. Real playlist changes invalidate it.
            if (player.mediaItemCount > 0) guard.invalidate()
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) onPlaybackIntentChanged()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) onPlaybackIntentChanged()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) onPlaybackIntentChanged()
        }
    }

    init {
        underlyingPlayer.addListener(listener)
    }

    fun cancelPendingResumption() {
        onPlaybackIntentChanged()
        guard.invalidate()
        pendingResumptionJob?.cancel()
    }

    override fun play() {
        if (shouldIgnoreQueuedPlayback()) return
        cancelPendingResumption()
        if (closed) return
        if (underlyingPlayer.currentMediaItem != null || underlyingPlayer.mediaItemCount > 0) {
            super.play()
            return
        }
        val request = guard.capture()
        tracePlaybackEvent("guarded_resumption_requested", underlyingPlayer)
        pendingResumptionJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                val prepared = withContext(Dispatchers.IO) { prepareResumption() }
                if (closed || !guard.isCurrent(request) ||
                    underlyingPlayer.mediaItemCount != 0 || !prepared.isAuthorized()
                ) {
                    tracePlaybackEvent("guarded_resumption_discarded", underlyingPlayer)
                    return@launch
                }
                val items = prepared.mediaItems
                if (items.mediaItems.isEmpty()) return@launch
                // No suspension between the final guard and playback handoff.
                underlyingPlayer.setMediaItems(items.mediaItems, items.startIndex, items.startPositionMs)
                prepared.onApplied()
                underlyingPlayer.prepare()
                underlyingPlayer.play()
                tracePlaybackEvent("guarded_resumption_applied", underlyingPlayer)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                tracePlaybackEvent("guarded_resumption_failed", underlyingPlayer)
                // In particular, do not call play/prepare/seek on failure.
            }
        }
    }

    private fun seekTextChapter(next: Boolean): Boolean {
        val player = underlyingPlayer
        if (player.currentMediaItem?.mediaMetadata?.extras?.getString(PlaybackMetadata.EXTRA_SOURCE_CODE) != TEXT_NARRATION_SOURCE) return false
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaMetadata.extras?.getString(PlaybackMetadata.EXTRA_CHAPTER_ID).orEmpty() }
        textChapterTarget(ids, player.currentMediaItemIndex, next)?.let { player.seekTo(it, 0L) }
        return true
    }

    override fun seekToNextMediaItem() { if (!seekTextChapter(true)) super.seekToNextMediaItem() }
    override fun seekToPreviousMediaItem() { if (!seekTextChapter(false)) super.seekToPreviousMediaItem() }
    override fun seekToNext() { if (!seekTextChapter(true)) super.seekToNext() }
    override fun seekToPrevious() { if (!seekTextChapter(false)) super.seekToPrevious() }

    override fun pause() {
        cancelPendingResumption()
        super.pause()
    }
    override fun stop() {
        cancelPendingResumption()
        super.stop()
    }
    override fun prepare() {
        if (shouldIgnoreQueuedPlayback()) return
        cancelPendingResumption()
        super.prepare()
    }
    override fun clearMediaItems() {
        cancelPendingResumption()
        super.clearMediaItems()
    }
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }
    override fun setMediaItems(mediaItems: List<MediaItem>) {
        cancelPendingResumption()
        super.setMediaItems(mediaItems)
    }
    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        cancelPendingResumption()
        super.setMediaItems(mediaItems, resetPosition)
    }
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        cancelPendingResumption()
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    fun onPlayerInteractionFinished(commands: Player.Commands) {
        if (listOf(
                Player.COMMAND_STOP, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_SPEED_AND_PITCH,
            ).any(commands::contains)
        ) cancelPendingResumption()
    }

    fun close() {
        closed = true
        cancelPendingResumption()
        underlyingPlayer.removeListener(listener)
    }
}

@OptIn(UnstableApi::class)
internal fun emptyPlaybackResumptionItems() =
    MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, 0L)

