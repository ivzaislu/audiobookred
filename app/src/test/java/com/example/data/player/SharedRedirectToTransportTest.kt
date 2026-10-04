package com.example.data.player

import com.example.data.api.ApiClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedRedirectToTransportTest {
    @Test
    fun myAudiobooksPlaybackCacheKeySelectsProviderReferer() {
        val resourceUrl = "https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/6/track.mp3"
        val key = playbackProviderCacheKey("myaudiobooks", resourceUrl)
        val source = playbackProviderSourceFromCacheKey(key)

        assertEquals("myaudiobooks", source)
        assertEquals(
            "https://my-audiobooks.com/",
            ApiClient.standaloneProviderRequestHeaders(source)["Referer"],
        )
    }

    @Test
    fun myAudiobooksMp3KeepsRangeAndDoesNotInventPlaylistOrigin() {
        val request = Request.Builder()
            .url("https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/6/track.mp3")
            .header("Referer", "https://my-audiobooks.com/")
            .header("Range", "bytes=1048576-2097151")
            .build()

        val prepared = ApiClient.applyStandaloneProviderHeaders(request)

        assertEquals("https://my-audiobooks.com/", prepared.header("Referer"))
        assertEquals("bytes=1048576-2097151", prepared.header("Range"))
        assertEquals(null, prepared.header("Origin"))
        assertTrue(prepared.header("User-Agent").orEmpty().contains("Chrome/152"))
    }

    @Test
    fun myAudiobooksPlaylistAddsObservedOrigin() {
        val request = Request.Builder()
            .url("https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/6/123456.pl.txt")
            .header("Referer", "https://my-audiobooks.com/")
            .build()

        val prepared = ApiClient.applyStandaloneProviderHeaders(request)

        assertEquals("https://my-audiobooks.com/", prepared.header("Referer"))
        assertEquals("https://my-audiobooks.com", prepared.header("Origin"))
        assertTrue(prepared.header("User-Agent").orEmpty().contains("Chrome/152"))
    }

    @Test
    fun bazaKnigSharedCdnPolicyRemainsUnchanged() {
        val request = Request.Builder()
            .url("https://redirectto.cc/audio/book.mp3")
            .header("Referer", "https://baza-knig.info/")
            .header("Range", "bytes=0-1048575")
            .build()

        val prepared = ApiClient.applyStandaloneProviderHeaders(request)

        assertEquals("https://baza-knig.info/", prepared.header("Referer"))
        assertEquals("https://baza-knig.info", prepared.header("Origin"))
        assertEquals("bytes=0-1048575", prepared.header("Range"))
        assertFalse(prepared.header("User-Agent").orEmpty().contains("Chrome/152"))
    }
}
