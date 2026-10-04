package com.example.data.parser

import android.content.Context
import com.example.data.api.ApiClient
import com.example.data.settings.ExternalServiceCredentialsStore
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request

/**
 * RuTracker HTTP/auth/Cloudflare session boundary.
 *
 * HTML mapping and catalog paging live outside this class. Cookie revision,
 * challenge retry and login serialization stay here as one transport contract.
 */
internal class RuTrackerSessionClient(
    context: Context,
    private val credentialsStore: ExternalServiceCredentialsStore,
) {
    private val appContext = context.applicationContext
    private val http = ApiClient.createHttpClient(30L)
        .newBuilder()
        // OkHttp owns cookie parsing/matching so Domain, Path, expiry and Secure
        // rules apply on every request and redirect instead of flattening all
        // RuTracker cookies into one global header.
        .cookieJar(RuTrackerCloudflareSession.cookieJar(appContext))
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if (!isTrustedRuTrackerUrl(request.url.scheme, request.url.host, request.url.port)) {
                throw IOException("RuTracker попытался перейти на недоверенный адрес")
            }
            chain.proceed(request)
        }
        .build()
    private val authenticationMutex = Mutex()

    init {
        RuTrackerCloudflareSession.initialize(appContext)
    }

    fun hasConfiguredCredentials(): Boolean {
        val credentials = credentialsStore.ruTrackerCredentials()
        return credentials.login.isNotBlank() && credentials.password.isNotBlank()
    }
    suspend fun fetchText(url: String): String {
        var challenges = 0
        var authenticationAttempts = 0
        var rateLimitRetries = 0
        while (true) {
            val cookieRevision = RuTrackerCloudflareSession.cookieRevision()
            val attempt = execute(url)
            if (!attempt.challenged) {
                if (attempt.code == 429) {
                    if (rateLimitRetries++ < RUTRACKER_RATE_LIMIT_RETRIES) {
                        val fallbackBackoff = rateLimitRetries * RUTRACKER_RATE_LIMIT_BACKOFF_MS
                        delay(
                            (attempt.retryAfterMs ?: fallbackBackoff)
                                .coerceIn(
                                    RUTRACKER_RATE_LIMIT_MIN_DELAY_MS,
                                    RUTRACKER_RATE_LIMIT_MAX_DELAY_MS,
                                )
                        )
                        continue
                    }
                    throw IOException(
                        "RuTracker временно ограничил частоту запросов (HTTP 429). Повторите позже."
                    )
                }
                if (attempt.code !in 200..299) {
                    throw IOException("RuTracker HTTP ${attempt.code} for $url")
                }

                if (AbredRuTrackerHtmlParser.isAuthenticationPage(attempt.body, url)) {
                    if (
                        authenticationAttempts++ < 1 &&
                        authenticate(attempt.body, url)
                    ) {
                        continue
                    }
                    throw RuTrackerAuthenticationRequiredException(
                        "RuTracker требует вход. Проверьте логин и пароль в настройках."
                    )
                }

                return attempt.body
            }
            if (challenges >= 1) {
                throw RuTrackerCloudflareRequiredException(
                    url,
                    "RuTracker снова запросил Cloudflare после проверки",
                )
            }
            if (
                !RuTrackerCloudflareSession.clearCookiesForChallengeIfUnchanged(
                    targetUrl = url,
                    expectedRevision = cookieRevision,
                )
            ) {
                // Another request refreshed the shared RuTracker cookies after
                // this HTTP request started. Retry with that newer session
                // instead of letting this stale challenge response erase it.
                continue
            }
            challenges += 1
            if (
                !RuTrackerCloudflareSession.awaitChallengeForRetry(url)
            ) {
                throw RuTrackerCloudflareRequiredException(
                    url,
                    "Проверка RuTracker не завершена. Нажмите «Повторить».",
                )
            }
        }
    }

    private suspend fun authenticate(
        loginHtml: String,
        pageUrl: String,
    ): Boolean = authenticationMutex.withLock {
        val credentials = credentialsStore.ruTrackerCredentials()
        if (credentials.login.isBlank() || credentials.password.isBlank()) return@withLock false

        val loginRequest = AbredRuTrackerHtmlParser.parseLoginRequest(
            rawHtml = loginHtml,
            pageUrl = pageUrl,
            login = credentials.login,
            password = credentials.password,
        ) ?: return@withLock false

        val body = FormBody.Builder().apply {
            loginRequest.fields.forEach { (name, value) -> add(name, value) }
        }.build()

        for (challengeAttempt in 0..1) {
            val request = requestBuilder(
                url = loginRequest.actionUrl,
                referer = pageUrl,
            )
                .post(body)
                .build()
            val attempt = execute(request)

            if (!attempt.challenged) {
                if (attempt.code !in 200..299) return@withLock false
                return@withLock !AbredRuTrackerHtmlParser.isAuthenticationPage(
                    attempt.body,
                    loginRequest.actionUrl,
                )
            }

            if (challengeAttempt >= 1) return@withLock false
            if (
                !RuTrackerCloudflareSession.awaitChallengeForRetry(loginRequest.actionUrl)
            ) return@withLock false
        }

        false
    }

    private suspend fun execute(url: String): FetchAttempt =
        execute(requestBuilder(url, "$RUTRACKER_FORUM_URL/").get().build())

    private fun requestBuilder(
        url: String,
        referer: String,
    ): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", RuTrackerCloudflareSession.userAgent())
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
        .header("Referer", referer)

    private suspend fun execute(request: Request): FetchAttempt = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val responseBody = response.body
            val body = decodeRuTrackerHtml(
                bytes = responseBody?.bytes() ?: ByteArray(0),
                declaredCharset = responseBody?.contentType()?.charset(),
            )
            FetchAttempt(
                body = body,
                code = response.code,
                challenged = isRuTrackerBrowserChallenge(
                    response.code,
                    response.header("cf-mitigated"),
                    body,
                ),
                retryAfterMs = response.header("Retry-After")
                    ?.trim()
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0L }
                    ?.let { seconds ->
                        seconds.coerceAtMost(
                            RUTRACKER_RATE_LIMIT_MAX_DELAY_MS / 1_000L
                        ) * 1_000L
                    },
            )
        }
    }

    private data class FetchAttempt(
        val body: String,
        val code: Int,
        val challenged: Boolean,
        val retryAfterMs: Long? = null,
    )
}

internal fun decodeRuTrackerHtml(
    bytes: ByteArray,
    declaredCharset: Charset?,
): String {
    if (bytes.isEmpty()) return ""
    val charset = declaredCharset ?: run {
        val probeLength = minOf(bytes.size, 4096)
        val asciiProbe = String(bytes, 0, probeLength, StandardCharsets.ISO_8859_1).lowercase()
        if (
            "charset=windows-1251" in asciiProbe ||
            "charset=\"windows-1251\"" in asciiProbe ||
            "charset='windows-1251'" in asciiProbe ||
            "charset=cp1251" in asciiProbe
        ) {
            Charset.forName("windows-1251")
        } else {
            StandardCharsets.UTF_8
        }
    }
    return String(bytes, charset)
}

private const val RUTRACKER_RATE_LIMIT_RETRIES = 2
private const val RUTRACKER_RATE_LIMIT_BACKOFF_MS = 1_000L
private const val RUTRACKER_RATE_LIMIT_MIN_DELAY_MS = 500L
private const val RUTRACKER_RATE_LIMIT_MAX_DELAY_MS = 5_000L
