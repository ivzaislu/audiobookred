package com.example.data.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRefreshRecoveryGateTest {
    @Test
    fun onlyOneAutomaticRecoveryRunsForSameErroredBookAndSource() {
        val gate = PlaybackRefreshRecoveryGate()

        assertTrue(gate.shouldRecover("audiopolka:42", "audiopolka", false, false, "Source error"))
        assertFalse(gate.shouldRecover("audiopolka:42", "audiopolka", false, false, "Source error"))
    }

    @Test
    fun successfulPlaybackRearmsRecoveryForLaterExpiredUrl() {
        val gate = PlaybackRefreshRecoveryGate()

        assertTrue(gate.shouldRecover("uknig:42", "uknig", false, false, "Source error"))
        assertFalse(gate.shouldRecover("uknig:42", "uknig", true, false, null))
        assertTrue(gate.shouldRecover("uknig:42", "uknig", false, false, "Source error"))
    }

    @Test
    fun switchingSourceRearmsSameBookIdentity() {
        val gate = PlaybackRefreshRecoveryGate()

        assertTrue(gate.shouldRecover("book:42", "audioboo", false, false, "Source error"))
        assertFalse(gate.shouldRecover("book:42", "audioboo", false, false, "Source error"))
        assertTrue(gate.shouldRecover("book:42", "future-source", false, false, "Source error"))
    }

    @Test
    fun localFilesAndMissingErrorsNeverTriggerNetworkRefresh() {
        val gate = PlaybackRefreshRecoveryGate()

        assertFalse(gate.shouldRecover("bazaknig:42", "bazaknig", false, true, "Local file error"))
        assertFalse(gate.shouldRecover("knigavuhe:42", "knigavuhe", false, false, null))
    }
}
