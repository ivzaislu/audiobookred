package com.example.data.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackResumeValueSanitizerTest {
    @Test
    fun finiteProgressIsPreserved() {
        assertEquals(42.5, sanitizePlaybackProgressPercent(42.5), 0.0001)
    }

    @Test
    fun progressIsClampedToSupportedRange() {
        assertEquals(0.0, sanitizePlaybackProgressPercent(-1.0), 0.0001)
        assertEquals(100.0, sanitizePlaybackProgressPercent(101.0), 0.0001)
    }

    @Test
    fun nonFiniteProgressFallsBackToZero() {
        assertEquals(0.0, sanitizePlaybackProgressPercent(Double.NaN), 0.0001)
        assertEquals(0.0, sanitizePlaybackProgressPercent(Double.POSITIVE_INFINITY), 0.0001)
        assertEquals(0.0, sanitizePlaybackProgressPercent(Double.NEGATIVE_INFINITY), 0.0001)
    }
}
