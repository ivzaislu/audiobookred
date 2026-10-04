package com.example.data.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.settings.PlayerSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Applies sleep-timer policy to the service-owned Player.
 *
 * The persistent timer store remains shared with the UI. This controller owns
 * only playback reactions: pause-at-chapter-end and minute timer expiry.
 */
@OptIn(UnstableApi::class)
internal class PlaybackSleepTimerController(
    private val player: ExoPlayer,
    private val store: PlaybackSleepTimerStore,
    private val settingsStore: PlayerSettingsStore,
    private val scope: CoroutineScope,
    private val saveCheckpoint: () -> Unit,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            store.state.collectLatest { timer ->
                applyPauseAtEndPolicy()
                if (timer.mode != SleepTimerMode.MINUTES) return@collectLatest

                val remaining = timer.remainingMs()
                if (remaining > 0L) delay(remaining)

                val current = store.state.value
                if (
                    current.mode == SleepTimerMode.MINUTES &&
                    current.endAtMs == timer.endAtMs
                ) {
                    if (player.mediaItemCount > 0) {
                        player.pause()
                        saveCheckpoint()
                    }
                    store.clear()
                }
            }
        }
    }

    fun applyPauseAtEndPolicy() {
        val sleepAtChapterEnd = store.state.value.mode == SleepTimerMode.END_OF_CHAPTER
        player.setPauseAtEndOfMediaItems(
            sleepAtChapterEnd || !settingsStore.state.value.autoNextChapter
        )
    }

    fun onPlaybackEnded() {
        if (store.state.value.mode == SleepTimerMode.END_OF_CHAPTER) {
            store.clear()
        }
    }

    fun onAutoTransition(oldIndex: Int) {
        if (!matchesEndOfChapterTimer(oldIndex)) return
        player.pause()
        store.clear()
    }

    fun onSeekTargetChanged(newIndex: Int) {
        val timer = store.state.value
        if (timer.mode != SleepTimerMode.END_OF_CHAPTER) return
        if (newIndex == timer.chapterIndex) return
        store.clear()
    }

    fun close() {
        store.close()
    }

    private fun matchesEndOfChapterTimer(oldIndex: Int): Boolean {
        val timer = store.state.value
        if (timer.mode != SleepTimerMode.END_OF_CHAPTER || oldIndex != timer.chapterIndex) return false
        if (oldIndex !in 0 until player.mediaItemCount) return false

        val extras = runCatching {
            player.getMediaItemAt(oldIndex).mediaMetadata.extras
        }.getOrNull() ?: return false
        val bookId = extras.getString(PlaybackMetadata.EXTRA_BOOK_ID).orEmpty()
        val sourceCode = extras.getString(PlaybackMetadata.EXTRA_SOURCE_CODE).orEmpty()
        return bookId == timer.bookId &&
            (timer.sourceCode == null || sourceCode == timer.sourceCode)
    }
}
