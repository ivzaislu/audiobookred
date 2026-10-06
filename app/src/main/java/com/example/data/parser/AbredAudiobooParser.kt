package com.example.data.parser

import android.content.Context
import android.util.Base64
import com.example.data.api.ApiClient
import com.example.data.model.AudioSeriesBriefDto
import com.example.data.model.BookCardDto
import com.example.data.model.BookDetailDto
import com.example.data.model.GenreDto
import com.example.data.model.LiveCatalogItemDto
import com.example.data.model.PersonDto
import com.example.data.model.SeriesDetailDto
import com.example.data.model.SeriesEntryDto
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup

internal class AudiobooRateLimitedException(
    val retryAfterSeconds: Long? = null,
) : IOException(
    retryAfterSeconds?.let { "Audioboo временно ограничил запросы. Повторите примерно через $it сек." }
        ?: "Audioboo временно ограничил запросы. Попробуйте немного позже."
)

/** Cloudflare-aware, rate-limit-friendly Audioboo transport used by Abred. */
internal class AbredAudiobooParser(context: Context) {
    private val http = ApiClient.createHttpClient(readTimeoutSeconds = 30L)

    private data class CacheEntry(
        val storedAtMs: Long,
        val detail: BookDetailDto,
        val html: String,
    )

    private data class HtmlCacheEntry(
        val storedAtMs: Long,
        val html: String,
    )

    private data class FetchAttempt(
        val body: String,
        val code: Int,
        val challenged: Boolean,
        val rateLimited: Boolean,
        val retryAfterSeconds: Long?,
    )

