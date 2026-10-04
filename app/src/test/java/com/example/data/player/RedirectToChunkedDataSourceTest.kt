package com.example.data.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectToChunkedDataSourceTest {
    @Test
    fun rutrackerRetriesOnlyTransientTorrServeStatuses() {
        assertTrue(isTransientRuTrackerStreamStatus(404))
        assertTrue(isTransientRuTrackerStreamStatus(409))
        assertTrue(isTransientRuTrackerStreamStatus(425))
        assertTrue(isTransientRuTrackerStreamStatus(429))
        assertTrue(isTransientRuTrackerStreamStatus(500))
        assertTrue(isTransientRuTrackerStreamStatus(503))

        assertFalse(isTransientRuTrackerStreamStatus(400))
        assertFalse(isTransientRuTrackerStreamStatus(401))
        assertFalse(isTransientRuTrackerStreamStatus(403))
    }

    @Test
    fun parsesContentRangeTotalsCapturedFromSharedRedirectToCdn() {
        assertEquals(
            10_863_177L,
            parseRedirectToContentRangeTotal(
                mapOf("Content-Range" to listOf("bytes 32768-1081343/10863177"))
            ),
        )
        assertEquals(
            13_105_528L,
            parseRedirectToContentRangeTotal(
                mapOf("content-range" to listOf("bytes 0-1048575/13105528"))
            ),
        )
        assertEquals(
            10_863_177L,
            parseRedirectToContentRangeTotal(
                mapOf("Content-Range" to listOf("bytes */10863177"))
            ),
        )
    }

    @Test
    fun unknownOrMalformedContentRangeHasNoInventedLength() {
        assertNull(
            parseRedirectToContentRangeTotal(
                mapOf("Content-Range" to listOf("bytes 0-1048575/*"))
            )
        )
        assertNull(parseRedirectToContentRangeTotal(mapOf("Content-Length" to listOf("1048576"))))
    }

    @Test
    fun requestedRangeEndMatchesSequentialOneMiBHarWindows() {
        assertEquals(1_048_576L, REDIRECTTO_CDN_CHUNK_BYTES)
        assertEquals(2_129_920L, requestedEndExclusive(1_081_344L, REDIRECTTO_CDN_CHUNK_BYTES))
        assertEquals(3_178_496L, requestedEndExclusive(2_129_920L, REDIRECTTO_CDN_CHUNK_BYTES))
        assertEquals(-1L, requestedEndExclusive(0L, C.LENGTH_UNSET.toLong()))
    }

    @Test
    fun requestedRangeEndSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, requestedEndExclusive(Long.MAX_VALUE - 10L, 100L))
    }

    @Test
    fun playbackProviderCacheKeyCarriesSourceForSharedCdnRequests() {
        val key = playbackProviderCacheKey(
            sourceCode = " MYAUDIOBOOKS ",
            resourceUrl = "https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/0.mp3",
        )

        assertEquals("myaudiobooks", playbackProviderSourceFromCacheKey(key))
        assertNull(playbackProviderSourceFromCacheKey("https://example.test/audio.mp3"))
        assertNull(playbackProviderCacheKey("   ", "https://example.test/audio.mp3"))
    }
}
