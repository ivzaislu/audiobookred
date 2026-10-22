package com.example.data.narration

import com.example.data.textbook.TextFragmentEntity
import org.junit.Assert.*
import org.junit.Test

class TextAudioReadingPositionTest {
    private val fragments = listOf(
        TextFragmentEntity("txt_x:c0:f0", "txt_x:c0", 0, 0, 100, "а".repeat(100)),
        TextFragmentEntity("txt_x:c0:f1", "txt_x:c0", 1, 100, 300, "б".repeat(200)),
        TextFragmentEntity("txt_x:c0:f2", "txt_x:c0", 2, 300, 400, "в".repeat(100)),
    )

    @Test fun savedBookTimeFindsApproximateFragmentAndRelativePosition() {
        val result = approximateAudioFragment(fragments, 45_000L, 120_000L)
        assertNotNull(result)
        assertEquals("txt_x:c0:f1", result!!.first)
        assertEquals(0.25, result.second, 0.0001)
    }

    @Test fun startEndAndOutOfRangePlaybackClampToValidText() {
        assertEquals(fragments.first().id to 0.0, approximateAudioFragment(fragments, -5000L, 100_000L))
        assertEquals(fragments.first().id to 0.0, approximateAudioFragment(fragments, 3000L, 0L))
        val atEnd = approximateAudioFragment(fragments, 500_000L, 100_000L)
        assertEquals(fragments.last().id, atEnd?.first)
        assertTrue((atEnd?.second ?: 0.0) > 0.99)
    }

    @Test fun noTextMeansNoJumpInsteadOfMisdirectedNavigation() {
        assertNull(approximateAudioFragment(emptyList(), 1000L, 2000L))
    }
    @Test fun legacyWavSupportsTextToAudioWithoutResynthesis() {
        assertEquals(45_000L, approximateTextAudioTime(fragments, fragments[1].id, 0.25, 120_000))
        val time = approximateTextAudioTime(fragments, fragments[2].id, 0.5, 120_000)!!
        val text = approximateAudioFragment(fragments, time, 120_000)!!
        assertEquals(fragments[2].id, text.first)
        assertEquals(0.5, text.second, 0.0001)
    }

    @Test fun unknownDurationAndMissingTextDoNotInventAudioTime() {
        assertEquals(0L, approximateTextAudioTime(fragments, fragments[1].id, 0.5, 0))
        assertNull(approximateTextAudioTime(fragments, "deleted", 0.5, 120_000))
        assertNull(approximateTextAudioTime(emptyList(), "deleted", 0.5, 120_000))
    }
}
