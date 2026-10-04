package com.example.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedResolverTest {
    private val chapters = setOf("chapter-0", "chapter-1")

    @Test
    fun disabledRememberBookSpeedAlwaysUsesDefault() {
        val result = resolvePlaybackSpeed(
            rememberBookSpeed = false,
            defaultSpeed = 1.25f,
            chapterIds = chapters,
            sourceVariantCount = 1,
            persisted = PlaybackSpeedCheckpoint("chapter-0", 1.75f),
            immediate = PlaybackSpeedCheckpoint("chapter-1", 2.0f, preferOverPersisted = true),
        )

        assertEquals(1.25f, result, 0.0001f)
    }

    @Test
    fun immediateOverrideWinsOverMatchingPersisted() {
        val result = resolvePlaybackSpeed(
            rememberBookSpeed = true,
            defaultSpeed = 1.0f,
            chapterIds = chapters,
            sourceVariantCount = 1,
            persisted = PlaybackSpeedCheckpoint("chapter-0", 1.5f),
            immediate = PlaybackSpeedCheckpoint("chapter-1", 1.8f, preferOverPersisted = true),
        )

        assertEquals(1.8f, result, 0.0001f)
    }

    @Test
    fun matchingPersistedWinsOverNonOverrideImmediate() {
        val result = resolvePlaybackSpeed(
            rememberBookSpeed = true,
            defaultSpeed = 1.0f,
            chapterIds = chapters,
            sourceVariantCount = 1,
            persisted = PlaybackSpeedCheckpoint("chapter-0", 1.6f),
            immediate = PlaybackSpeedCheckpoint("chapter-1", 1.2f),
        )

        assertEquals(1.6f, result, 0.0001f)
    }

    @Test
    fun multiSourcePersistedWithoutChapterIdFallsBackToImmediate() {
        val result = resolvePlaybackSpeed(
            rememberBookSpeed = true,
            defaultSpeed = 1.0f,
            chapterIds = chapters,
            sourceVariantCount = 2,
            persisted = PlaybackSpeedCheckpoint(null, 1.7f),
            immediate = PlaybackSpeedCheckpoint("chapter-1", 1.3f),
        )

        assertEquals(1.3f, result, 0.0001f)
    }

    @Test
    fun singleSourcePersistedWithoutChapterIdIsAccepted() {
        val result = resolvePlaybackSpeed(
            rememberBookSpeed = true,
            defaultSpeed = 1.0f,
            chapterIds = chapters,
            sourceVariantCount = 1,
            persisted = PlaybackSpeedCheckpoint(null, 1.4f),
            immediate = null,
        )

        assertEquals(1.4f, result, 0.0001f)
    }

    @Test
    fun invalidImmediateOverrideFallsBackToMatchingPersisted() {
        val result = resolvePlaybackSpeed(
            rememberBookSpeed = true,
            defaultSpeed = 1.0f,
            chapterIds = chapters,
            sourceVariantCount = 1,
            persisted = PlaybackSpeedCheckpoint("chapter-0", 1.6f),
            immediate = PlaybackSpeedCheckpoint("chapter-1", Float.NaN, preferOverPersisted = true),
        )

        assertEquals(1.6f, result, 0.0001f)
    }

    @Test
    fun invalidPersistedFallsBackToImmediate() {
        val result = resolvePlaybackSpeed(
            rememberBookSpeed = true,
            defaultSpeed = 1.0f,
            chapterIds = chapters,
            sourceVariantCount = 1,
            persisted = PlaybackSpeedCheckpoint("chapter-0", Float.POSITIVE_INFINITY),
            immediate = PlaybackSpeedCheckpoint("chapter-1", 1.2f),
        )

        assertEquals(1.2f, result, 0.0001f)
    }

    @Test
    fun finiteCheckpointSpeedIsClampedToSupportedRange() {
        val result = resolvePlaybackSpeed(
            rememberBookSpeed = true,
            defaultSpeed = 1.0f,
            chapterIds = chapters,
            sourceVariantCount = 1,
            persisted = null,
            immediate = PlaybackSpeedCheckpoint("chapter-1", 9.0f, preferOverPersisted = true),
        )

        assertEquals(3.0f, result, 0.0001f)
    }

    @Test
    fun invalidDefaultFallsBackToNormalSpeed() {
        val result = resolvePlaybackSpeed(
            rememberBookSpeed = false,
            defaultSpeed = Float.NaN,
            chapterIds = chapters,
            sourceVariantCount = 1,
            persisted = null,
            immediate = null,
        )

        assertEquals(1.0f, result, 0.0001f)
    }
}
