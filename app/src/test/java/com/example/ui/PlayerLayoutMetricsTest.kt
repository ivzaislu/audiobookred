package com.example.ui

import androidx.compose.ui.unit.dp
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLayoutMetricsTest {
    @Test
    fun portraitTinyUsesCompactTouchGeometry() {
        val metrics = playerPortraitMetrics(
            maxHeight = 649.dp,
            largeText = false,
            extraLargeText = false,
        )

        assertTrue(metrics.tiny)
        assertEquals(AbredSpacing.Sm, metrics.horizontalPadding)
        assertEquals(112.dp, metrics.coverWidth)
        assertEquals(2.dp, metrics.coverElevation)
        assertEquals(64.dp, metrics.playSize)
        assertEquals(48.dp, metrics.seekSize)
    }

    @Test
    fun portraitTinyBoundaryEndsAt650Dp() {
        val metrics = playerPortraitMetrics(
            maxHeight = 650.dp,
            largeText = false,
            extraLargeText = false,
        )

        assertFalse(metrics.tiny)
        assertEquals(AbredSpacing.Lg, metrics.horizontalPadding)
        assertEquals(144.dp, metrics.coverWidth)
        assertEquals(6.dp, metrics.coverElevation)
        assertEquals(72.dp, metrics.playSize)
        assertEquals(52.dp, metrics.seekSize)
    }

    @Test
    fun portraitLargeTextOverridesNormalSizing() {
        val metrics = playerPortraitMetrics(
            maxHeight = 900.dp,
            largeText = true,
            extraLargeText = false,
        )

        assertEquals(128.dp, metrics.coverWidth)
        assertEquals(56.dp, metrics.seekSize)
        assertEquals(AbredSpacing.Xxs, metrics.sectionGap)
    }

    @Test
    fun portraitExtraLargeTextUsesLargestSeekTarget() {
        val metrics = playerPortraitMetrics(
            maxHeight = 900.dp,
            largeText = true,
            extraLargeText = true,
        )

        assertEquals(112.dp, metrics.coverWidth)
        assertEquals(60.dp, metrics.seekSize)
    }

    @Test
    fun landscapeCoverBreakpointIsBelow400Dp() {
        val short = playerLandscapeMetrics(
            maxHeight = 399.dp,
            largeText = false,
            extraLargeText = false,
            hasTransientOverlay = false,
        )
        val boundary = playerLandscapeMetrics(
            maxHeight = 400.dp,
            largeText = false,
            extraLargeText = false,
            hasTransientOverlay = false,
        )

        assertEquals(112.dp, short.coverWidth)
        assertEquals(128.dp, boundary.coverWidth)
        assertEquals(AbredSizes.MinimumTouchTarget, boundary.seekSize)
    }

    @Test
    fun landscapeOverlayReserveTracksTextScaleAndVisibility() {
        val hidden = playerLandscapeMetrics(
            maxHeight = 500.dp,
            largeText = true,
            extraLargeText = true,
            hasTransientOverlay = false,
        )
        val large = playerLandscapeMetrics(
            maxHeight = 500.dp,
            largeText = true,
            extraLargeText = false,
            hasTransientOverlay = true,
        )
        val extraLarge = playerLandscapeMetrics(
            maxHeight = 500.dp,
            largeText = true,
            extraLargeText = true,
            hasTransientOverlay = true,
        )

        assertEquals(0.dp, hidden.overlayReserve)
        assertEquals(88.dp, large.overlayReserve)
        assertEquals(120.dp, extraLarge.overlayReserve)
        assertEquals(0.32f, extraLarge.infoWeight, 0.0001f)
        assertEquals(0.68f, extraLarge.controlsWeight, 0.0001f)
    }
}
