package com.example.data.api

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientSecurityTest {
    @Test
    fun identicalEffectiveTimeoutsReuseStandaloneHttpClient() {
        val defaultClient = ApiClient.createHttpClient()
        val explicitDefaultClient = ApiClient.createHttpClient(30L)
        val coercedDefaultClient = ApiClient.createHttpClient(10L)
        val downloadClient = ApiClient.createHttpClient(120L)

        assertSame(defaultClient, explicitDefaultClient)
        assertSame(defaultClient, coercedDefaultClient)
        assertNotSame(defaultClient, downloadClient)
    }

    @Test
    fun backendOriginRequiresSameSchemeHostAndEffectivePort() {
        assertTrue(ApiClient.sameOrigin("http://example.test:8000/", "http://example.test:8000/file"))
        assertTrue(ApiClient.sameOrigin("https://example.test/", "https://EXAMPLE.test/file"))
        assertFalse(ApiClient.sameOrigin("http://example.test:8000/", "https://example.test:8000/file"))
        assertFalse(ApiClient.sameOrigin("http://example.test:8000/", "http://cdn.example.test:8000/file"))
        assertFalse(ApiClient.sameOrigin("http://example.test:8000/", "http://example.test:9000/file"))
        assertFalse(ApiClient.sameOrigin("http://example.test:8000/", "file:///tmp/audio.mp3"))
    }

    @Test
    fun backendHeadersAreRemovedBeforeCrossOriginExchange() {
        val request = Request.Builder()
            .url("https://cdn.example.test/audio.mp3")
            .header("X-Client-Id", "client")
            .header("X-Device-Id", "device")
            .header("X-Device-Name", "phone")
            .header("X-App-Version", "0.5.3.6")
            .header("X-Profile-Id", "profile")
            .header("X-Device-Token", "secret-token")
            .header("X-Profile-Key", "secret-key")
            .header("Range", "bytes=100-")
            .build()
        val stripped = BackendHeaderPolicy.strip(request)
        BackendHeaderPolicy.names.forEach { assertNull(stripped.header(it)) }
        assertEquals("bytes=100-", stripped.header("Range"))
    }

    @Test
    fun bazaKnigCdnGetsHotlinkHeadersWithoutLosingRange() {
        val request = Request.Builder()
            .url("https://j3wccg4mgjcw.redirectto.cc/s01/2/1/5/7/9/0.mp3")
            .header("User-Agent", "AudioBookRed/Test")
            .header("Range", "bytes=100-")
            .build()

        val enriched = ApiClient.applyStandaloneProviderHeaders(request)

        assertEquals("https://baza-knig.info", enriched.header("Origin"))
        assertEquals("https://baza-knig.info/", enriched.header("Referer"))
        assertEquals("bytes=100-", enriched.header("Range"))
        assertTrue(enriched.header("User-Agent").orEmpty().startsWith("Mozilla/5.0"))
        assertTrue(ApiClient.isRedirectToCdnHost(enriched.url.host))
    }

    @Test
    fun myAudiobooksAudioUsesItsRefererWithoutBazaOrigin() {
        val request = Request.Builder()
            .url("https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/0.mp3")
            .header("Referer", "https://my-audiobooks.com/")
            .header("Range", "bytes=0-")
            .build()

        val enriched = ApiClient.applyStandaloneProviderHeaders(request)

        assertNull(enriched.header("Origin"))
        assertEquals("https://my-audiobooks.com/", enriched.header("Referer"))
        assertEquals("bytes=0-", enriched.header("Range"))
        assertTrue(enriched.header("User-Agent").orEmpty().contains("Chrome/152"))
        assertTrue(ApiClient.isRedirectToCdnHost(enriched.url.host))
    }

    @Test
    fun myAudiobooksPlaylistKeepsCapturedOrigin() {
        val request = Request.Builder()
            .url("https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/123458.pl.txt")
            .header("Origin", "https://my-audiobooks.com")
            .header("Referer", "https://my-audiobooks.com/")
            .build()

        val enriched = ApiClient.applyStandaloneProviderHeaders(request)

        assertEquals("https://my-audiobooks.com", enriched.header("Origin"))
        assertEquals("https://my-audiobooks.com/", enriched.header("Referer"))
        assertTrue(enriched.header("User-Agent").orEmpty().contains("Chrome/152"))
    }

    @Test
    fun sourceHintsOnlyExposeProviderReferer() {
        assertEquals(
            mapOf("Referer" to "https://my-audiobooks.com/"),
            ApiClient.standaloneProviderRequestHeaders(" MYAUDIOBOOKS "),
        )
        assertEquals(
            mapOf("Referer" to "https://baza-knig.info/"),
            ApiClient.standaloneProviderRequestHeaders("bazaknig"),
        )
        assertTrue(ApiClient.standaloneProviderRequestHeaders("uknig").isEmpty())
    }

    @Test
    fun providerHeadersDoNotLeakToUnrelatedHosts() {
        val request = Request.Builder()
            .url("https://cdn.example.test/audio.mp3")
            .header("Range", "bytes=0-")
            .build()

        val untouched = ApiClient.applyStandaloneProviderHeaders(request)

        assertNull(untouched.header("Origin"))
        assertNull(untouched.header("Referer"))
        assertEquals("bytes=0-", untouched.header("Range"))
        assertFalse(ApiClient.isRedirectToCdnHost(untouched.url.host))
    }

    @Test
    fun externalCoverUrlsStayDirectExceptLegacyFastPicNormalization() {
        assertEquals(
            "https://i125.fastpic.org/big/2026/0821/example.jpg",
            ApiClient.coverImageUrl("https://i125.fastpic.org/big/2026/0821/example.jpg"),
        )
        assertEquals(
            "https://i11.fastpic.org/big/2016/0101/example.jpg",
            ApiClient.coverImageUrl("http://i11.fastpic.ru/big/2016/0101/example.jpg"),
        )

        val ordinary = listOf(
            "https://cdn.example.test/covers/example.jpg",
            "https://cdn.example.test:443/covers/example.jpg",
            "http://cdn.example.test:80/covers/example.jpg",
        )
        ordinary.forEach { url ->
            assertEquals(url, ApiClient.coverImageUrl(url))
        }
    }

    @Test
    fun relativePlaceholderIpAndUnusualPortUrlsAreRejectedInStandaloneMode() {
        assertNull(ApiClient.coverImageUrl("/covers/example.jpg"))
        assertNull(ApiClient.externalHttpUrl("/covers/example.jpg"))
        assertNull(ApiClient.externalHttpUrl(ApiClient.baseUrl.trimEnd('/') + "/covers/example.jpg"))
        assertNull(ApiClient.externalHttpUrl("http://203.0.113.10:8000/covers/example.jpg"))
        assertNull(ApiClient.externalHttpUrl("https://203.0.113.10/audio.mp3"))
        assertNull(ApiClient.externalHttpUrl("https://cdn.example.test:8443/audio.mp3"))
        assertTrue(ApiClient.isDisallowedStandaloneTarget("http://203.0.113.10:8000/file"))
        assertTrue(ApiClient.isDisallowedStandaloneTarget("https://cdn.example.test:8443/file"))
        assertFalse(ApiClient.isDisallowedStandaloneTarget("https://cdn.example.test/file"))
    }

    @Test
    fun resolvedPrivateAndSpecialAddressesAreRejected() {
        val blocked = listOf(
            "0.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.169.254",
            "172.16.0.1",
            "192.168.1.1",
            "198.18.0.1",
            "224.0.0.1",
            "::1",
            "fd00::1",
            "fe80::1",
            "2001:db8::1",
        )

        blocked.forEach { raw ->
            assertTrue(raw, ApiClient.isDisallowedResolvedAddress(InetAddress.getByName(raw)))
        }
    }

    @Test
    fun resolvedPublicAddressesRemainAllowed() {
        val public = listOf(
            "1.1.1.1",
            "8.8.8.8",
            "2606:4700:4700::1111",
            "2001:4860:4860::8888",
        )

        public.forEach { raw ->
            assertFalse(raw, ApiClient.isDisallowedResolvedAddress(InetAddress.getByName(raw)))
        }
    }

    @Test
    fun dnsRejectsEntireAnswerWhenAnyAddressIsPrivate() {
        val delegate = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("127.0.0.1"),
            )
        }

        assertThrows(UnknownHostException::class.java) {
            StandalonePublicDns(delegate).lookup("mixed.example")
        }
    }

    @Test
    fun dnsReturnsPublicAnswersUnchanged() {
        val expected = listOf(
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("2606:4700:4700::1111"),
        )
        val delegate = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = expected
        }

        assertEquals(expected, StandalonePublicDns(delegate).lookup("public.example"))
    }
}
