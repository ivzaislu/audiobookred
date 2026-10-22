package com.example.data.textbook

import org.junit.Assert.assertEquals
import org.junit.Test

class TextListeningProgressTest {
    @Test fun chapterProgressIsRelativeToTheOriginalBook() {
        assertEquals(50.0, textChapterListeningPercent(1000, 400, 600, 50.0), 0.001)
        assertEquals(60.0, textChapterListeningPercent(1000, 400, 600, 100.0), 0.001)
        assertEquals(40.0, textChapterListeningPercent(1000, 400, 600, 0.0), 0.001)
    }

    @Test fun lastChapterAndEmptyOrInvalidInputsStayBounded() {
        assertEquals(100.0, textChapterListeningPercent(1000, 900, 1000, 100.0), 0.001)
        assertEquals(0.0, textChapterListeningPercent(0, 0, 0, 50.0), 0.001)
        assertEquals(0.0, textChapterListeningPercent(1000, 100, 200, Double.NaN), 0.001)
        assertEquals(100.0, textChapterListeningPercent(1000, -10, 2000, 150.0), 0.001)
    }
}
