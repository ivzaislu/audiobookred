package com.example.data.textsource

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class FantasyWorldsProviderTest {
    private fun suppliedBook() = requireNotNull(javaClass.getResource("/fantasy-worlds/book-31866.html")).readText()
    private val book = RemoteTextBook("1465", "Книга", FantasyWorldsHtmlParser.bookUrl("1465"),
        downloadUrl = FantasyWorldsHtmlParser.ORIGIN + "/lib/id1465/download/")
    private fun provider(server: MockWebServer) = FantasyWorldsProvider(OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: ""))).build())
        }.build())

    @Test fun listingAndDetailCanReuseTheSameVerifiedMetadataWithoutRepeatedRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(suppliedBook()))
            val provider = provider(server)
            val first = provider.book("31866")
            assertEquals("Екатерина Каблукова", first.authors.single().name)
            assertEquals(first, provider.book("31866"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun failedMetadataFetchIsNotCachedAndCanBeRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(suppliedBook()))
            val provider = provider(server)
            try { provider.book("31866"); fail("Expected metadata error") } catch (_: IllegalStateException) { }
            assertEquals("31866", provider.book("31866").id)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun redirectsRemainOnProviderAndDownloadSendsCardReferer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/files/book.zip"))
            val bytes = byteArrayOf(0x50, 0x4b, 3, 4, 1, 2)
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            assertArrayEquals(bytes, provider(server).download(book))
            val first = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("/lib/id1465/download/", first.path)
            assertEquals(book.url, first.getHeader("Referer"))
            assertEquals("/files/book.zip", server.takeRequest(3, TimeUnit.SECONDS)?.path)
        }
    }
    @Test fun externalRedirectAndHtmlDisguisedAsBookAreRejected() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.test/book.zip"))
            try { provider(server).download(book); fail("External redirect") } catch (_: IllegalStateException) { }
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody("<html>Проверка браузера</html>"))
            try { provider(server).download(book); fail("HTML file") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun oversizeResponseAndCancellationNeverReturnAPartialBook() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Length", 33L * 1024 * 1024))
            try { provider(server).download(book); fail("Oversize") } catch (_: IllegalArgumentException) { }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            try { withTimeout(1000) { provider(server).download(book) }; fail("Not cancelled") }
            catch (_: TimeoutCancellationException) { }
        }
    }
    @Test fun payloadSniffingDoesNotAcceptAnHtmlErrorPage() {
        assertFalse(FantasyWorldsProvider.isBookPayload("<html>404</html>".toByteArray()))
        assertTrue(FantasyWorldsProvider.isBookPayload("<?xml version='1.0'?><FictionBook xmlns='x'>".toByteArray()))
    }

    @Test fun chosenFormatDownloadsItsOwnRouteAndKeepsCardReferer() = runBlocking {
        MockWebServer().use { server ->
            val provider = provider(server)
            val parsed = FantasyWorldsHtmlParser.book(requireNotNull(javaClass.getResource("/fantasy-worlds/book.html")).readText(), book.url)
            val mobi = ByteArray(80).also { "BOOKMOBI".toByteArray().copyInto(it, 60) }
            val bodies = mapOf("fb2" to byteArrayOf(0x50, 0x4b, 3, 4), "epub" to byteArrayOf(0x50, 0x4b, 3, 4),
                "mobi" to mobi, "txt" to "Текст книги".toByteArray())
            for (option in parsed.downloads) {
                val bytes = bodies.getValue(option.code)
                server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
                assertArrayEquals(bytes, provider.downloadFile(parsed, option))
                val request = server.takeRequest(3, TimeUnit.SECONDS)!!
                assertEquals(option.url.removePrefix(FantasyWorldsHtmlParser.ORIGIN), request.path)
                assertEquals(book.url, request.getHeader("Referer"))
            }
        }
    }

    @Test fun fileDownloadRejectsUnlistedFormatsAndHtmlForEveryFormat() = runBlocking {
        MockWebServer().use { server ->
            val parsed = FantasyWorldsHtmlParser.book(requireNotNull(javaClass.getResource("/fantasy-worlds/book.html")).readText(), book.url)
            val provider = provider(server)
            val forged = parsed.downloads.first().copy(url = "https://evil.test/file")
            try { provider.downloadFile(parsed, forged); fail("Unlisted choice") } catch (_: IllegalArgumentException) { }
            assertEquals(0, server.requestCount)
            for (option in parsed.downloads) {
                server.enqueue(MockResponse().setBody("<!DOCTYPE html><html>Ошибка</html>"))
                try { provider.downloadFile(parsed, option); fail("HTML for ${option.code}") } catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun downloadFileCannotFollowAnExternalRedirect() = runBlocking {
        MockWebServer().use { server ->
            val parsed = FantasyWorldsHtmlParser.book(requireNotNull(javaClass.getResource("/fantasy-worlds/book.html")).readText(), book.url)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.test/book.epub"))
            try { provider(server).downloadFile(parsed, parsed.downloads.first { it.code == "epub" }); fail("Redirect") }
            catch (_: IllegalStateException) { }
            assertEquals(1, server.requestCount)
        }
    }
}
