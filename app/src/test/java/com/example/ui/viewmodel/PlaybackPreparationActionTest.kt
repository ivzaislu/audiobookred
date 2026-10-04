package com.example.ui.viewmodel

import com.example.data.backup.UserDataRestoreGate
import com.example.data.model.BookDetailDto
import com.example.data.player.PlaybackRecoveryGuard
import com.example.domain.playback.PreparedPlayback
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlaybackPreparationActionTest {
    @Test
    fun completedPlaybackInvalidatesSuspendedRecoveryBeforeFirstChapterCanStart() = runBlocking {
        val guard = PlaybackRecoveryGuard()
        val request = guard.capture()
        val result = CompletableDeferred<PreparedPlayback>()
        var played = false
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            executePreparedPlayback(
                prepare = { result.await() },
                isCurrent = { guard.isCurrent(request) },
                play = { played = true },
            )
        }

        // The Player reports ENDED while preparation is waiting for its result.
        guard.invalidate()
        result.complete(PreparedPlayback(BookDetailDto(id = "bazaknig:1", title = "Книга"), 0, 0L, 1f))

        assertFalse(pending.await())
        assertFalse(played)
    }

    @Test
    fun newRequestForSameBookRejectsOlderPreparationEvenWhenRestoreEpochIsUnchanged() = runBlocking {
        var generation = 1L
        val requestedGeneration = generation
        var played = false
        val started = executePreparedPlayback(
            prepare = {
                generation += 1L
                PreparedPlayback(BookDetailDto(id = "bazaknig:1", title = "Книга"), 0, 0L, 1f)
            },
            isCurrent = { requestedGeneration == generation },
            play = { played = true },
        )
        assertFalse(started)
        assertFalse(played)
    }

    @Test
    fun alreadyInvalidRecoveryDoesNotReadOrClearResumeCheckpoints() = runBlocking {
        val started = executePreparedPlayback(
            prepare = { error("Stale recovery must not call prepare") },
            isCurrent = { false },
            play = { error("Stale recovery must not play") },
        )
        assertFalse(started)
    }

    @Test
    fun unchangedRecoveryStillPlaysThePreparedChapter() = runBlocking {
        val guard = PlaybackRecoveryGuard()
        val request = guard.capture()
        val target = PreparedPlayback(BookDetailDto(id = "bazaknig:1", title = "Книга"), 8, 19_000L, 1.2f)
        var played: PreparedPlayback? = null
        val started = executePreparedPlayback(
            prepare = { target },
            isCurrent = { guard.isCurrent(request) },
            play = { played = it },
        )
        assertTrue(started)
        assertEquals(8, played?.chapterIndex)
        assertEquals(19_000L, played?.positionMs)
    }

    @Test
    fun pauseDuringMediaItemBuildInvalidatesPreviouslyAuthorizedRecovery() = runBlocking {
        val guard = PlaybackRecoveryGuard()
        val request = guard.capture()
        var pendingMediaItemRequest: Long? = null
        val started = executePreparedPlayback(
            prepare = { PreparedPlayback(BookDetailDto(id = "bazaknig:1", title = "Книга"), 8, 19_000L, 1f) },
            isCurrent = { guard.isCurrent(request) },
            play = {
                guard.invalidate()
                pendingMediaItemRequest = guard.capture()
            },
        )
        assertTrue(started)
        val loadRequest = checkNotNull(pendingMediaItemRequest)
        assertTrue(guard.isCurrent(loadRequest))
        guard.invalidate()
        assertFalse(guard.isCurrent(loadRequest))
    }

    @After
    fun resetGate() {
        UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
    }

    @Test
    fun preparedPlaybackStartsBeforeCallerMayNavigate() = runBlocking {
        val expectedEpoch = UserDataRestoreGate.currentPlaybackWriteEpoch()
        val prepared = PreparedPlayback(
            book = BookDetailDto(id = "audiopolka:1", title = "Книга"),
            chapterIndex = 2,
            positionMs = 12_345L,
            speed = 1.25f,
        )
        var played: PreparedPlayback? = null

        val started = executePreparedPlayback(
            prepare = { prepared },
            play = { played = it },
        )

        assertTrue(started)
        assertEquals(prepared.copy(playbackWriteEpoch = expectedEpoch), played)
    }

    @Test
    fun restoreEpochChangeDuringPreparationDoesNotStartPlayback() = runBlocking {
        val prepared = PreparedPlayback(
            book = BookDetailDto(id = "audiopolka:2", title = "Книга 2"),
            chapterIndex = 0,
            positionMs = 0L,
            speed = 1f,
        )
        var played = false

        val started = executePreparedPlayback(
            prepare = {
                UserDataRestoreGate.blockPlaybackWrites()
                prepared
            },
            play = { played = true },
        )

        assertFalse(started)
        assertFalse(played)
    }

    @Test
    fun restoreRollbackDuringPreparationStillRejectsCapturedEpoch() = runBlocking {
        val prepared = PreparedPlayback(
            book = BookDetailDto(id = "audiopolka:rollback", title = "Книга после rollback"),
            chapterIndex = 1,
            positionMs = 4_321L,
            speed = 1.1f,
        )
        var played = false

        val started = executePreparedPlayback(
            prepare = {
                // Simulate a restore that starts and rolls back before the async
                // preparation returns. The gate is open again, but the epoch that
                // executePreparedPlayback captured before prepare is stale twice over.
                UserDataRestoreGate.blockPlaybackWrites()
                UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
                prepared
            },
            play = { played = true },
        )

        assertFalse(started)
        assertFalse(played)
        assertTrue(UserDataRestoreGate.playbackWritesAllowed())
    }

    @Test
    fun missingPreparationDoesNotStartPlayback() = runBlocking {
        var played = false

        val started = executePreparedPlayback(
            prepare = { null },
            play = { played = true },
        )

        assertFalse(started)
        assertFalse(played)
    }

    @Test
    fun preparationFailureDoesNotStartPlayback() {
        var played = false
        try {
            runBlocking {
                executePreparedPlayback(
                    prepare = { throw IllegalStateException("offline") },
                    play = { played = true },
                )
            }
            fail("Expected preparation failure")
        } catch (_: IllegalStateException) {
            // Expected: PlaybackViewModel catches this and does not invoke onStarted.
        }
        assertFalse(played)
    }
}
