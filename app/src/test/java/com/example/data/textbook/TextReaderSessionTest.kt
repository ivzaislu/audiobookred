package com.example.data.textbook

import org.junit.Assert.*
import org.junit.Test

class TextReaderSessionTest {
    @Test fun previewRejectsEveryAutomaticSaveIncludingLateNormalCallbacks() {
        val session = TextReaderSession()
        val old = session.open(false)
        assertTrue(session.canSave(old))
        val preview = session.open(true)
        repeat(100) { // scroll, chapter changes, pause/stop/dispose all share this guard
            assertFalse(session.canSave(preview))
            assertFalse(session.canSave(old))
        }
    }

    @Test fun successfulExplicitCommitOpensANewNormalGeneration() {
        val session = TextReaderSession()
        val preview = session.open(true)
        assertTrue(session.beginCommit(preview))
        assertFalse(session.beginCommit(preview))
        assertFalse(session.canSave(preview))
        assertTrue(session.canCommit(preview))
        val normal = session.finishCommit(preview, true)
        assertTrue(session.canSave(normal))
        assertFalse(session.canSave(preview))
        assertFalse(session.canCommit(preview))
    }

    @Test fun failedCommitRemainsPreviewAndCanBeRetried() {
        val session = TextReaderSession()
        val preview = session.open(true)
        session.beginCommit(preview)
        session.finishCommit(preview, false)
        assertEquals(TextReaderSession.Mode.PREVIEW, session.mode)
        assertFalse(session.canSave(preview))
        assertTrue(session.beginCommit(preview))
    }

    @Test fun closeAndReopenCannotReviveOldCallbacks() {
        val session = TextReaderSession()
        val old = session.open(false)
        session.close()
        assertFalse(session.canSave(old))
        val next = session.open(false)
        assertTrue(session.canSave(next))
        assertFalse(session.canSave(old))
    }

    @Test fun lateCommitCannotReplaceAnotherBookOrRollback() {
        val session = TextReaderSession()
        val preview = session.open(true)
        session.beginCommit(preview)
        val normal = session.open(false)
        session.finishCommit(preview, true)
        assertTrue(session.canSave(normal))
        assertFalse(session.canCommit(preview))
        assertFalse(session.beginCommit(normal))
    }

    @Test fun processRecreationStartsClosedAndDoesNotRestorePreviewAsNormal() {
        val old = TextReaderSession().also { it.open(true) }
        val recreated = TextReaderSession()
        assertNotEquals(old.identity, recreated.identity)
        assertFalse(recreated.canSave(old.generation))
        assertEquals(TextReaderSession.Mode.CLOSED, recreated.mode)
    }
    @Test fun explicitCommitKeepsNormalReadingUntilExplicitListeningResumesFollowing() {
        val session = TextReaderSession()
        val preview = session.open(true)
        assertTrue(session.canFollow(preview))
        assertTrue(session.beginCommit(preview))
        assertFalse(session.canFollow(preview))
        val reading = session.finishCommit(preview, true)
        assertTrue(session.canSave(reading))
        assertFalse(session.canFollow(reading))
        session.allowFollowing(preview) // A disposed callback cannot override the manual action.
        assertFalse(session.canFollow(reading))
        session.allowFollowing(reading)
        assertTrue(session.canFollow(reading))
    }

    @Test fun returnToBookmarkAllowsReadingWithoutImmediatelyFollowingPlayingAudio() {
        val session = TextReaderSession()
        val preview = session.open(true)
        val reading = session.open(false, allowFollowing = false)
        assertTrue(session.canSave(reading))
        assertFalse(session.canFollow(reading))
        assertFalse(session.canFollow(preview))
        session.close()
        session.allowFollowing(reading)
        assertFalse(session.canFollow(reading))
        assertTrue(session.canFollow(session.open(false)))
    }

    @Test fun failedCommitRestoresFollowingOnlyAfterLeavingCommittingMode() {
        val session = TextReaderSession()
        val preview = session.open(true)
        session.beginCommit(preview)
        assertFalse(session.canFollow(preview))
        session.finishCommit(preview, false)
        assertTrue(session.canFollow(preview))
        assertFalse(session.canSave(preview))
    }

}
