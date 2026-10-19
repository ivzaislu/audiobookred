package com.example.data.diagnostics

import org.junit.Assert.*
import org.junit.Test
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class DiagnosticHttpPolicyTest {
    @Test fun knownSourceHostsDoNotAcceptLookalikeSuffixes() {
        assertEquals("audiopolka", DiagnosticHttpPolicy.source("AUDIOPOLKA.CLUB"))
        assertEquals("audiopolka", DiagnosticHttpPolicy.source("cdn.audiopolka.club"))
        assertEquals("other", DiagnosticHttpPolicy.source("audiopolka.club.attacker.invalid"))
        assertEquals("other", DiagnosticHttpPolicy.source("not-audiopolka.club"))
    }

    @Test fun routesNeverContainIdentifiersOrQueries() {
        assertEquals("audio", DiagnosticHttpPolicy.route("/stream/SECRET.mp3", true))
        assertEquals("book_page", DiagnosticHttpPolicy.route("/book/SECRET/", false))
        assertEquals("playlist", DiagnosticHttpPolicy.route("/12345/SECRET.pl.txt", false))
        assertEquals("page", DiagnosticHttpPolicy.route("/SECRET/12345", false))
        assertEquals("torrserve", DiagnosticHttpPolicy.target("192.168.1.10", true))
    }

    @Test fun destinationKeysAreOpaqueStableWithinProcessAndDifferentForDifferentHosts() {
        val first = DiagnosticHttpPolicy.hostKey("private-server.invalid")
        assertTrue(Regex("[0-9a-f]{16}").matches(first))
        assertEquals(first, DiagnosticHttpPolicy.hostKey("private-server.invalid"))
        assertNotEquals(first, DiagnosticHttpPolicy.hostKey("another-server.invalid"))
        assertFalse(first.contains("private"))
    }

    @Test fun mediaAndHttpCanCorrelateAResourceWithoutDisclosingItsUrl() {
        val key = DiagnosticHttpPolicy.resourceKey("https://audioboo.org:443/SECRET?token=SECRET")
        assertEquals(key, DiagnosticHttpPolicy.resourceKey("https://audioboo.org/SECRET?token=SECRET"))
        assertTrue(Regex("[0-9a-f]{16}").matches(key))
        assertFalse(key.contains("SECRET"))
    }

    @Test fun responseMetadataIsReducedToFixedCategories() {
        assertEquals("html", DiagnosticHttpPolicy.contentType("text/html; SECRET=123"))
        assertEquals("json", DiagnosticHttpPolicy.contentType("application/problem+json"))
        assertEquals("audio", DiagnosticHttpPolicy.contentType("audio/mpeg"))
        assertEquals("other", DiagnosticHttpPolicy.contentType("SECRET"))
        assertEquals("missing", DiagnosticHttpPolicy.contentType(null))
    }

    @Test fun cookieJarPresenceComesFromNetworkRequestRatherThanPublicResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val url = server.url("/book")
            val client = OkHttpClient.Builder().cookieJar(object : CookieJar {
                override fun loadForRequest(url: HttpUrl) = listOf(
                    Cookie.Builder().name("session").value("SECRET").hostOnlyDomain(url.host).build(),
                )
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            }).build()
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    assertNull(response.request.header("Cookie"))
                    assertEquals("session=SECRET", server.takeRequest().getHeader("Cookie"))
                    val fields = DiagnosticHttpPolicy.responseRequestPresence(response)
                    assertEquals("true", fields["requestHeadersObserved"])
                    assertEquals("true", fields["sentCookie"])
                    assertFalse(fields.values.any { it.contains("SECRET") })
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun redirectReportsHeadersOfFinalExchange() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
            server.enqueue(MockResponse().setResponseCode(403))
            val url = server.url("/start")
            val client = OkHttpClient.Builder().cookieJar(object : CookieJar {
                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    if (url.encodedPath == "/start") listOf(
                        Cookie.Builder().name("session").value("SECRET").hostOnlyDomain(url.host).path("/start").build(),
                    ) else emptyList()
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            }).build()
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    assertEquals("session=SECRET", server.takeRequest().getHeader("Cookie"))
                    assertNull(server.takeRequest().getHeader("Cookie"))
                    assertEquals("false", DiagnosticHttpPolicy.responseRequestPresence(response)["sentCookie"])
                    assertEquals("true", DiagnosticHttpPolicy.responseRequestPresence(response.priorResponse!!)["sentCookie"])
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun cacheOnlyResponseDoesNotClaimThatCredentialsWereAbsentOnWire() {
        val request = Request.Builder().url("https://audioboo.org/book").header("Cookie", "SECRET").build()
        val cached = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").build()
        val response = cached.newBuilder().cacheResponse(cached).build()
        assertEquals(mapOf("requestHeadersObserved" to "false"), DiagnosticHttpPolicy.responseRequestPresence(response))
    }

    @Test fun newContextStillRejectsArbitraryStrings() {
        val event = DiagnosticPrivacy.event("http_complete", mapOf(
            "status" to "403", "source" to "audioboo", "operation" to "book", "requestId" to "12",
            "resourceKey" to "token=SECRET", "targetResourceKey" to "https://SECRET",
            "hostKey" to "https://SECRET", "route" to "/private/SECRET", "target" to "SECRET",
            "reasonName" to "SECRET", "cookie" to "SECRET", "errorName" to "SECRET",
            "format" to "html", "sentCookie" to "true",
        ))!!
        assertTrue(event.contains("status=403"))
        assertTrue(event.contains("operation=book"))
        assertTrue(event.contains("sentCookie=true"))
        assertFalse(event.contains("SECRET"))
    }
}
