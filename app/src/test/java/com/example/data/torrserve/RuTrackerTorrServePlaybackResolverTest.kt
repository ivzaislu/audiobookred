package com.example.data.torrserve

import com.example.data.model.BookDetailDto
import com.example.data.model.SourceVariantDto
import com.example.data.settings.ServiceCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuTrackerTorrServePlaybackResolverTest {
    @Test
    fun magnetBecomesPlayableAudioChapters() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "hash":"0123456789abcdef0123456789abcdef01234567",
                          "stat_string":"Torrent working",
                          "file_stats":[
                            {"id":1,"path":"Book/001 - Начало.mp3","length":1234},
                            {"id":2,"path":"Book/cover.jpg","length":456},
                            {"id":3,"path":"Book/002 - Продолжение.m4b","length":5678}
                          ]
                        }
                        """.trimIndent()
                    )
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-0/1234")
                    .setBody("x")
            )

            val client = TorrServeClient(
                http = OkHttpClient(),
                serverUrlProvider = { server.url("/").toString().trimEnd('/') },
                credentialsProvider = { ServiceCredentials("alice", "secret") },
            )
            val resolver = RuTrackerTorrServePlaybackResolver(client)
            val book = testBook()
            val stages = mutableListOf<TorrServePreparationStage>()

            val resolved = resolver.resolve(
                book = book,
                onPreparationStage = stages::add,
            )

            assertEquals(
                listOf(
                    TorrServePreparationStage.CONNECTING,
                    TorrServePreparationStage.WAITING_FOR_METADATA,
                    TorrServePreparationStage.BUFFERING,
                ),
                stages,
            )
            assertEquals(2, resolved.chapters.size)
            assertEquals("001 - Начало", resolved.chapters[0].title)
            assertEquals("002 - Продолжение", resolved.chapters[1].title)
            assertTrue(resolved.chapters[0].streamUrl.contains("/stream/001%20-%20"))
            assertTrue(
                resolved.chapters[0].streamUrl.contains(
                    "link=0123456789abcdef0123456789abcdef01234567"
                )
            )
            assertTrue(resolved.chapters[0].streamUrl.contains("index=1"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun playbackWarmupUsesSelectedChapterInsteadOfFirstAudioFile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "hash":"0123456789abcdef0123456789abcdef01234567",
                          "stat_string":"Torrent working",
                          "file_stats":[
                            {"id":1,"path":"Book/001.mp3","length":1234},
                            {"id":2,"path":"Book/cover.jpg","length":456},
                            {"id":3,"path":"Book/002.m4b","length":5678}
                          ]
                        }
                        """.trimIndent()
                    )
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-0/5678")
                    .setBody("x")
            )

            val client = TorrServeClient(
                http = OkHttpClient(),
                serverUrlProvider = { server.url("/").toString().trimEnd('/') },
                credentialsProvider = { ServiceCredentials("alice", "secret") },
            )
            val resolver = RuTrackerTorrServePlaybackResolver(client)

            val resolved = resolver.resolve(
                book = testBook(),
                warmChapterIndexSelector = { book ->
                    assertEquals(2, book.chapters.size)
                    1
                },
            )

            assertEquals(2, resolved.chapters.size)
            val metadataRequest = server.takeRequest()
            val warmupRequest = server.takeRequest()
            assertEquals("/torrents", metadataRequest.path)
            assertTrue(warmupRequest.path.orEmpty().contains("index=3"))
        }
    }

    @Test
    fun downloadResolutionKeepsTorrentSizesWithoutOpeningStream() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "hash":"0123456789abcdef0123456789abcdef01234567",
                          "file_stats":[
                            {"id":1,"path":"Book/001.mp3","length":1234},
                            {"id":2,"path":"Book/cover.jpg","length":456},
                            {"id":3,"path":"Book/002.flac","length":5678}
                          ]
                        }
                        """.trimIndent()
                    )
            )

            val client = TorrServeClient(
                http = OkHttpClient(),
                serverUrlProvider = { server.url("/").toString().trimEnd('/') },
                credentialsProvider = { ServiceCredentials("alice", "secret") },
            )
            val resolver = RuTrackerTorrServePlaybackResolver(client)

            val resolved = resolver.resolveForDownload(testBook())

            assertEquals(2, resolved.files.size)
            assertEquals(listOf(1234L, 5678L), resolved.files.map { it.length })
            assertEquals(2, resolved.book.chapters.size)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/torrents", request.path)
            assertTrue(request.body.readUtf8().contains("\"save_to_db\":true"))
        }
    }

    private fun testBook() = BookDetailDto(
        id = "rutracker:6910707",
        title = "Тестовая книга",
        selectedSource = "rutracker",
        selectedBookSourceId = "live:rutracker:6910707",
        sourceVariants = listOf(
            SourceVariantDto(
                bookSourceId = "live:rutracker:6910707",
                sourceCode = "rutracker",
                sourceName = "RuTracker",
                magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            )
        ),
    )
}
