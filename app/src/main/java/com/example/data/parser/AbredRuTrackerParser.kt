package com.example.data.parser

import android.content.Context
import com.example.data.model.BookDetailDto
import com.example.data.model.GenreDto
import com.example.data.model.LiveCatalogItemDto
import com.example.data.settings.ExternalServiceCredentialsStore
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal class RuTrackerAuthenticationRequiredException(
    message: String = "RuTracker требует авторизованную браузерную сессию",
) : IOException(message)

/**
 * RuTracker provider reconstructed from STPlayer's RTR contract.
 *
 * STPlayer stores concrete selectors in rtr.json, which is not embedded in the
 * supplied APK. Keep the same search -> topic -> magnet contract while accepting
 * both current and legacy RuTracker markup.
 */
internal class AbredRuTrackerParser(
    context: Context,
    credentialsStore: ExternalServiceCredentialsStore,
) {
    private val session = RuTrackerSessionClient(
        context = context.applicationContext,
        credentialsStore = credentialsStore,
    )
    private val searchCatalogMutex = Mutex()
    private val searchCatalogs = LinkedHashMap<String, RuTrackerSearchCatalogBuffer>(
        RUTRACKER_SEARCH_BUFFER_MAX,
        0.75f,
        true,
    )
    private val forumCatalogMutex = Mutex()
    private val forumCatalogs = LinkedHashMap<Int, RuTrackerForumCatalogBuffer>()
    private val previewSemaphore = Semaphore(RUTRACKER_PREVIEW_CONCURRENCY)
    private val previewCache = LinkedHashMap<String, RuTrackerCardPreview>(
        RUTRACKER_PREVIEW_CACHE_MAX,
        0.75f,
        true,
    )

    suspend fun search(
        query: String,
        page: Int,
        limit: Int = RUTRACKER_SEARCH_PAGE_SIZE,
    ): List<LiveCatalogItemDto> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()

        if (!session.hasConfiguredCredentials()) {
            throw RuTrackerAuthenticationRequiredException(
                "Для поиска RuTracker укажите логин и пароль в настройках."
            )
        }

        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceAtLeast(1)
        val searchKey = clean.lowercase()

        val pageItems = searchCatalogMutex.withLock {
            if (safePage == 1) {
                searchCatalogs.remove(searchKey)
            }
            val buffer = searchCatalogs.getOrPut(searchKey) {
                RuTrackerSearchCatalogBuffer()
            }
            trimSearchBuffers()

            buffer.page(safePage, safeLimit) { sitePage ->
                fetchSearchPage(
                    query = clean,
                    sitePage = sitePage,
                    buffer = buffer,
                )
            }
        }

        // Enrich only the logical page that is actually returned to the UI.
        // A 30-item UI page must not trigger preview requests for all 50 rows
        // from RuTracker's physical search page.
        return enrichCards(pageItems)
    }

    private suspend fun fetchSearchPage(
        query: String,
        sitePage: Int,
        buffer: RuTrackerSearchCatalogBuffer,
    ): RuTrackerSearchPage {
        suspend fun scoped(): RuTrackerSearchPage {
            val url = AbredRuTrackerHtmlParser.searchUrl(query, sitePage)
            return AbredRuTrackerHtmlParser.parseSearchPage(
                rawHtml = session.fetchText(url),
                pageUrl = url,
            )
        }

        suspend fun fallback(): RuTrackerSearchPage {
            val url = AbredRuTrackerHtmlParser.searchFallbackUrl(query, sitePage)
            return AbredRuTrackerHtmlParser.parseSearchPage(
                rawHtml = session.fetchText(url),
                pageUrl = url,
                audiobookOnly = true,
            )
        }

        return when (buffer.mode) {
            RuTrackerSearchMode.SCOPED -> scoped()
            RuTrackerSearchMode.FALLBACK -> fallback()
            null -> {
                // Pick the search contract once on the first physical page and
                // keep it for the whole logical result set. Mixing scoped and
                // all-forums ordering between pages would create gaps/duplicates.
                val scopedPage = scoped()
                if (scopedPage.items.isNotEmpty()) {
                    buffer.mode = RuTrackerSearchMode.SCOPED
                    scopedPage
                } else {
                    buffer.mode = RuTrackerSearchMode.FALLBACK
                    fallback()
                }
            }
        }
    }

    private fun trimSearchBuffers() {
        // searchCatalogs is access-ordered. getOrPut() touches the current key,
        // so the first entry is always the least-recently-used query.
        while (searchCatalogs.size > RUTRACKER_SEARCH_BUFFER_MAX) {
            val eldest = searchCatalogs.entries.iterator()
            if (!eldest.hasNext()) return
            eldest.next()
            eldest.remove()
        }
    }

    suspend fun genres(): List<GenreDto> = AbredRuTrackerHtmlParser.genres()

    fun canBrowseGenre(id: String): Boolean =
        AbredRuTrackerHtmlParser.parseGenreForumId(id) != null

    suspend fun genreBooks(id: String, page: Int, limit: Int): List<LiveCatalogItemDto> {
        val forumId = AbredRuTrackerHtmlParser.parseGenreForumId(id)
            ?: error("Invalid RuTracker genre id: $id")
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceAtLeast(1)
        val startLong = (safePage.toLong() - 1L) * safeLimit.toLong()
        if (startLong > Int.MAX_VALUE.toLong()) return emptyList()
        val start = startLong.toInt()
        val targetSize = (startLong + safeLimit.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        val pageItems = forumCatalogMutex.withLock {
            if (safePage == 1) {
                forumCatalogs.remove(forumId)
            }
            val buffer = forumCatalogs.getOrPut(forumId) {
                RuTrackerForumCatalogBuffer()
            }

            while (buffer.items.size < targetSize && !buffer.terminal) {
                val sitePage = buffer.nextSitePage
                val url = AbredRuTrackerHtmlParser.forumPageUrl(forumId, sitePage)
                val parsedPage = AbredRuTrackerHtmlParser.parseForumPage(
                    rawHtml = session.fetchText(url),
                    pageUrl = url,
                    expectedForumId = forumId,
                )

                parsedPage.items.forEach { item ->
                    if (buffer.seenTopicIds.add(item.externalId)) {
                        buffer.items += item
                    }
                }

                val nextPage = parsedPage.nextPage
                if (nextPage == null || nextPage <= sitePage) {
                    buffer.terminal = true
                } else {
                    buffer.nextSitePage = nextPage
                }
            }

            if (start >= buffer.items.size) {
                emptyList()
            } else {
                buffer.items.drop(start).take(safeLimit).toList()
            }
        }

        return enrichCards(pageItems)
    }

    suspend fun book(bookId: String): BookDetailDto {
        val parsed = parseLiveBookKey(bookId) ?: error("Invalid live book key: $bookId")
        require(parsed.first == RUTRACKER_SOURCE) { "Not a RuTracker key: $bookId" }
        val topicId = parsed.second
        require(topicId.all(Char::isDigit)) { "Invalid RuTracker topic id: $topicId" }
        val url = AbredRuTrackerHtmlParser.topicUrl(topicId)
        val html = session.fetchText(url)
        if (AbredRuTrackerHtmlParser.isAuthenticationPage(html, url)) {
            throw RuTrackerAuthenticationRequiredException()
        }
        return AbredRuTrackerHtmlParser.parseBook(html, url, bookId, topicId)
    }

    private suspend fun enrichCards(
        items: List<LiveCatalogItemDto>,
    ): List<LiveCatalogItemDto> = coroutineScope {
        items.map { item ->
            async {
                previewSemaphore.withPermit {
                    enrichCard(item)
                }
            }
        }.awaitAll()
    }

    private suspend fun enrichCard(item: LiveCatalogItemDto): LiveCatalogItemDto {
        val cached = synchronized(previewCache) { previewCache[item.externalId] }
        val preview = cached ?: runCatching {
            val pageUrl = item.externalUrl.ifBlank {
                AbredRuTrackerHtmlParser.topicUrl(item.externalId)
            }
            AbredRuTrackerHtmlParser.parseCardPreview(
                rawHtml = session.fetchText(
                    pageUrl,
                    callTimeoutMs = RUTRACKER_PREVIEW_TIMEOUT_MS,
                ),
                pageUrl = pageUrl,
            ).also { parsed ->
                // Do not pin a completely empty preview for the whole process.
                // A transient/malformed topic response would otherwise make the
                // card permanently lose its cover and metadata until app restart.
                if (parsed.hasUsefulMetadata()) {
                    synchronized(previewCache) {
                        previewCache[item.externalId] = parsed
                        while (previewCache.size > RUTRACKER_PREVIEW_CACHE_MAX) {
                            val eldest = previewCache.entries.iterator()
                            if (eldest.hasNext()) {
                                eldest.next()
                                eldest.remove()
                            } else {
                                break
                            }
                        }
                    }
                }
            }
        }.getOrElse {
            return item
        }

        return item.copy(
            coverUrl = preview.coverUrl.ifBlank { item.coverUrl },
            durationSeconds = preview.durationSeconds.takeIf { it > 0L } ?: item.durationSeconds,
            narrators = preview.narrators.ifEmpty { item.narrators },
            genres = preview.genres.ifEmpty { item.genres },
        )
    }


}

