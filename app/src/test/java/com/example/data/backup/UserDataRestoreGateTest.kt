package com.example.data.backup

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDataRestoreGateTest {
    @After
    fun resetGate() {
        UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
    }

    @Test
    fun concurrentUserRestoreCannotShareActiveEpoch() {
        assertTrue(UserDataRestoreGate.tryBeginImportRestore())
        val firstEpoch = UserDataRestoreGate.currentPlaybackWriteEpoch()

        assertFalse(UserDataRestoreGate.tryBeginImportRestore())
        assertEqualsEpoch(firstEpoch)

        UserDataRestoreGate.finishRestoreAwaitingFreshPrepare()
        val completedEpoch = UserDataRestoreGate.currentPlaybackWriteEpoch()
        assertNotEquals(firstEpoch, completedEpoch)

        // A completed restore may still be waiting for fresh playback prepare;
        // a new explicit import is nevertheless a distinct restore generation.
        assertTrue(UserDataRestoreGate.tryBeginImportRestore())
        assertNotEquals(completedEpoch, UserDataRestoreGate.currentPlaybackWriteEpoch())
    }

    @Test
    fun successfulRestoreCannotBeUnlockedUntilRestoreIoFinishes() {
        assertTrue(UserDataRestoreGate.playbackWritesAllowed())

        UserDataRestoreGate.blockPlaybackWrites()
        val activeEpoch = UserDataRestoreGate.currentPlaybackWriteEpoch()
        assertFalse(UserDataRestoreGate.playbackWritesAllowed())
        assertFalse(UserDataRestoreGate.playbackWriteEpochCurrent(activeEpoch))
        assertFalse(UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare(activeEpoch))

        UserDataRestoreGate.finishRestoreAwaitingFreshPrepare()
        val restoredEpoch = UserDataRestoreGate.currentPlaybackWriteEpoch()
        assertNotEquals(activeEpoch, restoredEpoch)
        assertFalse(UserDataRestoreGate.playbackWritesAllowed())
        assertTrue(UserDataRestoreGate.playbackWriteEpochCurrent(restoredEpoch))
        assertTrue(UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare(restoredEpoch))
        assertTrue(UserDataRestoreGate.playbackWritesAllowed())
    }

    @Test
    fun checkpointEpochNeverReactivatesAfterRestore() {
        UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
        val oldEpoch = UserDataRestoreGate.capturePlaybackWriteEpoch()
        assertNotNull(oldEpoch)

        UserDataRestoreGate.blockPlaybackWrites()
        val activeEpoch = UserDataRestoreGate.currentPlaybackWriteEpoch()
        assertNotEquals(oldEpoch, activeEpoch)
        assertFalse(UserDataRestoreGate.playbackWriteEpochAllowed(oldEpoch))
        assertFalse(UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare(activeEpoch))

        UserDataRestoreGate.finishRestoreAwaitingFreshPrepare()
        val restoredEpoch = UserDataRestoreGate.currentPlaybackWriteEpoch()
        assertNotEquals(activeEpoch, restoredEpoch)
        assertTrue(UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare(restoredEpoch))
        assertFalse(UserDataRestoreGate.playbackWriteEpochAllowed(oldEpoch))
        assertFalse(UserDataRestoreGate.playbackWriteEpochAllowed(activeEpoch))
        assertTrue(UserDataRestoreGate.playbackWriteEpochAllowed(restoredEpoch))
    }

    @Test
    fun preparedTargetFromOldEpochCannotUnlockNewRestore() {
        UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
        val preparedEpoch = UserDataRestoreGate.registerPreparedPlaybackTarget(
            bookId = "book-1",
            sourceCode = "source-a",
        )

        UserDataRestoreGate.blockPlaybackWrites()

        assertFalse(UserDataRestoreGate.playbackWriteEpochCurrent(preparedEpoch))
        assertFalse(UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare(preparedEpoch))
        assertFalse(UserDataRestoreGate.playbackWritesAllowed())

        UserDataRestoreGate.finishRestoreAwaitingFreshPrepare()
        val restoredEpoch = UserDataRestoreGate.currentPlaybackWriteEpoch()
        assertTrue(UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare(restoredEpoch))
    }

    @Test
    fun targetPreparedDuringActiveRestoreIsInvalidatedOnCompletion() {
        UserDataRestoreGate.blockPlaybackWrites()
        val transientEpoch = UserDataRestoreGate.registerPreparedPlaybackTarget(
            bookId = "book-transient",
            sourceCode = "source-b",
        )

        assertFalse(UserDataRestoreGate.playbackWriteEpochCurrent(transientEpoch))
        assertFalse(UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare(transientEpoch))
        assertNull(
            UserDataRestoreGate.preparedPlaybackTargetEpoch(
                bookId = "book-transient",
                sourceCode = "source-b",
            )
        )

        UserDataRestoreGate.finishRestoreAwaitingFreshPrepare()

        assertFalse(UserDataRestoreGate.playbackWritesAllowed())
        assertFalse(UserDataRestoreGate.playbackWriteEpochCurrent(transientEpoch))
        assertFalse(UserDataRestoreGate.playbackWriteEpochAllowed(transientEpoch))
    }

    @Test
    fun rollbackInvalidatesTargetsPreparedDuringBlockedRestore() {
        UserDataRestoreGate.blockPlaybackWrites()
        val transientEpoch = UserDataRestoreGate.registerPreparedPlaybackTarget(
            bookId = "book-transient",
            sourceCode = "source-b",
        )

        UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()

        assertTrue(UserDataRestoreGate.playbackWritesAllowed())
        assertFalse(UserDataRestoreGate.playbackWriteEpochCurrent(transientEpoch))
        assertFalse(UserDataRestoreGate.playbackWriteEpochAllowed(transientEpoch))
    }

    @Test
    fun preparedTargetRegistrationRejectsStaleOrActiveExpectedEpoch() {
        UserDataRestoreGate.allowPlaybackWritesAfterFreshPrepare()
        val expectedEpoch = UserDataRestoreGate.currentPlaybackWriteEpoch()

        UserDataRestoreGate.blockPlaybackWrites()
        val activeEpoch = UserDataRestoreGate.currentPlaybackWriteEpoch()

        assertNull(
            UserDataRestoreGate.registerPreparedPlaybackTarget(
                bookId = "book-stale",
                sourceCode = "source-c",
                expectedEpoch = expectedEpoch,
            )
        )
        assertNull(
            UserDataRestoreGate.registerPreparedPlaybackTarget(
                bookId = "book-active",
                sourceCode = "source-c",
                expectedEpoch = activeEpoch,
            )
        )
        assertNull(
            UserDataRestoreGate.preparedPlaybackTargetEpoch(
                bookId = "book-stale",
                sourceCode = "source-c",
            )
        )
    }

    private fun assertEqualsEpoch(expected: Long) {
        assertTrue(UserDataRestoreGate.currentPlaybackWriteEpoch() == expected)
    }
}
