package com.example.data.narration

import org.junit.Assert.*
import org.junit.Test

class TextAudioResumeSelectionTest {
    @Test fun lastPlayedChapterExportWinsOverWholeBookDefaultAndLive() {
        assertEquals("chapter-two", textAudioResumeSource(50, listOf("all" to 10L, "chapter-two" to 100L)))
    }
    @Test fun liveBookmarkWinsOverAnUnplayedOrOlderExport() {
        assertNull(textAudioResumeSource(100, listOf("all" to null, "chapter-two" to 50L)))
    }
    @Test fun readyWavWithoutLiveBookmarkWorksWithoutEngineOrReaderPosition() {
        assertEquals("all", textAudioResumeSource(null, listOf("all" to null)))
        assertEquals("chapter-two", textAudioResumeSource(null, listOf("all" to null, "chapter-two" to 25L)))
    }
    @Test fun noSavedAudioSelectsLiveStart() {
        assertNull(textAudioResumeSource(null, emptyList()))
    }
}