    private val cache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > 64
    }

    private val htmlCache = object : LinkedHashMap<String, HtmlCacheEntry>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HtmlCacheEntry>?): Boolean = size > 48
    }

    /**
     * Audioboo is deliberately serialized. Catalog, global search and a detail
     * refresh can be requested concurrently by the UI; sending all of them at
     * once is exactly what tends to trip the site's anti-abuse limiter.
     */
    private val requestMutex = Mutex()
    private var lastRequestFinishedAtMs = 0L
    private var rateLimitedUntilMs = 0L

    init {
        AudiobooCloudflareSession.initialize(context.applicationContext)
        // No startup/preflight request here. A saved clearance is validated only
        // when the user actually asks Audioboo for content, avoiding one needless
        // hit on every process/parser start.
    }

    suspend fun catalog(page: Int): List<LiveCatalogItemDto> =
        logicalCatalog(page.coerceAtLeast(1), AUDIOBOO_LOGICAL_PAGE_SIZE)

    suspend fun search(query: String): List<LiveCatalogItemDto> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val url = "$AUDIOBOO_BASE_URL/"
        val form = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("story", clean)
            .build()
        val html = fetchText(url, form)
        return AbredAudiobooDleParser.parseCatalog(html, url)
    }

    suspend fun genres(): List<GenreDto> {
        val url = "$AUDIOBOO_BASE_URL/"
        return AbredAudiobooDleParser.parseGenres(fetchText(url), url)
    }

    fun canBrowseAuthor(authorId: String): Boolean =
        AbredAudiobooDleParser.parseEntityRef(authorId, "author") != null

    fun canBrowseNarrator(narratorId: String): Boolean =
        AbredAudiobooDleParser.parseEntityRef(narratorId, "narrator") != null

    fun canBrowseGenre(genreId: String): Boolean =
        AbredAudiobooDleParser.parseEntityRef(genreId, "genre") != null

    suspend fun authorBooks(authorId: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val externalId = AbredAudiobooDleParser.parseEntityRef(authorId, "author")
            ?: error("Invalid Audioboo author id: $authorId")
        return collection("author", externalId, page, limit)
    }

    suspend fun narratorBooks(narratorId: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val externalId = AbredAudiobooDleParser.parseEntityRef(narratorId, "narrator")
            ?: error("Invalid Audioboo narrator id: $narratorId")
        return collection("narrator", externalId, page, limit)
    }

    suspend fun genreBooks(genreId: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val externalId = AbredAudiobooDleParser.parseEntityRef(genreId, "genre")
            ?: error("Invalid Audioboo genre id: $genreId")
        return collection("genre", externalId, page, limit)
    }

    suspend fun book(bookId: String): BookDetailDto {
        cached(bookId)?.let { return it.detail }
        val parsed = parseLiveBookKey(bookId) ?: error("Invalid live book key: $bookId")
        require(parsed.first == AUDIOBOO_SOURCE) { "Not an Audioboo key: $bookId" }
        val externalPath = parsed.second
        val url = AbredAudiobooDleParser.bookUrl(externalPath)
        val html = unwrapEngineGoLinks(fetchText(url))
        val detail = AbredAudiobooDleParser.parseBook(html, url, bookId, externalPath)
        synchronized(cache) { cache[bookId] = CacheEntry(monotonicMs(), detail, html) }
        return detail
    }

    fun canLoadSourceSeries(bookId: String, provider: String? = null): Boolean {
        val parsed = parseLiveBookKey(bookId) ?: return false
        val requested = provider?.trim()?.lowercase().orEmpty()
        return parsed.first == AUDIOBOO_SOURCE && (requested.isBlank() || requested == AUDIOBOO_SOURCE)
    }

    suspend fun sourceSeries(
        bookId: String,
        provider: String? = null,
        page: Int = 1,
        limit: Int = AUDIOBOO_LOGICAL_PAGE_SIZE,
    ): SeriesDetailDto {
        require(canLoadSourceSeries(bookId, provider)) { "Audioboo source series is unavailable for $bookId" }
        val detail = book(bookId)
        val membership = detail.audioSeries.firstOrNull { it.provider == AUDIOBOO_SOURCE }
            ?: error("Audioboo book has no source series: $bookId")
        val externalId = membership.externalId.takeIf(String::isNotBlank)
            ?: error("Audioboo book has no source series id: $bookId")
        val seriesName = membership.name.ifBlank { detail.sourceSeriesName.ifBlank { detail.seriesName } }
        val html = synchronized(cache) { cache[bookId]?.html }.orEmpty()
        val embedded = AbredAudiobooDleParser.parseEmbeddedSeries(
            html,
            AbredAudiobooDleParser.bookUrl(parseLiveBookKey(bookId)!!.second),
            seriesName,
            externalId,
        )
        val declaredCount = AbredAudiobooDleParser.parseSeriesCount(html, AUDIOBOO_BASE_URL)

        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, AUDIOBOO_LOGICAL_PAGE_SIZE)
        val useEmbedded = embedded.isNotEmpty() && (declaredCount <= 0 || embedded.size >= declaredCount)

        // DLE does not guarantee chronological order for a cycle. For incomplete
        // #somids blocks we therefore fetch the complete cycle collection first,
        // sort it globally by the inferred book number and only then apply Abred
        // paging. Sorting individual physical pages would still scramble books
        // at page boundaries.
        val orderedSeries = if (useEmbedded) {
            embedded
        } else {
            completeSeriesCollection(externalId, declaredCount)
        }
        val rows = orderedSeries.logicalPageSlice(safePage, safeLimit)
        val cards = rows.map { it.toBookCard(externalId, seriesName) }
        val entries = rows.mapIndexed { index, item ->
            SeriesEntryDto(
                externalWorkId = item.externalId,
                title = item.title,
                authors = item.authors,
                position = item.seriesPosition?.toDouble(),
                available = true,
                book = cards[index],
            )
        }
        val total = declaredCount.coerceAtLeast(orderedSeries.size)
        return SeriesDetailDto(
            id = "source:$AUDIOBOO_SOURCE:$externalId",
            name = seriesName,
            kind = "source_series",
            provider = AUDIOBOO_SOURCE,
            booksCount = total,
            totalCount = total,
            books = cards,
            entries = entries,
        )
    }

    /**
     * The website exposes about 27 cards per physical DLE page. Abred exposes
     * only 12 logical items at a time and reuses the already fetched HTML for
     * subsequent logical pages, instead of jumping to another site page early.
     */
    private suspend fun logicalCatalog(page: Int, limit: Int): List<LiveCatalogItemDto> {
        val window = physicalPageWindow(page, limit, SITE_PAGE_SIZE) ?: return emptyList()
        var sitePage = window.page
        var skip = window.skip
        val result = ArrayList<LiveCatalogItemDto>(limit)

        while (result.size < limit) {
            val url = AbredAudiobooDleParser.catalogPageUrl(sitePage)
            val rows = AbredAudiobooDleParser.parseCatalog(fetchText(url), url)
            if (rows.isEmpty()) break
            val available = rows.drop(skip)
            if (available.isNotEmpty()) result += available.take(limit - result.size)
            if (rows.size < SITE_PAGE_SIZE) break
            sitePage = nextPhysicalPage(sitePage) ?: break
            skip = 0
        }
        return result
    }

    private suspend fun collection(
        kind: String,
        externalId: String,
        page: Int,
        limit: Int,
    ): List<LiveCatalogItemDto> {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, AUDIOBOO_LOGICAL_PAGE_SIZE)
        val window = physicalPageWindow(safePage, safeLimit, SITE_PAGE_SIZE) ?: return emptyList()
        var sitePage = window.page
        var skip = window.skip
        val result = ArrayList<LiveCatalogItemDto>(safeLimit)

        while (result.size < safeLimit) {
            val url = AbredAudiobooDleParser.collectionPageUrl(kind, externalId, sitePage)
            val rows = AbredAudiobooDleParser.parseCatalog(fetchText(url), url)
            if (rows.isEmpty()) break
            val available = rows.drop(skip)
            if (available.isNotEmpty()) result += available.take(safeLimit - result.size)
            if (rows.size < SITE_PAGE_SIZE) break
            sitePage = nextPhysicalPage(sitePage) ?: break
            skip = 0
        }
        return result
    }

    private suspend fun completeSeriesCollection(
        externalId: String,
        declaredCount: Int,
    ): List<LiveCatalogItemDto> {
        val result = ArrayList<LiveCatalogItemDto>(declaredCount.coerceAtLeast(SITE_PAGE_SIZE))
        val seen = linkedSetOf<String>()
        var sitePage = 1

        while (sitePage <= MAX_SERIES_SITE_PAGES) {
            val url = AbredAudiobooDleParser.collectionPageUrl("series", externalId, sitePage)
            val rows = AbredAudiobooDleParser.parseCatalog(fetchText(url), url)
            if (rows.isEmpty()) break

            var added = 0
            for (item in rows) {
                if (seen.add(item.externalId)) {
                    result += item
                    added += 1
                }
            }
            if (rows.size < SITE_PAGE_SIZE || added == 0) break
            if (declaredCount > 0 && result.size >= declaredCount) break
            sitePage += 1
        }

        return AbredAudiobooDleParser.orderSeries(result)
    }

    private fun LiveCatalogItemDto.toBookCard(
        seriesExternalId: String,
        fallbackSeriesName: String,
    ): BookCardDto {
        val effectiveSeriesName = seriesName.ifBlank { fallbackSeriesName }
        return BookCardDto(
            id = key,
            title = title,
            authors = authors.map { PersonDto("", it) },
            narrators = narrators.map { PersonDto("", it) },
            genres = genres.map { GenreDto("", it) },
            coverUrl = coverUrl,
            durationSeconds = durationSeconds,
            sourceCodes = listOf(AUDIOBOO_SOURCE),
            primarySource = AUDIOBOO_SOURCE,
            sourceSeriesName = effectiveSeriesName,
            sourceSeriesPosition = seriesPosition,
            audioSeries = listOf(
                AudioSeriesBriefDto(
                    id = "source:$AUDIOBOO_SOURCE:$seriesExternalId",
                    name = effectiveSeriesName,
                    position = seriesPosition?.toDouble(),
                    provider = AUDIOBOO_SOURCE,
                    externalId = seriesExternalId,
                    sourceName = "Audioboo",
                )
            ),
        )
    }

    private suspend fun fetchText(url: String, form: FormBody? = null): String {
        if (form == null) cachedHtml(url)?.let { return it }

        var challengeCount = 0
        while (true) {
            val attempt = execute(url, form)

            if (attempt.rateLimited) {
                throw AudiobooRateLimitedException(attempt.retryAfterSeconds)
            }

            if (!attempt.challenged) {
                if (attempt.code !in 200..299) throw IOException("Audioboo HTTP ${attempt.code} for $url")
                if (form == null) storeHtml(url, attempt.body)
                return attempt.body
            }

            // One operation may ask for one interactive verification. If the
            // very next retry is still a challenge, fail this provider attempt;
            // a future user action may request a fresh verification again.
            if (challengeCount++ >= MAX_CHALLENGE_RETRIES) {
                throw AudiobooCloudflareRequiredException(
                    url,
                    "Audioboo снова запросил Cloudflare после проверки",
                )
            }

            if (
                !AudiobooCloudflareSession.awaitChallengeForRetry(url)
            ) {
                throw AudiobooCloudflareRequiredException(
                    url,
                    "Проверка Audioboo не завершена. Нажмите «Повторить».",
                )
            }
        }
    }

    private suspend fun execute(url: String, form: FormBody?): FetchAttempt = requestMutex.withLock {
        val now = monotonicMs()
        if (now < rateLimitedUntilMs) {
            val remainingSeconds = ((rateLimitedUntilMs - now + 999L) / 1_000L).coerceAtLeast(1L)
            throw AudiobooRateLimitedException(remainingSeconds)
        }

        val waitMs = MIN_REQUEST_INTERVAL_MS - (now - lastRequestFinishedAtMs)
        if (lastRequestFinishedAtMs > 0L && waitMs > 0L) delay(waitMs)

        try {
            withContext(Dispatchers.IO) {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", AudiobooCloudflareSession.userAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
                    .header("Referer", AUDIOBOO_BASE_URL + "/")
                if (form != null) builder.post(form)

                AudiobooCloudflareSession.cookieHeader()
                    .takeIf(String::isNotBlank)
                    ?.let { builder.header("Cookie", it) }

                http.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val challenged = isActualCloudflareChallenge(body)
                    val rateLimited = !challenged && isRateLimitResponse(response.code, body)
                    val retryAfterSeconds = parseRetryAfterSeconds(response.header("Retry-After"))
                        ?: if (rateLimited) DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS else null

                    if (challenged && AudiobooCloudflareSession.cookieHeader().isNotBlank()) {
                        AudiobooCloudflareSession.clearCookies()
                    }
                    if (rateLimited) {
                        val cooldownSeconds = retryAfterSeconds
                            ?.coerceIn(MIN_RATE_LIMIT_COOLDOWN_SECONDS, MAX_RATE_LIMIT_COOLDOWN_SECONDS)
                            ?: DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS
                        rateLimitedUntilMs = monotonicMs() + cooldownSeconds * 1_000L
                    }

                    FetchAttempt(
                        body = body,
                        code = response.code,
                        challenged = challenged,
                        rateLimited = rateLimited,
                        retryAfterSeconds = retryAfterSeconds,
                    )
                }
            }
        } finally {
            lastRequestFinishedAtMs = monotonicMs()
        }
    }

    /**
     * HTTP 429 by itself is a rate-limit signal, not a request to solve a
     * Cloudflare challenge. WebView is opened only for actual challenge DOM.
     */
    private fun isRateLimitResponse(code: Int, rawHtml: String): Boolean {
        if (code == 429) return true
        if (rawHtml.isBlank()) return false
        val lower = rawHtml.lowercase()
        return RATE_LIMIT_MARKERS.any(lower::contains)
    }

    private fun isActualCloudflareChallenge(rawHtml: String): Boolean {
        if (rawHtml.isBlank()) return false
        val document = Jsoup.parse(rawHtml)
        val title = document.title().lowercase()
        val hasAudiobooContent = document.selectFirst(
            "#dle-content, article.card, body#pmovie, .pmovie__player, .card__title"
        ) != null
        if (hasAudiobooContent && "just a moment" !in title) return false

        val lower = rawHtml.lowercase()
        return "just a moment" in title ||
            ("just a moment" in lower && "cloudflare" in lower) ||
            "cf-chl-widget" in lower ||
            "challenge-form" in lower ||
            "cf-turnstile-response" in lower
    }

    internal fun unwrapEngineGoLinks(rawHtml: String): String =
        ENGINE_GO_REGEX.replace(rawHtml) { match ->
            val original = match.value
            val encoded = match.groupValues.getOrNull(1).orEmpty()
            decodeMediaTarget(encoded) ?: original
        }

    private fun decodeMediaTarget(encodedValue: String): String? {
        val urlDecoded = runCatching {
            URLDecoder.decode(encodedValue.replace("&amp;", "&"), StandardCharsets.UTF_8.name())
        }.getOrDefault(encodedValue)
        val decodedBytes = runCatching { Base64.decode(urlDecoded, Base64.DEFAULT) }
            .recoverCatching { Base64.decode(urlDecoded, Base64.URL_SAFE) }
            .getOrNull()
            ?: return null
        val target = decodedBytes.toString(StandardCharsets.UTF_8).trim()
        return ApiClient.externalHttpUrl(target)
    }

    private fun cached(bookId: String): CacheEntry? = synchronized(cache) {
        val row = cache[bookId] ?: return@synchronized null
        if (monotonicMs() - row.storedAtMs > DETAIL_CACHE_TTL_MS) {
            cache.remove(bookId)
            null
        } else row
    }

    private fun cachedHtml(url: String): String? = synchronized(htmlCache) {
        val row = htmlCache[url] ?: return@synchronized null
        if (monotonicMs() - row.storedAtMs > HTML_CACHE_TTL_MS) {
            htmlCache.remove(url)
            null
        } else row.html
    }

    private fun storeHtml(url: String, html: String) {
        if (html.isBlank()) return
        synchronized(htmlCache) {
            htmlCache[url] = HtmlCacheEntry(monotonicMs(), html)
        }
    }

    private fun parseRetryAfterSeconds(value: String?): Long? =
        value?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        val ENGINE_GO_REGEX = Regex("(?i)/engine/go\\.php\\?url=([A-Za-z0-9%_+\\-/=]+)")
        val RATE_LIMIT_MARKERS = listOf(
            "too many requests",
            "you are being rate limited",
            "rate limit",
            "error 1015",
            "превышен лимит",
            "превысили лимит",
            "слишком много запросов",
        )
        const val MAX_CHALLENGE_RETRIES = 1
        const val AUDIOBOO_LOGICAL_PAGE_SIZE = 12
        const val MIN_REQUEST_INTERVAL_MS = 1_500L
        const val DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS = 10L
        const val MIN_RATE_LIMIT_COOLDOWN_SECONDS = 1L
        const val MAX_RATE_LIMIT_COOLDOWN_SECONDS = 10L
        const val DETAIL_CACHE_TTL_MS = 2 * 60 * 1000L
        const val HTML_CACHE_TTL_MS = 2 * 60 * 1000L
        const val SITE_PAGE_SIZE = 27
        const val MAX_SERIES_SITE_PAGES = 40
    }
}
