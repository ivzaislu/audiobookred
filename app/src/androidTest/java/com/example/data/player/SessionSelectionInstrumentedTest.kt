package com.example.data.player

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

@OptIn(UnstableApi::class)
class SessionSelectionInstrumentedTest {
    @Test
    fun naturalChapterTransitionAndEndDoNotCancelAnExplicitNewSelection() = Fixture().use { f ->
        val playing = CountDownLatch(1)
        val ended = CountDownLatch(1)
        f.main {
            f.player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) playing.countDown() }
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended.countDown() }
            })
            f.player.setMediaSources(List(2) {
                SilenceMediaSource.Factory().setDurationUs(500_000L).createMediaSource()
            })
            f.player.prepare()
            f.player.play()
        }
        assertTrue(playing.await(10, TimeUnit.SECONDS))
        f.flush()
        f.requestSelection()
        assertTrue(ended.await(10, TimeUnit.SECONDS))
        // Empty handoff avoids any network audio; acceptance is the regression assertion.
        f.prepared.complete(emptyPlaybackResumptionItems())
        assertTrue(f.finished.await(10, TimeUnit.SECONDS))
        f.main { assertEquals(1, f.accepted.get()) }
    }

    @Test
    fun cancellingOldFutureCannotCancelNewRequestStartedByItsListener() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val selection = GuardedSessionPreparation(scope)
        val oldValue = CompletableDeferred<String>()
        val requested = CountDownLatch(1)
        lateinit var next: ListenableFuture<String>
        try {
            instrumentation.runOnMainSync {
                val old = selection.future(owner = "old", isAuthorized = { true },
                    prepare = { requested.countDown(); oldValue.await() })
                old.addListener({
                    next = selection.future(owner = "new", isAuthorized = { true }, prepare = { "new" })
                }, MoreExecutors.directExecutor())
            }
            assertTrue(requested.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { selection.invalidate() }
            assertEquals("new", next.get(10, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { selection.invalidate(); scope.cancel() }
        }
    }

    @Test
    fun delayedSelectionCannotReplaceBookChosenByAnotherController() = Fixture().use { f ->
        f.requestSelection()
        f.main { f.phone.setMediaItem(f.item("phone")) }
        f.flush()
        f.prepared.complete(f.result())
        f.flush()
        f.main {
            assertEquals("phone", f.player.currentMediaItem?.mediaId)
            assertEquals(1f, f.player.playbackParameters.speed)
            assertEquals(0, f.accepted.get())
        }
        val explicitPlay = CountDownLatch(1)
        f.main {
            f.player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) explicitPlay.countDown()
                }
            })
            f.player.setMediaSource(SilenceMediaSource.Factory().setDurationUs(200_000L).createMediaSource())
            f.car.play()
        }
        assertTrue("A later explicit Play must work", explicitPlay.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun pauseCancelsSelectionAndItsAlreadyQueuedPreparePlay() = Fixture().use { f ->
        f.main { f.player.setMediaItem(f.item("current")) }
        f.flush()
        f.requestSelection(queuePlay = true)
        f.main { f.phone.pause() }
        assertTrue(f.finished.await(10, TimeUnit.SECONDS))
        f.prepared.complete(f.result())
        f.flush()
        f.main {
            assertEquals("current", f.player.currentMediaItem?.mediaId)
            assertFalse(f.player.playWhenReady)
            assertEquals(Player.STATE_IDLE, f.player.playbackState)
            assertEquals(0, f.accepted.get())
        }
    }

    @Test
    fun invalidEpochRejectsPlaylistAndSpeedAtFinalHandoff() = Fixture().use { f ->
        f.requestSelection()
        f.main { f.authorized = false }
        f.prepared.complete(f.result())
        assertTrue(f.finished.await(10, TimeUnit.SECONDS))
        f.main {
            assertEquals(0, f.player.mediaItemCount)
            assertEquals(1f, f.player.playbackParameters.speed)
            assertEquals(0, f.accepted.get())
        }
    }

    @Test
    fun validSelectionAppliesPlaylistAndSpeed() = Fixture().use { f ->
        f.requestSelection()
        f.prepared.complete(f.result())
        assertTrue(f.finished.await(10, TimeUnit.SECONDS))
        f.main {
            assertEquals("prepared", f.player.currentMediaItem?.mediaId)
            assertEquals(2f, f.player.playbackParameters.speed)
            assertEquals(1, f.accepted.get())
        }
    }

    @Test
    fun latePreparationFailureDoesNotChangeAnotherBook() = Fixture().use { f ->
        f.requestSelection()
        f.main { f.phone.setMediaItem(f.item("phone")) }
        f.flush()
        f.prepared.completeExceptionally(IllegalStateException("late failure"))
        f.flush()
        f.main {
            assertEquals("phone", f.player.currentMediaItem?.mediaId)
            assertEquals(0, f.accepted.get())
        }
    }

    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val selection = GuardedSessionPreparation(scope)
        val prepared = CompletableDeferred<MediaSession.MediaItemsWithStartPosition>()
        private val requested = CountDownLatch(1)
        private val queued = CountDownLatch(2)
        val finished = CountDownLatch(1)
        val accepted = AtomicInteger()
        var authorized = true
        lateinit var player: ExoPlayer
        private lateinit var wrapper: GuardedPlaybackResumptionPlayer
        private lateinit var session: MediaSession
        lateinit var car: MediaController
        lateinit var phone: MediaController

        init {
            lateinit var carFuture: ListenableFuture<MediaController>
            lateinit var phoneFuture: ListenableFuture<MediaController>
            main {
                val context = instrumentation.targetContext
                player = ExoPlayer.Builder(context).build()
                wrapper = GuardedPlaybackResumptionPlayer(player, scope, selection::invalidate,
                    { selection.shouldIgnoreQueuedPlayback(session.controllerForCurrentRequest) },
                ) { error("No empty-player resumption expected") }
                session = MediaSession.Builder(context, wrapper).setCallback(object : MediaSession.Callback {
                    override fun onSetMediaItems(
                        session: MediaSession, controller: MediaSession.ControllerInfo,
                        mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long,
                    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                        wrapper.cancelPendingResumption()
                        if (mediaItems.single().mediaId != "request") {
                            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(mediaItems, 0, 0L))
                        }
                        return selection.future(owner = controller, isAuthorized = { authorized },
                            prepare = { requested.countDown(); prepared.await() },
                            onAccepted = {
                                accepted.incrementAndGet()
                                player.playbackParameters = PlaybackParameters(2f)
                            },
                        )
                    }
                    @Suppress("DEPRECATION")
                    override fun onPlayerCommandRequest(
                        session: MediaSession, controller: MediaSession.ControllerInfo, command: Int,
                    ): Int {
                        if (command == Player.COMMAND_PREPARE || command == Player.COMMAND_PLAY_PAUSE) queued.countDown()
                        return androidx.media3.session.SessionResult.RESULT_SUCCESS
                    }
                    override fun onPlayerInteractionFinished(
                        session: MediaSession, controller: MediaSession.ControllerInfo, commands: Player.Commands,
                    ) {
                        wrapper.onPlayerInteractionFinished(commands)
                        selection.onInteractionFinished(controller)
                        if (commands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS) || commands.contains(Player.COMMAND_SET_MEDIA_ITEM)) {
                            finished.countDown()
                        }
                    }
                }).build()
                carFuture = MediaController.Builder(context, session.token).buildAsync()
                phoneFuture = MediaController.Builder(context, session.token).buildAsync()
            }
            car = carFuture.get(10, TimeUnit.SECONDS)
            phone = phoneFuture.get(10, TimeUnit.SECONDS)
            flush()
        }
        fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://example.test/$id").build()
        fun result() = MediaSession.MediaItemsWithStartPosition(listOf(item("prepared")), 0, 0L)
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun flush() = instrumentation.waitForIdleSync()
        fun requestSelection(queuePlay: Boolean = false) {
            main {
                car.setMediaItem(item("request"))
                if (queuePlay) { car.prepare(); car.play() }
            }
            assertTrue(requested.await(10, TimeUnit.SECONDS))
            if (queuePlay) assertTrue(queued.await(10, TimeUnit.SECONDS))
        }
        override fun close() = main {
            car.release(); phone.release(); wrapper.close(); session.release(); player.release(); scope.cancel()
        }
    }
}
