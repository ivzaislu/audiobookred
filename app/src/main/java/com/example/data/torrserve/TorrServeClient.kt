package com.example.data.torrserve

import android.content.Context
import com.example.data.settings.ExternalServiceCredentialsStore
import com.example.data.settings.ServiceCredentials
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext

private data class TorrServeTorrentRequest(
    val action: String,
    val link: String = "",
    val hash: String = "",
    val title: String = "",
    val poster: String = "",
    @Json(name = "save_to_db") val saveToDb: Boolean = false,
)

/**
 * Shared TorrServer HTTP client.
 *
 * TorrServer uses HTTP Basic authentication when --httpauth is enabled. There is
 * no login token/session to persist: each protected request carries the current
 * saved credentials. A 401 causes one credentials refresh/retry so a request
 * that races with a settings update can recover without looping.
 */
@Singleton
class TorrServeClient internal constructor(
    private val http: okhttp3.OkHttpClient,
    private val serverUrlProvider: () -> String,
    private val credentialsProvider: () -> ServiceCredentials,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        credentialsStore: ExternalServiceCredentialsStore,
    ) : this(
        http = buildTorrServeHttpClient(readTimeoutSeconds = 45L),
        serverUrlProvider = credentialsStore::torrServeUrl,
        credentialsProvider = credentialsStore::torrServeCredentials,
    )

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val torrentRequestAdapter = moshi.adapter(TorrServeTorrentRequest::class.java)
    private val torrentStatusAdapter = moshi.adapter(TorrServeTorrentStatus::class.java)
    private val torrentStatusListAdapter = moshi.adapter<List<TorrServeTorrentStatus>>(
        Types.newParameterizedType(List::class.java, TorrServeTorrentStatus::class.java)
    )

    suspend fun prepareMagnet(
        magnetUri: String,
        title: String,
        poster: String = "",
        saveToDb: Boolean = false,
        onPreparationStage: (TorrServePreparationStage) -> Unit = {},
    ): TorrServeTorrentStatus = withContext(Dispatchers.IO) {
        val magnet = magnetUri.trim()
        if (!magnet.startsWith("magnet:?", ignoreCase = true)) {
            throw TorrServeConfigurationException(
                "RuTracker не вернул корректную magnet-ссылку для этой книги."
            )
        }

        onPreparationStage(TorrServePreparationStage.CONNECTING)
        val added = torrentRequest(
            TorrServeTorrentRequest(
                action = "add",
                link = magnet,
                title = title.trim(),
                poster = poster.trim(),
                saveToDb = saveToDb,
            )
        )
        val hash = added.hash.trim().ifBlank { infoHashFromMagnet(magnet).orEmpty() }
        if (hash.isBlank()) {
            throw TorrServeHttpException(
                statusCode = 200,
                message = "TorrServe добавил magnet, но не вернул infohash раздачи.",
            )
        }

        onPreparationStage(TorrServePreparationStage.WAITING_FOR_METADATA)
        if (added.fileStats.orEmpty().isNotEmpty()) return@withContext added.copy(hash = hash)

        val deadlineNanos = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(TORRENT_METADATA_TIMEOUT_SECONDS)
        var last = added.copy(hash = hash)

        while (System.nanoTime() < deadlineNanos) {
            delay(TORRENT_METADATA_POLL_MS)
            last = try {
                torrentRequest(
                    TorrServeTorrentRequest(
                        action = "get",
                        hash = hash,
                    )
                )
            } catch (error: TorrServeHttpException) {
                if (error.statusCode == 404) continue
                throw error
            }
            if (last.fileStats.orEmpty().isNotEmpty()) return@withContext last.copy(hash = hash)
        }

        throw TorrServeConnectionException(
            "TorrServe не получил метаданные раздачи за ${TORRENT_METADATA_TIMEOUT_SECONDS} секунд. " +
                "Проверьте доступность сидов и повторите запуск."
        )
    }

    suspend fun awaitStreamReady(
        hash: String,
        file: TorrServeTorrentFile,
        timeoutMs: Long = TORRENT_STREAM_READY_TIMEOUT_MS,
        pollDelayMs: Long = TORRENT_STREAM_READY_POLL_MS,
    ) = withContext(Dispatchers.IO) {
        val url = streamUrl(hash, file)
        val deadlineNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1L))
        var lastTransientCode: Int? = null

        while (System.nanoTime() < deadlineNanos) {
            try {
                val response = executeProtected(
                    requestFactory = { _, credentials ->
                        Request.Builder()
                            .url(url)
                            .get()
                            .header("Range", "bytes=0-0")
                            .header("Accept", "*/*")
                            .applyBasicAuth(credentials)
                            .build()
                    },
                )
                response.use {
                    // executeProtected only returns 2xx responses. 200 is valid
                    // for TorrServe builds that ignore Range; 206 is the common
                    // partial-content response.
                }
                return@withContext
            } catch (error: TorrServeHttpException) {
                if (!isTransientStreamStatus(error.statusCode)) throw error
                lastTransientCode = error.statusCode
            }

            delay(pollDelayMs.coerceAtLeast(1L))
        }

        val suffix = lastTransientCode?.let { " (последний HTTP $it)" }.orEmpty()
        throw TorrServeConnectionException(
            "TorrServe принял раздачу, но аудиопоток ещё не готов$suffix. " +
                "Подождите несколько секунд и повторите запуск."
        )
    }

    fun streamUrl(
        hash: String,
        file: TorrServeTorrentFile,
    ): String {
        val cleanHash = hash.trim()
        require(cleanHash.isNotBlank()) { "TorrServe torrent hash is blank" }
        require(file.id > 0) { "TorrServe file id must be positive" }

        val fileName = file.path
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "audio" }

        return requireBaseUrl()
            .trimEnd('/')
            .plus("/")
            .toHttpUrl()
            .newBuilder()
            .addPathSegment("stream")
            .addPathSegment(fileName)
            .addQueryParameter("link", cleanHash)
            .addQueryParameter("index", file.id.toString())
            .addQueryParameter("play", null)
            .build()
            .toString()
    }

    /**
     * HTTP client dedicated to TorrServe /stream reads.
     *
     * The stream URL may be restored from cache while the user has already
     * changed the configured TorrServe server. Validate the destination before
     * adding Basic auth so current credentials can never be sent to a stale or
     * foreign origin.
     */
    fun streamingHttpClient(): okhttp3.OkHttpClient =
        http.newBuilder()
            .readTimeout(120L, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                val configuredUrl = configuredStreamUrl(request.url.toString())
                    ?: throw TorrServeConfigurationException(
                        "Адрес TorrServe изменился или stream URL устарел. " +
                            "Повторите запуск книги, чтобы заново подготовить поток."
                    )
                val guarded = request.newBuilder()
                    .url(configuredUrl)
                    .removeHeader("Authorization")
                    .applyBasicAuth(credentialsProvider())
                    .build()
                chain.proceed(guarded)
            }
            .build()

    fun configuredStreamUrl(rawUrl: String): String? {
        val candidate = rawUrl.trim().toHttpUrlOrNull() ?: return null
        val base = runCatching { requireBaseUrl().trimEnd('/').toHttpUrl() }.getOrNull()
            ?: return null
        if (
            candidate.scheme != base.scheme ||
            !candidate.host.equals(base.host, ignoreCase = true) ||
            candidate.port != base.port
        ) {
            return null
        }

        val basePath = base.encodedPath.trimEnd('/')
        val streamPrefix = (if (basePath.isBlank()) "" else basePath) + "/stream/"
        if (!candidate.encodedPath.startsWith(streamPrefix)) return null
        if (candidate.queryParameter("link").isNullOrBlank()) return null
        if (candidate.queryParameter("index")?.toIntOrNull()?.let { it > 0 } != true) return null
        return candidate.toString()
    }

    suspend fun checkConnection(): TorrServeConnectionInfo {
        val response = executeProtected(
            requestFactory = { baseUrl, credentials ->
                Request.Builder()
                    .url(resolveUrl(baseUrl, "torrents"))
                    .post("""{"action":"list"}""".toRequestBody(jsonMediaType))
                    .applyBasicAuth(credentials)
                    .build()
            },
        )

        response.use {
            // /torrents is protected by the same Basic auth as playback/API.
            // Validate that a reverse proxy/login page did not return HTTP 200
            // with unrelated HTML.
            val payload = it.body?.string().orEmpty().trim()
            val looksLikeJson = payload.startsWith("{") || payload.startsWith("[")
            if (!looksLikeJson) {
                throw TorrServeHttpException(
                    statusCode = 200,
                    message = "Сервер ответил, но это не похоже на TorrServe API. Проверьте адрес сервера.",
                )
            }
        }

        val version = fetchVersionBestEffort()
        return TorrServeConnectionInfo(
            serverUrl = requireBaseUrl(),
            version = version,
        )
    }

    private suspend fun torrentRequest(
        request: TorrServeTorrentRequest,
    ): TorrServeTorrentStatus {
        val payload = postJson(
            path = "torrents",
            json = torrentRequestAdapter.toJson(request),
        )
        return parseTorrentStatus(payload)
            ?: throw TorrServeHttpException(
                statusCode = 200,
                message = "TorrServe вернул некорректные данные о раздаче.",
            )
    }

    private fun parseTorrentStatus(payload: String): TorrServeTorrentStatus? {
        val clean = payload.trim()
        if (clean.isBlank()) return null
        return runCatching {
            if (clean.startsWith("[")) {
                torrentStatusListAdapter.fromJson(clean)?.firstOrNull()
            } else {
                torrentStatusAdapter.fromJson(clean)
            }
        }.getOrNull()
    }

    suspend fun postJson(
        path: String,
        json: String,
    ): String {
        val response = executeProtected(
            requestFactory = { baseUrl, credentials ->
                Request.Builder()
                    .url(resolveUrl(baseUrl, path))
                    .post(json.toRequestBody(jsonMediaType))
                    .applyBasicAuth(credentials)
                    .build()
            },
        )
        return response.use { it.body?.string().orEmpty() }
    }

    suspend fun getText(path: String): String {
        val response = executeProtected(
            requestFactory = { baseUrl, credentials ->
                Request.Builder()
                    .url(resolveUrl(baseUrl, path))
                    .get()
                    .applyBasicAuth(credentials)
                    .build()
            },
        )
        return response.use { it.body?.string().orEmpty() }
    }

    fun url(path: String): String =
        resolveUrl(requireBaseUrl(), path)

    /**
     * Headers for Media3/other transports that need to perform the HTTP request
     * themselves (for example /stream). Never persist or log this map.
     */
    fun authorizationHeaders(): Map<String, String> {
        val credentials = credentialsProvider()
        if (credentials.login.isBlank() || credentials.password.isBlank()) return emptyMap()
        return mapOf(
            "Authorization" to Credentials.basic(
                credentials.login,
                credentials.password,
                StandardCharsets.UTF_8,
            )
        )
    }

    private suspend fun fetchVersionBestEffort(): String = try {
        // /echo is the official lightweight status/version endpoint. On servers
        // with global httpauth it accepts the same Authorization header.
        getText("echo").trim()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ""
    }

    private suspend fun executeProtected(
        requestFactory: (baseUrl: String, credentials: ServiceCredentials) -> Request,
    ): Response = withContext(Dispatchers.IO) {
        val baseUrl = requireBaseUrl()
        var credentials = credentialsProvider()

        for (attemptIndex in 0..1) {
            val request = requestFactory(baseUrl, credentials)
            val response = try {
                http.newCall(request).execute()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (timeout: SocketTimeoutException) {
                throw TorrServeConnectionException(
                    "TorrServe не ответил вовремя. Проверьте адрес сервера и сеть.",
                    timeout,
                )
            } catch (error: IOException) {
                throw TorrServeConnectionException(
                    "Не удалось подключиться к TorrServe. Проверьте адрес сервера и доступность устройства.",
                    error,
                )
            }

            when (response.code) {
                in 200..299 -> return@withContext response

                401 -> {
                    response.close()
                    if (attemptIndex == 0) {
                        credentials = credentialsProvider()
                        continue
                    }

                    throw TorrServeAuthorizationException(
                        torrServeAuthorizationMessage(credentials)
                    )
                }

                403 -> {
                    response.close()
                    throw TorrServeAccessDeniedException(
                        "TorrServe запретил этот запрос. Проверьте права пользователя и режим read-only сервера."
                    )
                }

                404 -> {
                    response.close()
                    throw TorrServeHttpException(
                        statusCode = 404,
                        message = "TorrServe доступен, но нужный API не найден. Проверьте адрес и версию TorrServer.",
                    )
                }

                else -> {
                    val code = response.code
                    response.close()
                    if (code >= 500) {
                        throw TorrServeHttpException(
                            statusCode = code,
                            message = "TorrServe временно недоступен: ошибка сервера HTTP $code.",
                        )
                    }
                    throw TorrServeHttpException(
                        statusCode = code,
                        message = "TorrServe вернул ошибку HTTP $code.",
                    )
                }
            }
        }

        throw TorrServeAuthorizationException(
            torrServeAuthorizationMessage(credentials)
        )
    }

    private fun Request.Builder.applyBasicAuth(credentials: ServiceCredentials): Request.Builder {
        if (credentials.login.isBlank() || credentials.password.isBlank()) return this
        return header(
            "Authorization",
            Credentials.basic(
                credentials.login,
                credentials.password,
                StandardCharsets.UTF_8,
            ),
        )
    }

    private fun requireBaseUrl(): String {
        val stored = serverUrlProvider().trim()
        if (stored.isBlank()) {
            throw TorrServeConfigurationException(
                "Укажите адрес TorrServe в настройках, например http://192.168.1.10:8090."
            )
        }

        val normalized = ExternalServiceCredentialsStore.normalizeServerUrl(stored)
        val parsed = normalized.toHttpUrlOrNull()
            ?: throw TorrServeConfigurationException(
                "Некорректный адрес TorrServe. Используйте адрес вида http://192.168.1.10:8090."
            )

        if (parsed.scheme != "http" && parsed.scheme != "https") {
            throw TorrServeConfigurationException(
                "TorrServe поддерживает адреса только http:// или https://."
            )
        }

        return normalized
    }

    private fun resolveUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trimEnd('/') + "/"
        val cleanPath = path.trim().trimStart('/')
        return base + cleanPath
    }
}


private const val TORRENT_METADATA_TIMEOUT_SECONDS = 30L
private const val TORRENT_METADATA_POLL_MS = 500L
private const val TORRENT_STREAM_READY_TIMEOUT_MS = 20_000L
private const val TORRENT_STREAM_READY_POLL_MS = 500L
