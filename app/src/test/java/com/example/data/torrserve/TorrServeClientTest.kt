package com.example.data.torrserve

import com.example.data.settings.ServiceCredentials
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrServeClientTest {
    @Test
    fun savedCredentialsAreSentOnSuccessfulRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("[]")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("MatriX.133")
            )

            val credentials = ServiceCredentials("alice", "secret")
            val client = client(
                server = server,
                credentialsProvider = { credentials },
            )

            val info = client.checkConnection()

            assertEquals("MatriX.133", info.version)
            assertEquals(2, server.requestCount)

            val expectedAuth = Credentials.basic("alice", "secret", Charsets.UTF_8)
            val apiRequest = server.takeRequest()
            val echoRequest = server.takeRequest()
            assertEquals("/torrents", apiRequest.path)
            assertEquals(expectedAuth, apiRequest.getHeader("Authorization"))
            assertEquals("/echo", echoRequest.path)
            assertEquals(expectedAuth, echoRequest.getHeader("Authorization"))
        }
    }

    @Test
    fun prepareMagnetPollsUntilMetadataAndUsesOneBasedFileIndex() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "hash":"0123456789abcdef0123456789abcdef01234567",
                          "file_stats":null
                        }
                        """.trimIndent()
                    )
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "hash":"0123456789abcdef0123456789abcdef01234567",
                          "file_stats":[
                            {"id":1,"path":"Book/001.mp3","length":1234}
                          ]
                        }
                        """.trimIndent()
                    )
            )

            val client = client(server)
            val stages = mutableListOf<TorrServePreparationStage>()
            val torrent = client.prepareMagnet(
                magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                title = "Book",
                onPreparationStage = stages::add,
            )

            assertEquals(
                listOf(
                    TorrServePreparationStage.CONNECTING,
                    TorrServePreparationStage.WAITING_FOR_METADATA,
                ),
                stages,
            )
            assertEquals(1, torrent.fileStats.orEmpty().single().id)
            val streamUrl = client.streamUrl(
                torrent.hash,
                torrent.fileStats.orEmpty().single(),
            )
            assertTrue(streamUrl.contains("index=1"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun streamReadinessRetriesTransientResponsesUntilPartialContent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-0/1234")
                    .setBody("x")
            )

            val client = client(server)
            client.awaitStreamReady(
                hash = "0123456789abcdef0123456789abcdef01234567",
                file = TorrServeTorrentFile(
                    id = 1,
                    path = "Book/001.mp3",
                    length = 1234,
                ),
                timeoutMs = 2_000L,
                pollDelayMs = 1L,
            )

            assertEquals(3, server.requestCount)
            repeat(3) {
                val request = server.takeRequest()
                assertEquals("bytes=0-0", request.getHeader("Range"))
                assertEquals(
                    Credentials.basic("alice", "secret", Charsets.UTF_8),
                    request.getHeader("Authorization"),
                )
            }
        }
    }

    @Test
    fun configuredStreamUrlAcceptsOnlyCurrentTorrServeOrigin() {
        MockWebServer().use { server ->
            val client = client(server)
            val valid = server.url(
                "/stream/001.mp3?link=0123456789abcdef0123456789abcdef01234567&index=1&play"
            ).toString()

            assertEquals(valid, client.configuredStreamUrl(valid))
            assertEquals(
                null,
                client.configuredStreamUrl(
                    "http://127.0.0.1:9999/stream/001.mp3?link=abc&index=1&play"
                ),
            )
            assertEquals(
                null,
                client.configuredStreamUrl(
                    server.url("/torrents?link=abc&index=1").toString()
                ),
            )
            assertEquals(
                null,
                client.configuredStreamUrl(
                    server.url("/stream/001.mp3?link=abc").toString()
                ),
            )
        }
    }

    @Test
    fun streamingClientAddsCredentialsOnlyForCurrentConfiguredStreamOrigin() {
        MockWebServer().use { currentServer ->
            MockWebServer().use { staleServer ->
                currentServer.enqueue(MockResponse().setResponseCode(206).setBody("x"))
                val credentials = ServiceCredentials("alice", "secret")
                val client = client(
                    server = currentServer,
                    credentialsProvider = { credentials },
                )
                val validUrl = currentServer.url(
                    "/stream/001.mp3?link=0123456789abcdef0123456789abcdef01234567&index=1&play"
                ).toString()

                client.streamingHttpClient()
                    .newCall(
                        Request.Builder()
                            .url(validUrl)
                            .header("Authorization", "Basic stale-value")
                            .build()
                    )
                    .execute()
                    .use { response ->
                        assertEquals(206, response.code)
                    }

                assertEquals(1, currentServer.requestCount)
                assertEquals(
                    Credentials.basic("alice", "secret", Charsets.UTF_8),
                    currentServer.takeRequest().getHeader("Authorization"),
                )

                val staleUrl = staleServer.url(
                    "/stream/001.mp3?link=0123456789abcdef0123456789abcdef01234567&index=1&play"
                ).toString()
                val error = runCatching {
                    client.streamingHttpClient()
                        .newCall(
                            Request.Builder()
                                .url(staleUrl)
                                .header("Authorization", "Basic must-not-leak")
                                .build()
                        )
                        .execute()
                        .use { }
                }.exceptionOrNull()

                assertTrue(error is TorrServeConfigurationException)
                assertEquals(0, staleServer.requestCount)
            }
        }
    }

    @Test
    fun wrongSavedCredentialsRetryOnceThenReturnAuthorizationError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))

            val credentials = ServiceCredentials("alice", "wrong")
            val client = client(
                server = server,
                credentialsProvider = { credentials },
            )

            val error = capture<TorrServeAuthorizationException> {
                client.postJson("torrents", """{"action":"list"}""")
            }

            assertEquals(
                "TorrServe отклонил авторизацию. Проверьте сохранённые логин и пароль.",
                error.message,
            )
            assertEquals(2, server.requestCount)

            val expectedAuth = Credentials.basic("alice", "wrong", Charsets.UTF_8)
            assertEquals(expectedAuth, server.takeRequest().getHeader("Authorization"))
            assertEquals(expectedAuth, server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test
    fun unauthorizedRequestReloadsCredentialsAndSucceedsOnRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true}""")
            )

            var reads = 0
            val client = client(
                server = server,
                credentialsProvider = {
                    reads += 1
                    if (reads == 1) {
                        ServiceCredentials("alice", "old-password")
                    } else {
                        ServiceCredentials("alice", "new-password")
                    }
                },
            )

            val result = client.postJson("torrents", """{"action":"list"}""")

            assertEquals("""{"ok":true}""", result)
            assertEquals(2, server.requestCount)
            assertEquals(
                Credentials.basic("alice", "old-password", Charsets.UTF_8),
                server.takeRequest().getHeader("Authorization"),
            )
            assertEquals(
                Credentials.basic("alice", "new-password", Charsets.UTF_8),
                server.takeRequest().getHeader("Authorization"),
            )
        }
    }

    @Test
    fun forbiddenReturnsAccessDeniedMessageWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val client = client(server)

            val error = capture<TorrServeAccessDeniedException> {
                client.postJson("torrents", """{"action":"list"}""")
            }

            assertEquals(
                "TorrServe запретил этот запрос. Проверьте права пользователя и режим read-only сервера.",
                error.message,
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun missingApiReturnsClear404MessageWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val client = client(server)

            val error = capture<TorrServeHttpException> {
                client.postJson("torrents", """{"action":"list"}""")
            }

            assertEquals(404, error.statusCode)
            assertEquals(
                "TorrServe доступен, но нужный API не найден. Проверьте адрес и версию TorrServer.",
                error.message,
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun serverFailureReturnsStatusInMessageWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val client = client(server)

            val error = capture<TorrServeHttpException> {
                client.postJson("torrents", """{"action":"list"}""")
            }

            assertEquals(503, error.statusCode)
            assertEquals(
                "TorrServe временно недоступен: ошибка сервера HTTP 503.",
                error.message,
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun networkFailureReturnsConnectionMessage() = runBlocking {
        val failingHttp = OkHttpClient.Builder()
            .addInterceptor {
                throw IOException("network unavailable")
            }
            .build()

        val client = TorrServeClient(
            http = failingHttp,
            serverUrlProvider = { "http://127.0.0.1:8090" },
            credentialsProvider = { ServiceCredentials("alice", "secret") },
        )

        val error = capture<TorrServeConnectionException> {
            client.postJson("torrents", """{"action":"list"}""")
        }

        assertEquals(
            "Не удалось подключиться к TorrServe. Проверьте адрес сервера и доступность устройства.",
            error.message,
        )
    }

    @Test
    fun authorizationMessageExplainsMissingCredentials() {
        assertEquals(
            "TorrServe требует авторизацию. Укажите логин и пароль в настройках.",
            torrServeAuthorizationMessage(ServiceCredentials()),
        )
    }

    @Test
    fun authorizationMessageExplainsPartialCredentials() {
        assertEquals(
            "Для TorrServe заполнены не все данные авторизации. Укажите и логин, и пароль.",
            torrServeAuthorizationMessage(ServiceCredentials(login = "user")),
        )
    }

    private fun client(
        server: MockWebServer,
        credentialsProvider: () -> ServiceCredentials = {
            ServiceCredentials("alice", "secret")
        },
    ): TorrServeClient = TorrServeClient(
        http = OkHttpClient(),
        serverUrlProvider = { server.url("/").toString().trimEnd('/') },
        credentialsProvider = credentialsProvider,
    )

    private suspend inline fun <reified T : Throwable> capture(
        crossinline block: suspend () -> Unit,
    ): T {
        return try {
            block()
            throw AssertionError("Expected " + T::class.java.simpleName)
        } catch (error: Throwable) {
            assertTrue(
                "Expected " + T::class.java.simpleName + ", got " + error::class.java.simpleName,
                error is T,
            )
            error as T
        }
    }
}
