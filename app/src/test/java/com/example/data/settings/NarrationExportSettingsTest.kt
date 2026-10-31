package com.example.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class NarrationExportSettingsTest {
    @Test fun supportedBitratesSurviveAndInvalidValuesFallBackToDefault() {
        for (value in listOf(32, 48, 64, 96, 128)) assertEquals(value, sanitizeNarrationBitrate(value))
        for (value in listOf(Int.MIN_VALUE, 0, 31, 65, Int.MAX_VALUE)) {
            assertEquals(64, sanitizeNarrationBitrate(value))
        }
    }
}