private fun RuTrackerCardPreview.hasUsefulMetadata(): Boolean =
    coverUrl.isNotBlank() ||
        durationSeconds > 0L ||
        narrators.isNotEmpty() ||
        genres.isNotEmpty()

internal data class RuTrackerCardPreview(
    val coverUrl: String = "",
    val durationSeconds: Long = 0L,
    val narrators: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
)

internal data class RuTrackerAudioForum(val id: Int, val name: String)

internal const val RUTRACKER_SEARCH_PAGE_SIZE = 50
private const val RUTRACKER_SEARCH_BUFFER_MAX = 12
private const val RUTRACKER_PREVIEW_CONCURRENCY = 4
private const val RUTRACKER_PREVIEW_TIMEOUT_MS = 2_500L
private const val RUTRACKER_PREVIEW_CACHE_MAX = 240
internal const val RUTRACKER_FORUM_PAGE_SIZE = 50

internal val RUTRACKER_AUDIO_FORUMS = listOf(
    RuTrackerAudioForum(2388, "Зарубежная фантастика, фэнтези, мистика, ужасы, фанфики"),
    RuTrackerAudioForum(2387, "Российская фантастика, фэнтези, мистика, ужасы, фанфики"),
    RuTrackerAudioForum(661, "Любовно-фантастический роман"),
    RuTrackerAudioForum(2348, "Сборники/разное Фантастика, фэнтези, мистика, ужасы, фанфики"),
    RuTrackerAudioForum(695, "Поэзия"),
    RuTrackerAudioForum(399, "Зарубежная литература"),
    RuTrackerAudioForum(402, "Русская литература"),
    RuTrackerAudioForum(467, "Современные любовные романы"),
    RuTrackerAudioForum(490, "Детская литература"),
    RuTrackerAudioForum(499, "Зарубежные детективы, приключения, триллеры, боевики"),
    RuTrackerAudioForum(2137, "Российские детективы, приключения, триллеры, боевики"),
    RuTrackerAudioForum(2127, "Азиатская подростковая литература, ранобэ, веб-новеллы"),
    RuTrackerAudioForum(2325, "Православие"),
    RuTrackerAudioForum(2342, "Ислам"),
    RuTrackerAudioForum(530, "Другие традиционные религии"),
    RuTrackerAudioForum(2152, "Нетрадиционные религиозно-философские учения"),
    RuTrackerAudioForum(1350, "Книги по медицине"),
    RuTrackerAudioForum(403, "Учебная и научно-популярная литература"),
    RuTrackerAudioForum(1279, "lossless-аудиокниги"),
    RuTrackerAudioForum(716, "Бизнес"),
    RuTrackerAudioForum(2165, "Разное"),
    RuTrackerAudioForum(401, "Некондиционные раздачи"),
)
