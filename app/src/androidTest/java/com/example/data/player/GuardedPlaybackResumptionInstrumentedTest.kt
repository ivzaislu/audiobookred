package com.example.data.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(UnstableApi::class)
class GuardedPlaybackResumptionInstrumentedTest {
    @Test
    fun sdkAsyncResumptionFailureReallyReplaysLastFileWithoutOurGuard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        val requested = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val replayed = CountDownLatch(1)
        lateinit var player: ExoPlayer
        lateinit var session: MediaSession
        lateinit var future: ListenableFuture<MediaController>
        var controller: MediaController? = null
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            player = ExoPlayer.Builder(context).build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && player.mediaItemCount > 0) ended.countDown()
                }
            })
            session = MediaSession.Builder(context, player).setCallback(object : MediaSession.Callback {
                override fun onPlaybackResumption(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    isForPlayback: Boolean,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    requested.countDown()
                    return result
                }
            }).build()
            future = MediaController.Builder(context, session.token).buildAsync()
        }
        try {
            val connected = future.get(10, TimeUnit.SECONDS)
            controller = connected
            instrumentation.runOnMainSync { connected.play() }
            assertTrue(requested.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                player.setMediaSources(List(2) {
                    SilenceMediaSource.Factory().setDurationUs(100_000L).createMediaSource()
                })
                player.prepare()
                player.play()
            }
            assertTrue(ended.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) replayed.countDown()
                    }
                })
                // This is the real Media3 1.11 failure fallback, not a fake Player.
                result.setException(IOException("Simulated late resumption failure"))
            }
            assertTrue("SDK fallback behavior changed", replayed.await(10, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync {
                controller?.release()
                session.release()
                player.release()
            }
        }
    }

    @Test
    fun delayedSuccessCannotReplaceAnotherBookAfterItEnds() = Fixture().use { f ->
        val request = f.requestPlay()
        f.finishAnotherBook()
        val starts = f.starts.get()
        f.prepared.complete(f.resumption())
        f.join(request)
        f.assertAnotherBookStillEnded(starts)
    }

    @Test
    fun delayedFailureCannotInvokeSdkFallbackPlayOnEndedBook() = Fixture().use { f ->
        val request = f.requestPlay()
        f.finishAnotherBook()
        val starts = f.starts.get()
        f.prepared.completeExceptionally(IOException("Simulated stream failure"))
        f.join(request)
        f.assertAnotherBookStillEnded(starts)
    }

    @Test
    fun pauseWhilePlayerIsEmptyCancelsResumptionWithoutNeedingPlayerEvents() = Fixture().use { f ->
        val request = f.requestPlay()
        f.main { f.controller.pause() }
        assertTrue("Pause was not delivered", f.secondPlayInteraction.await(10, TimeUnit.SECONDS))
        f.prepared.complete(f.resumption())
        f.join(request)
        f.main {
            assertEquals(0, f.player.mediaItemCount)
            assertFalse(f.player.isPlaying)
            assertFalse(f.player.playWhenReady)
            assertEquals(0, f.starts.get())
        }
    }

    @Test
    fun validResumptionPlaysAndLaterExplicitPlayStillWorks() = Fixture().use { f ->
        val request = f.requestPlay()
        f.prepared.complete(f.resumption())
        f.join(request)
        assertTrue("Resumed audio did not end", f.ended.await(10, TimeUnit.SECONDS))
        f.main {
            assertEquals("resumed", f.player.currentMediaItem?.mediaId)
            assertEquals(1, f.starts.get())
            f.controller.play()
        }
        assertTrue("Explicit replay was blocked", f.twoStarts.await(10, TimeUnit.SECONDS))
    }

    private class Fixture : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var player: ExoPlayer
        lateinit var guarded: GuardedPlaybackResumptionPlayer
        lateinit var session: MediaSession
        lateinit var controller: MediaController
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val prepared = CompletableDeferred<SessionPlaybackResumption>()
        private val requested = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val twoStarts = CountDownLatch(2)
        val secondPlayInteraction = CountDownLatch(1)
        val starts = AtomicInteger()
        private val playInteractions = AtomicInteger()
        private lateinit var wav: File

        init {
            lateinit var future: ListenableFuture<MediaController>
            main {
                val context = instrumentation.targetContext
                wav = wavFile(context)
                player = ExoPlayer.Builder(context).build()
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) { starts.incrementAndGet(); twoStarts.countDown() }
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED && player.mediaItemCount > 0) ended.countDown()
                    }
                })
                guarded = GuardedPlaybackResumptionPlayer(player, scope) {
                    requested.countDown()
                    prepared.await()
                }
                session = MediaSession.Builder(context, guarded)
                    .setCallback(object : MediaSession.Callback {
                        override fun onPlaybackResumption(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                            isForPlayback: Boolean,
                        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
                            Futures.immediateFuture(emptyPlaybackResumptionItems())

                        override fun onPlayerInteractionFinished(
                            session: MediaSession,
                            info: MediaSession.ControllerInfo,
                            commands: Player.Commands,
                        ) {
                            guarded.onPlayerInteractionFinished(commands)
                            if (commands.contains(Player.COMMAND_PLAY_PAUSE) &&
                                playInteractions.incrementAndGet() >= 2
                            ) secondPlayInteraction.countDown()
                        }
                    }).build()
                future = MediaController.Builder(context, session.token).buildAsync()
            }
            controller = future.get(10, TimeUnit.SECONDS)
        }

        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

        fun requestPlay(): Job {
            main { controller.play() }
            assertTrue("No system resumption request", requested.await(10, TimeUnit.SECONDS))
            lateinit var job: Job
            main { job = requireNotNull(guarded.pendingResumptionJob) }
            return job
        }

        fun resumption() = SessionPlaybackResumption(
            MediaSession.MediaItemsWithStartPosition(
                listOf(MediaItem.Builder().setMediaId("resumed").setUri(Uri.fromFile(wav)).build()), 0, 0L,
            ),
        )

        fun finishAnotherBook() {
            main {
                player.setMediaSources(List(2) {
                    SilenceMediaSource.Factory().setDurationUs(100_000L).createMediaSource()
                })
                player.prepare()
                player.play()
            }
            assertTrue("Another book did not end", ended.await(10, TimeUnit.SECONDS))
        }

        fun join(job: Job) {
            runBlocking { withTimeout(10_000L) { job.join() } }
            instrumentation.waitForIdleSync()
        }

        fun assertAnotherBookStillEnded(expectedStarts: Int) = main {
            assertEquals(2, player.mediaItemCount)
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals(Player.STATE_ENDED, player.playbackState)
            assertFalse(player.isPlaying)
            assertEquals(expectedStarts, starts.get())
        }

        override fun close() {
            main {
                controller.release()
                guarded.close()
                session.release()
                player.release()
                scope.cancel()
            }
            wav.delete()
        }

        private fun wavFile(context: Context): File {
            val bytes = 3_200 // 200 ms, PCM16 mono, 8 kHz.
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes).array()
            return File.createTempFile("resumption-", ".wav", context.cacheDir).apply {
                writeBytes(header + ByteArray(bytes))
            }
        }
    }
}
