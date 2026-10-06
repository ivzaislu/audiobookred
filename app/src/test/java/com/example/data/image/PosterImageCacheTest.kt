package com.example.data.image

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterImageCacheTest {
    @Test
    fun canonicalizesLegacyFastPicAndBuildsFullviewUrl() {
        val legacy =
            "http://i120.fastpic.ru/big/2022/0612/60/2ff3f4b562cdf1e91b1938348933d860.jpg"

        assertEquals(
            "https://i120.fastpic.org/big/2022/0612/60/2ff3f4b562cdf1e91b1938348933d860.jpg",
            fastPicCanonicalImageUrl(legacy),
        )
        assertEquals(
            "https://fastpic.org/fullview/120/2022/0612/2ff3f4b562cdf1e91b1938348933d860.jpg",
            fastPicFullviewUrl(legacy),
        )
        assertEquals(
            listOf(
                "https://fastpic.org/fullview/120/2022/0612/2ff3f4b562cdf1e91b1938348933d860.jpg",
                "https://fastpic.org/view/120/2022/0612/2ff3f4b562cdf1e91b1938348933d860.jpg.html",
            ),
            fastPicViewerUrls(legacy),
        )
    }

    @Test
    fun signedUrlIsParsedOnlyForSameAssetAndFutureExpiry() {
        val source =
            "https://i128.fastpic.org/big/2026/0916/26/546a528aeb5befc02fe50f6c96412726.jpg"
        val expires = System.currentTimeMillis() / 1000L + 3600L
        val signed = source + "?md5=test-signature&expires=" + expires
        val html = "<script>loading_img = '" +
            signed.replace("&", "&amp;") +
            "';</script>"

        assertEquals(signed, fastPicSignedUrlFromHtml(source, html))
        assertTrue((fastPicSignedExpirySeconds(signed) ?: 0L) >= expires)

        val other =
            "https://i128.fastpic.org/big/2026/0916/26/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg" +
                "?md5=test-signature&expires=" + expires
        assertNull(
            fastPicSignedUrlFromHtml(
                source,
                "<script>loading_img = '$other';</script>",
            )
        )
    }

    @Test
    fun unsignedOrNonFastPicUrlsAreNotResolvedAsSignedAssets() {
        assertNull(
            fastPicCanonicalImageUrl(
                "https://example.org/big/2026/0916/26/546a528aeb5befc02fe50f6c96412726.jpg"
            )
        )
        assertNull(
            fastPicSignedExpirySeconds(
                "https://i128.fastpic.org/big/2026/0916/26/546a528aeb5befc02fe50f6c96412726.jpg"
            )
        )
    }

    @Test
    fun audiobooCoverHeadersReuseBrowserSession() {
        val request = Request.Builder()
            .url("https://audioboo.org/uploads/posts/2026-07/example.jpg")
            .build()

        val result = applyAudiobooCoverHeaders(
            request = request,
            userAgent = "AudiobooBrowser/1.0",
            cookieHeader = "cf_clearance=test; session=ok",
        )

        assertEquals("AudiobooBrowser/1.0", result.header("User-Agent"))
        assertEquals("cf_clearance=test; session=ok", result.header("Cookie"))
        assertEquals("https://audioboo.org/", result.header("Referer"))
        assertEquals("image", result.header("Sec-Fetch-Dest"))
        assertEquals("same-origin", result.header("Sec-Fetch-Site"))
    }

    @Test
    fun audiobooCoverHeadersDoNotLeakToOtherHosts() {
        val request = Request.Builder()
            .url("https://example.org/cover.jpg")
            .header("User-Agent", "Original")
            .build()

        val result = applyAudiobooCoverHeaders(
            request = request,
            userAgent = "AudiobooBrowser/1.0",
            cookieHeader = "cf_clearance=test",
        )

        assertEquals("Original", result.header("User-Agent"))
        assertNull(result.header("Cookie"))
        assertNull(result.header("Referer"))
    }
}
