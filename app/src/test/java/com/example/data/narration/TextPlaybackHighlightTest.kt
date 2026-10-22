package com.example.data.narration

import com.example.data.textbook.TextFragmentEntity
import org.junit.Assert.*
import org.junit.Test

class TextPlaybackHighlightTest {
    private val first = TextFragmentEntity("book:c0:f0", "book:c0", 0, 0, 10, "a".repeat(10))
    private val second = TextFragmentEntity("book:c0:f1", "book:c0", 1, 10, 30, "b".repeat(20))
    private val nextChapter = TextFragmentEntity("book:c1:f0", "book:c1", 0, 30, 40, "c".repeat(10))
    private val fragments = listOf(first, second)
    private val map = TextAudioSyncMap("manifest", "book:c0", 24_000, listOf(
        TextAudioSyncEntry(first.id, 0, 48_000), TextAudioSyncEntry(second.id, 48_000, 144_000),
    ))

    @Test fun liveUsesMediaFragmentIdentityRegardlessOfClockAndSpeed() {
        assertEquals(TextPlaybackHighlight("book", "book:c0", second.id, false),
            resolveTextPlaybackHighlight("book", "book:c0", fragments, second.id, 0, 0, null))
    }
    @Test fun savedWavChangesHighlightAtActualPcmBoundary() {
        assertEquals(first.id, resolveTextPlaybackHighlight("book", "book:c0", fragments, null, 999, 3000, map)?.fragmentId)
        val atBoundary = resolveTextPlaybackHighlight("book", "book:c0", fragments, null, 1000, 3000, map)!!
        assertEquals(second.id, atBoundary.fragmentId)
        assertFalse(atBoundary.approximate)
    }
    @Test fun seekBackwardAndPauseUseMediaPositionInsteadOfWallClock() {
        val later = resolveTextPlaybackHighlight("book", "book:c0", fragments, null, 2500, 3000, map)
        assertEquals(later, resolveTextPlaybackHighlight("book", "book:c0", fragments, null, 2500, 3000, map))
        assertEquals(first.id, resolveTextPlaybackHighlight("book", "book:c0", fragments, null, 100, 3000, map)?.fragmentId)
    }
    @Test fun chapterTransitionUsesNewChapterAndResetsLocalClock() {
        assertEquals(nextChapter.id, resolveTextPlaybackHighlight("book", "book:c1", listOf(nextChapter), nextChapter.id, 0, 0, null)?.fragmentId)
        assertNull(resolveTextPlaybackHighlight("book", "book:c1", listOf(nextChapter), first.id, 0, 0, null))
    }
    @Test fun legacyWavIsExplicitlyApproximateAndUnknownDurationUsesChapterStart() {
        val legacy = resolveTextPlaybackHighlight("book", "book:c0", fragments, null, 2000, 3000, null)!!
        assertTrue(legacy.approximate)
        assertEquals(second.id, legacy.fragmentId)
        assertEquals(first.id, resolveTextPlaybackHighlight("book", "book:c0", fragments, null, 5000, 0, null)?.fragmentId)
        assertNull(resolveTextPlaybackHighlight("book", "book:c0", emptyList(), null, 0, 0, null))
    }
}
