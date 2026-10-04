package com.example.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RememberPlaybackSpeedUseCaseTest {
    private val chapters = listOf("chapter-0", "chapter-1")

    @Test
    fun remembersCurrentChapterPositionAndSpeed() {
        val result = resolveRememberedPlaybackSpeedCheckpoint(
            rememberBookSpeed = true,
            chapterIds = chapters,
            chapterIndex = 1,
            positionMs = 12_345L,
            speed = 1.75f,
        )!!

        assertEquals("chapter-1", result.chapterId)
        assertEquals(1, result.chapterIndex)
        assertEquals(12_345L, result.positionMs)
        assertEquals(1.75f, result.speed, 0.0001f)
    }

    @Test
    fun disabledRememberSettingCreatesNoCheckpoint() {
        assertNull(
            resolveRememberedPlaybackSpeedCheckpoint(
                rememberBookSpeed = false,
                chapterIds = chapters,
                chapterIndex = 0,
                positionMs = 1L,
                speed = 1.5f,
            )
        )
    }

    @Test
    fun clampsChapterPositionAndSpeed() {
        val result = resolveRememberedPlaybackSpeedCheckpoint(
            rememberBookSpeed = true,
            chapterIds = chapters,
            chapterIndex = 99,
            positionMs = -5L,
            speed = 9f,
        )!!

        assertEquals("chapter-1", result.chapterId)
        assertEquals(1, result.chapterIndex)
        assertEquals(0L, result.positionMs)
        assertEquals(3f, result.speed, 0.0001f)
    }

    @Test
    fun clampsLowSpeed() {
        val result = resolveRememberedPlaybackSpeedCheckpoint(
            rememberBookSpeed = true,
            chapterIds = chapters,
            chapterIndex = 0,
            positionMs = 1L,
            speed = 0.1f,
        )!!

        assertEquals(0.5f, result.speed, 0.0001f)
    }

    @Test
    fun rejectsNonFiniteSpeed() {
        assertNull(
            resolveRememberedPlaybackSpeedCheckpoint(
                rememberBookSpeed = true,
                chapterIds = chapters,
                chapterIndex = 0,
                positionMs = 1L,
                speed = Float.NaN,
            )
        )
    }

    @Test
    fun emptyBookHasNoCheckpoint() {
        assertNull(
            resolveRememberedPlaybackSpeedCheckpoint(
                rememberBookSpeed = true,
                chapterIds = emptyList(),
                chapterIndex = 0,
                positionMs = 1L,
                speed = 1.5f,
            )
        )
    }
}
