package com.example.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMediaUrlPolicyTest {
    @Test
    fun publicHttpsStreamIsAccepted() {
        assertEquals(
            "https://cdn.example.org/audio/chapter-1.mp3",
            requireRemotePlaybackUrl("https://cdn.example.org/audio/chapter-1.mp3", "Глава 1"),
        )
    }

    @Test
    fun missingStreamFailsWithChapterLabel() {
        val error = runCatching { requireRemotePlaybackUrl("", "Глава 7") }.exceptionOrNull()

        assertTrue(error is UnavailablePlaybackMediaException)
        assertTrue(error?.message.orEmpty().contains("Глава 7"))
    }

    @Test
    fun localTorrServeStreamIsAcceptedForRuTrackerOnly() {
        val url = "http://192.168.1.20:8090/stream/chapter.mp3?link=abc&index=1&play"

        assertEquals(
            url,
            requireRemotePlaybackUrl(
                rawUrl = url,
                chapterLabel = "Глава 1",
                sourceCode = "rutracker",
            ),
        )

        val otherSourceError = runCatching {
            requireRemotePlaybackUrl(
                rawUrl = url,
                chapterLabel = "Глава 1",
                sourceCode = "audiopolka",
            )
        }.exceptionOrNull()
        assertTrue(otherSourceError is UnavailablePlaybackMediaException)
    }

    @Test(expected = UnavailablePlaybackMediaException::class)
    fun bareIpStreamIsRejected() {
        requireRemotePlaybackUrl("http://192.168.1.20/audio.mp3", "Глава 2")
    }
}
