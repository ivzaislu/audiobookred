package com.example.data.player

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class AudiobookSessionCommandsInstrumentedTest {
    @Test
    fun actualControllerRepeatRequestCannotLoopTwoChapterBook() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var player: ExoPlayer
        lateinit var session: MediaSession
        lateinit var controllerFuture: ListenableFuture<MediaController>
        var controller: MediaController? = null
        val finished = CountDownLatch(1)
        var failure: PlaybackException? = null
        val autoTransitions = mutableListOf<Pair<Int, Int>>()

        instrumentation.runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            player = ExoPlayer.Builder(context).build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) finished.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    failure = error
                    finished.countDown()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        autoTransitions += oldPosition.mediaItemIndex to newPosition.mediaItemIndex
                    }
                }
            })
            player.setMediaSources(List(2) {
                SilenceMediaSource.Factory().setDurationUs(200_000L).createMediaSource()
            })
            session = MediaSession.Builder(context, player)
                .setCallback(object : MediaSession.Callback {
                    override fun onConnectAsync(
                        session: MediaSession,
                        info: MediaSession.ControllerInfo,
                    ): ListenableFuture<MediaSession.ConnectionResult> =
                        Futures.immediateFuture(audiobookConnectionResult(session, info))
                })
                .build()
            controllerFuture = MediaController.Builder(context, session.token)
                .buildAsync()
        }
        try {
            val connected = controllerFuture.get(10, TimeUnit.SECONDS)
            controller = connected
            instrumentation.runOnMainSync {
                assertFalse(connected.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE))
                assertFalse(connected.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE))
                connected.repeatMode = Player.REPEAT_MODE_ALL
                connected.shuffleModeEnabled = true
                connected.prepare()
                connected.play()
            }
            assertTrue("Playback never reached ENDED", finished.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                org.junit.Assert.assertNull("Playback failed", failure)
                org.junit.Assert.assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
                assertFalse(player.shuffleModeEnabled)
                org.junit.Assert.assertEquals(Player.STATE_ENDED, player.playbackState)
                org.junit.Assert.assertEquals(1, player.currentMediaItemIndex)
                assertFalse(player.isPlaying)
                assertFalse(autoTransitions.contains(1 to 0))
            }
        } finally {
            instrumentation.runOnMainSync {
                controller?.release()
                session.release()
                player.release()
            }
        }
    }

    @Test
    fun controllerCannotEnableRepeatOrShuffleButKeepsTransportControls() {
        val commands = audiobookPlayerCommands(Player.Commands.Builder().addAllCommands().build())
        assertFalse(commands.contains(Player.COMMAND_SET_REPEAT_MODE))
        assertFalse(commands.contains(Player.COMMAND_SET_SHUFFLE_MODE))
        assertTrue(commands.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(commands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertTrue(commands.contains(Player.COMMAND_SET_SPEED_AND_PITCH))
    }

    @Test
    fun untrustedControllerDoesNotGainAnyAdditionalCommands() {
        val defaults = Player.Commands.Builder().add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM).build()
        val commands = audiobookPlayerCommands(defaults)
        assertTrue(commands.contains(Player.COMMAND_GET_CURRENT_MEDIA_ITEM))
        assertFalse(commands.contains(Player.COMMAND_PLAY_PAUSE))
        assertFalse(commands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertFalse(commands.contains(Player.COMMAND_SET_REPEAT_MODE))
        assertFalse(commands.contains(Player.COMMAND_SET_SHUFFLE_MODE))
    }
}
