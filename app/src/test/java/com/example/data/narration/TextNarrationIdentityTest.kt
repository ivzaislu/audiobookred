package com.example.data.narration

import com.example.data.player.PlaybackResumeStore
import org.junit.Assert.*
import org.junit.Test

class TextNarrationIdentityTest {
    private val ids = listOf("txt_a:c0:f0|old", "txt_a:c0:f1|old", "txt_a:c1:f0|old", "txt_a:c1:f1|old", "txt_a:c2:f0|old")

    @Test fun ruVoiceContractsAreSeparatedByVoiceEngineAndConfiguration() {
        val xenia = textNarrationContractKey("xenia-ru", 10100)
        val aidar = textNarrationContractKey("aidar-ru", 10100)
        val updated = textNarrationContractKey("xenia-ru", 10200)
        val changedRules = textNarrationContractKey("xenia-ru", 10100, 2)
        assertTrue(xenia.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(xenia, aidar)
        assertNotEquals(xenia, updated)
        assertNotEquals(xenia, changedRules)
        assertEquals(xenia, textNarrationContractKey("xenia-ru", 10100))
    }

    @Test fun contractRejectsAbsentRuVoiceWithoutLegacyFallback() {
        assertThrows(IllegalArgumentException::class.java) {
            textNarrationContractKey("xenia-ru", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            textNarrationContractKey("xenia-ru", 10100, -1)
        }
    }

    @Test fun chapterSelectionCollapsesFragmentsAndIgnoresSpeechContract() {
        assertEquals(listOf(0, 2, 4), textChapterStarts(ids))
        assertEquals(textChapterId(ids[0]), textChapterId(ids[1].replace("old", "new")))
        assertNotEquals(textChapterId(ids[1]), textChapterId(ids[2]))
    }

    @Test fun navigationMovesBetweenRealChaptersInsteadOfAudioFragments() {
        assertEquals(2, textChapterTarget(ids, 0, true))
        assertEquals(2, textChapterTarget(ids, 1, true))
        assertEquals(0, textChapterTarget(ids, 3, false))
        assertEquals(0, textChapterTarget(ids, 1, false))
        assertNull(textChapterTarget(ids, 4, true))
        assertNull(textChapterTarget(ids, -1, false))
        assertNull(textChapterTarget(emptyList(), 0, true))
    }
    @Test fun switchingNarrationModeKeepsTextFragmentAndApproximateTime() {
        val oldIds = ids
        val smoothIds = ids.map { it.replace("|old", "|smooth") }
        val target = textNarrationStyleSwitchPosition(smoothIds, oldIds[3], 0, 14_500L)
        assertEquals(3, target.fragmentIndex)
        assertEquals(14_500L, target.positionMs)
    }

    @Test fun missingFragmentResetsTimeWithoutJumpingOutsideBook() {
        val switched = textNarrationStyleSwitchPosition(ids, "deleted:f0|old", 99, 42_000)
        assertEquals(ids.lastIndex, switched.fragmentIndex)
        assertEquals(0L, switched.positionMs)
        assertEquals(0L, textNarrationStyleSwitchPosition(ids, ids[0], 4, -1L).positionMs)
    }

    @Test fun resumeUsesStableFragmentAndResetsTimeWhenSpeechChanges() {
        val saved = PlaybackResumeStore.Snapshot("txt_a", TEXT_NARRATION_SOURCE, ids[3], 1, 12000, 1.25f, 30.0, 50, true)
        val exact = normalizeTextNarrationResume(ids, saved)!!
        assertEquals(3, exact.chapterIndex)
        assertEquals(12000L, exact.positionMs)
        val changed = normalizeTextNarrationResume(ids.map { it.replace("old", "new") }, saved)!!
        assertEquals(3, changed.chapterIndex)
        assertEquals(0L, changed.positionMs)
        assertEquals(1.25f, changed.speed)
        val completed = normalizeTextNarrationResume(ids, saved.copy(progressPercent = 100.0))!!
        assertEquals(0, completed.chapterIndex)
        assertEquals(0L, completed.positionMs)
        assertNull(normalizeTextNarrationResume(emptyList(), saved))
    }

}
