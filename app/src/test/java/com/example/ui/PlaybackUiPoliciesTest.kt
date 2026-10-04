package com.example.ui

import com.example.data.model.PersonDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackUiPoliciesTest {
    @Test
    fun sameActiveBookDoesNotUseResumePath() {
        assertFalse(
            shouldResumeBookPlayback(
                activeBookId = "uknig:book-1",
                targetBookId = "uknig:book-1",
            )
        )
    }

    @Test
    fun differentOrMissingActiveBookUsesResumePath() {
        assertTrue(
            shouldResumeBookPlayback(
                activeBookId = "uknig:book-2",
                targetBookId = "uknig:book-1",
            )
        )
        assertTrue(
            shouldResumeBookPlayback(
                activeBookId = null,
                targetBookId = "uknig:book-1",
            )
        )
    }

    @Test
    fun progressRenderingStillRequiresMatchingPlaybackSource() {
        assertTrue(
            isSamePlaybackSource(
                activeBookId = "book-1",
                activeBookSourceId = "source-1",
                activeSourceCode = "uknig",
                targetBookId = "book-1",
                targetBookSourceId = "source-1",
                targetSourceCode = "uknig",
            )
        )
        assertFalse(
            isSamePlaybackSource(
                activeBookId = "book-1",
                activeBookSourceId = "source-1",
                activeSourceCode = "uknig",
                targetBookId = "book-1",
                targetBookSourceId = "source-2",
                targetSourceCode = "audioboo",
            )
        )
    }

    @Test
    fun openingFullPlayerRestoresDismissedMiniPlayerForSameBook() {
        assertTrue(shouldRestoreMiniPlayerAfterFullPlayerOpen("book-1", "book-1"))
        assertFalse(shouldRestoreMiniPlayerAfterFullPlayerOpen("book-1", "book-2"))
        assertFalse(shouldRestoreMiniPlayerAfterFullPlayerOpen(null, "book-1"))
    }

    @Test
    fun newRuTrackerPlaybackDefersFullPlayerUntilPreparationIsReady() {
        assertTrue(
            shouldDeferPlayerNavigationForPreparation(
                requiresResume = true,
                usesRuTrackerTorrServe = true,
            )
        )
        assertFalse(
            shouldDeferPlayerNavigationForPreparation(
                requiresResume = false,
                usesRuTrackerTorrServe = true,
            )
        )
        assertFalse(
            shouldDeferPlayerNavigationForPreparation(
                requiresResume = true,
                usesRuTrackerTorrServe = false,
            )
        )
    }

    @Test
    fun torrServerReadyRequiresPlaybackStartForPendingRuTrackerBook() {
        assertFalse(
            shouldMarkTorrServePreparationReady(
                pendingBookId = "book-b",
                startedBookId = "book-a",
                startedSourceCode = "rutracker",
            )
        )
        assertFalse(
            shouldMarkTorrServePreparationReady(
                pendingBookId = "book-b",
                startedBookId = "book-b",
                startedSourceCode = "audiopolka",
            )
        )
        assertTrue(
            shouldMarkTorrServePreparationReady(
                pendingBookId = "book-b",
                startedBookId = "book-b",
                startedSourceCode = "RuTracker",
            )
        )
    }

    @Test
    fun preparationOpensFullPlayerOnlyAtReadyStageWithPreparedBook() {
        assertFalse(
            shouldOpenPlayerForReadyPreparation(
                preparationActive = true,
                preparationReady = false,
                hasPreparedBook = true,
            )
        )
        assertFalse(
            shouldOpenPlayerForReadyPreparation(
                preparationActive = true,
                preparationReady = true,
                hasPreparedBook = false,
            )
        )
        assertTrue(
            shouldOpenPlayerForReadyPreparation(
                preparationActive = true,
                preparationReady = true,
                hasPreparedBook = true,
            )
        )
    }

    @Test
    fun decorativeWideImagesAreNotAcceptedAsBookCovers() {
        assertTrue(isLikelyBookCoverSize(800, 1200))
        assertTrue(isLikelyBookCoverSize(1000, 1000))
        assertFalse(isLikelyBookCoverSize(1600, 300))
        assertFalse(isLikelyBookCoverSize(200, 1000))
    }

    @Test
    fun longPeopleListsUseTwoNamePreviewAndSheet() {
        val people = (1..20).map { index -> PersonDto(id = "p$index", name = "Человек $index") }
        assertTrue(shouldUsePeopleSheet(people.size))
        assertEquals(listOf("Человек 1", "Человек 2"), peoplePreviewNames(people).map { it.name })
        assertFalse(shouldUsePeopleSheet(2))
    }
}
