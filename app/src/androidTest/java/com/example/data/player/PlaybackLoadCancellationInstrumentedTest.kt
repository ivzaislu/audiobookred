package com.example.data.player

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.backup.UserDataRestoreGate
import com.example.data.local.LocalCacheStore
import com.example.data.model.BookDetailDto
import com.example.data.model.ChapterDto
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(UnstableApi::class)
class PlaybackLoadCancellationInstrumentedTest {
    @Test
    fun pauseCancelsDelayedPlaylistAndLateSuccessCannotStartIt() = Fixture().use { f ->
        val load = f.startLoad()
        f.main { f.manager.pause() }
        f.items.complete(listOf(f.item()))
        f.join(load)
        f.main {
            assertEquals(0, f.player.mediaItemCount)
            assertFalse(f.player.playWhenReady)
        }
    }

    @Test
    fun pauseCancelsDelayedFailureWithoutClearingExistingPlaylist() = Fixture().use { f ->
        f.main { f.player.setMediaItem(MediaItem.Builder().setMediaId("existing").setUri("https://example.test/old").build()) }
        f.instrumentation.waitForIdleSync()
        val load = f.startLoad()
        f.main { f.manager.pause() }
        f.items.completeExceptionally(IllegalStateException("late failure"))
        f.join(load)
        f.main {
            assertEquals("existing", f.player.currentMediaItem?.mediaId)
            assertFalse(f.player.playWhenReady)
        }
    }

    @Test
    fun oldSnapshotCannotOverwriteNewBookWhileItsPlaylistIsStillLoading() = Fixture(recoverSnapshot = true).use { f ->
        assertTrue(f.snapshotRequested.await(10, TimeUnit.SECONDS))
        lateinit var recovery: Job
        f.main { recovery = requireNotNull(f.manager.pendingSnapshotRecoveryJob) }
        val load = f.startLoad()
        f.snapshot.complete(f.oldBook)
        f.join(recovery)
        f.main { assertEquals(f.book.id, f.manager.state.value.book?.id) }
        f.main { f.manager.pause() }
        f.items.complete(listOf(f.item()))
        f.join(load)
    }

    @Test
    fun pendingLoadPublishesRequestedTimeInsteadOfPreviousAudioClock() = Fixture().use { f ->
        val load = f.startLoad(42_000L)
        // Forces a controller state refresh while playlist construction is suspended.
        f.main { f.manager.setSpeed(1.25f) }
        f.instrumentation.waitForIdleSync()
        f.main {
            val state = f.manager.state.value
            assertEquals(f.book.id, state.book?.id)
            assertEquals(42_000L, state.positionMs)
            assertEquals(120_000L, state.durationMs)
            assertTrue(state.isLoading)
            assertFalse(state.isPlaying)
            f.manager.pause()
        }
        f.items.complete(listOf(f.item()))
        f.join(load)
    }

    private class Fixture(recoverSnapshot: Boolean = false) : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val items = CompletableDeferred<List<MediaItem>>()
        private val requested = CountDownLatch(1)
        val snapshotRequested = CountDownLatch(1)
        val snapshot = CompletableDeferred<BookDetailDto?>()
        val book = BookDetailDto(id = "load-test:new", title = "New", selectedSource = "test",
            chapters = listOf(ChapterDto(id = "new-1", position = 0, title = "Chapter", durationSeconds = 120L)))
        val oldBook = book.copy(id = "load-test:old", title = "Old")
        lateinit var player: ExoPlayer
        lateinit var session: MediaSession
        lateinit var manager: AudiobookPlayerManager
        private val connected = CountDownLatch(1)
        private val resumeStore = PlaybackResumeStore(instrumentation.targetContext)

        init {
            UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
            if (recoverSnapshot) resumeStore.saveImmediate(oldBook.id, "test", "new-1", 0, 1000L, 1f)
            main {
                val context = instrumentation.targetContext
                player = ExoPlayer.Builder(context).build()
                session = MediaSession.Builder(context, player).setCallback(object : MediaSession.Callback {
                    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
                        connected.countDown()
                    }
                }).build()
                manager = AudiobookPlayerManager(context, resumeStore, LocalCacheStore(context),
                    mediaItemsBuilder = { requested.countDown(); items.await() },
                    controllerFactory = { MediaController.Builder(it, session.token).buildAsync() },
                    snapshotBookLoader = {
                        if (recoverSnapshot) { snapshotRequested.countDown(); snapshot.await() } else null
                    },
                )
                // Bypass the production first-frame connection delay without starting a load.
                manager.setSpeed(1f)
            }
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        }

        fun item() = MediaItem.Builder().setMediaId("new")
            .setUri("https://example.test/new").build()
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun startLoad(positionMs: Long = 0L): Job {
            main { manager.play(book, 0, positionMs) }
            assertTrue(requested.await(10, TimeUnit.SECONDS))
            lateinit var job: Job
            main { job = requireNotNull(manager.pendingMediaLoadJob) }
            return job
        }
        fun join(job: Job) {
            runBlocking { withTimeout(10_000L) { job.join() } }
            instrumentation.waitForIdleSync()
        }
        override fun close() {
            main { manager.release(); session.release(); player.release() }
            resumeStore.clear(oldBook.id)
        }
    }
}
