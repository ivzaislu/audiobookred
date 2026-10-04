package com.example.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSettingsStoreTest {
    @Test
    fun validDefaultSpeedIsPreserved() {
        assertEquals(1.75f, sanitizeDefaultPlaybackSpeed(1.75f), 0.0001f)
    }

    @Test
    fun defaultSpeedIsClampedToSupportedRange() {
        assertEquals(0.5f, sanitizeDefaultPlaybackSpeed(0.1f), 0.0001f)
        assertEquals(3.0f, sanitizeDefaultPlaybackSpeed(9.0f), 0.0001f)
    }

    @Test
    fun nonFiniteDefaultSpeedFallsBackToNormalSpeed() {
        assertEquals(1.0f, sanitizeDefaultPlaybackSpeed(Float.NaN), 0.0001f)
        assertEquals(1.0f, sanitizeDefaultPlaybackSpeed(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(1.0f, sanitizeDefaultPlaybackSpeed(Float.NEGATIVE_INFINITY), 0.0001f)
    }

    @Test
    fun darkThemeIsDefaultForNewSettings() {
        assertEquals(DEFAULT_APP_THEME_MODE, PlayerSettings().themeMode)
        assertEquals(AppThemeMode.DARK, DEFAULT_APP_THEME_MODE)
    }
}
