package com.example.ui.theme

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class ReaderHighlightColorTest {
    @Test fun appThemeHighlightIsVisibleAndTextRemainsReadableInBothThemes() {
        for (theme in listOf(AudioBookRedDarkColors, AudioBookRedLightColors)) {
            // Regression: secondaryContainer used to make the highlight invisible.
            assertEquals(theme.background, theme.secondaryContainer)
            val highlight = readerAppHighlightColor(theme.background)
            assertNotEquals(theme.background, highlight)
            val text = theme.onBackground.luminance()
            val fill = highlight.luminance()
            val contrast = (maxOf(text, fill) + 0.05f) / (minOf(text, fill) + 0.05f)
            assertTrue("Text contrast on highlight: $contrast", contrast >= 4.5f)
        }
    }
}
