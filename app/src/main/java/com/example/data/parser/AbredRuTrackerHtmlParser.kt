package com.example.data.parser

import com.example.data.api.ApiClient
import com.example.data.model.BookDetailDto
import com.example.data.model.GenreDto
import com.example.data.model.LiveCatalogItemDto
import com.example.data.model.PersonDto
import com.example.data.model.SourceVariantDto
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Pure RuTracker URL/form/HTML mapping. No network, cookies or coroutine state. */
internal data class RuTrackerLoginRequest(
    val actionUrl: String,
    val fields: List<Pair<String, String>>,
)

internal fun isTrustedRuTrackerUrl(
    scheme: String,
    host: String,
    port: Int,
): Boolean =
    scheme.equals("https", ignoreCase = true) &&
        (
            host.equals("rutracker.org", ignoreCase = true) ||
                host.endsWith(".rutracker.org", ignoreCase = true)
        ) &&
        port == 443

internal object AbredRuTrackerHtmlParser {
    fun parseLoginRequest(
        rawHtml: String,
        pageUrl: String,
        login: String,
        password: String,
    ): RuTrackerLoginRequest? {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val form = document.selectFirst(
            "form[action*=login.php]:has(input[type=password]), form:has(input[type=password])"
        ) ?: return null

        val usernameInput = form.selectFirst(
            "input[name=login_username], input[name=username], input[type=text][name]"
        ) ?: return null
        val passwordInput = form.selectFirst(
            "input[name=login_password], input[name=password], input[type=password][name]"
        ) ?: return null

        val fields = linkedMapOf<String, String>()
        form.select("input[type=hidden][name]").forEach { input ->
            fields[input.attr("name")] = input.attr("value")
        }
        fields[usernameInput.attr("name")] = login
        fields[passwordInput.attr("name")] = password

        form.selectFirst("input[type=submit][name], button[type=submit][name]")?.let { submit ->
            val name = submit.attr("name").trim()
            if (name.isNotBlank()) {
                fields[name] = firstNonBlank(submit.attr("value"), clean(submit.text()))
            }
        }

        val rawAction = form.attr("action").trim().ifBlank { "login.php" }
        val actionUri = runCatching { URI(pageUrl).resolve(rawAction) }
            .getOrNull()
            ?: return null
        if (
            !isTrustedRuTrackerUrl(
                scheme = actionUri.scheme.orEmpty(),
                host = actionUri.host.orEmpty(),
                port = actionUri.port.takeIf { it >= 0 } ?: 443,
            )
        ) {
            return null
        }
        val actionUrl = actionUri.toString().takeIf(String::isNotBlank) ?: return null

        return RuTrackerLoginRequest(
            actionUrl = actionUrl,
            fields = fields.entries.map { (name, value) -> name to value },
        )
    }

    fun genres(): List<GenreDto> = RUTRACKER_AUDIO_FORUMS.map { forum ->
        GenreDto(
            id = "$RUTRACKER_SOURCE:genre:${forum.id}",
            name = forum.name,
        )
    }

    fun parseGenreForumId(value: String): Int? {
        val parts = value.trim().split(':', limit = 3)
        if (parts.size != 3 || parts[0] != RUTRACKER_SOURCE || parts[1] != "genre") return null
        val id = parts[2].toIntOrNull() ?: return null
        return id.takeIf { candidate -> RUTRACKER_AUDIO_FORUMS.any { it.id == candidate } }
    }

    fun forumPageUrl(forumId: Int, page: Int): String {
        require(RUTRACKER_AUDIO_FORUMS.any { it.id == forumId }) { "Unknown RuTracker forum: $forumId" }
        val safePage = page.coerceAtLeast(1)
        val start = (safePage.toLong() - 1L) * RUTRACKER_FORUM_PAGE_SIZE.toLong()
        require(start <= Int.MAX_VALUE.toLong()) { "RuTracker forum page is too large" }
        return buildString {
            append("$RUTRACKER_FORUM_URL/viewforum.php?f=$forumId")
            if (start > 0L) append("&start=$start")
        }
    }

    fun searchUrl(query: String, page: Int): String {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val start = (page.coerceAtLeast(1) - 1) * RUTRACKER_SEARCH_PAGE_SIZE
        val forums = RUTRACKER_AUDIO_FORUMS.joinToString(separator = "") { forum ->
            "&f%5B%5D=${forum.id}"
        }
        return "$RUTRACKER_FORUM_URL/tracker.php?nm=$encoded" +
            "&prev_new=0&prev_oop=1$forums&o=10&s=2&oop=1&start=$start"
    }

    fun searchFallbackUrl(query: String, page: Int): String {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val start = (page.coerceAtLeast(1) - 1) * RUTRACKER_SEARCH_PAGE_SIZE
        return "$RUTRACKER_FORUM_URL/tracker.php?nm=$encoded" +
            "&prev_new=0&prev_oop=1&f%5B%5D=-1&o=10&s=2&oop=1&start=$start"
    }

    fun topicUrl(topicId: String): String {
        require(topicId.isNotBlank() && topicId.all(Char::isDigit))
        return "$RUTRACKER_FORUM_URL/viewtopic.php?t=$topicId"
    }

    fun parseSearch(
        rawHtml: String,
        pageUrl: String,
        audiobookOnly: Boolean = false,
    ): List<LiveCatalogItemDto> =
        parseSearchPage(rawHtml, pageUrl, audiobookOnly).items

    fun parseSearchPage(
        rawHtml: String,
        pageUrl: String,
        audiobookOnly: Boolean = false,
    ): RuTrackerSearchPage {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val items = parseTopicRows(
            root = document,
            audiobookOnly = audiobookOnly,
        )
        val currentStart = Regex("[?&]start=(\\d+)")
            .find(pageUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
        val currentPage = currentStart / RUTRACKER_SEARCH_PAGE_SIZE + 1

        val nextStart = document
            .select("a[href*=tracker.php]")
            .mapNotNull { link ->
                Regex("[?&]start=(\\d+)")
                    .find(link.attr("href"))
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }
            .filter { it > currentStart }
            .minOrNull()

        val rawTopicRows = document.select(
            "tr[data-topic_id], tr[id^=trs-tr-], tr.hl-tr, tr[id^=tor_], " +
                "#tor-tbl tbody tr, table.forumline tr"
        ).count { row ->
            topicLink(row) != null &&
                row.selectFirst("a.dl-stub[href*=dl.php], a[href*='dl.php?t=']") != null
        }

        val nextPage = when {
            nextStart != null ->
                nextStart / RUTRACKER_SEARCH_PAGE_SIZE + 1
            rawTopicRows >= RUTRACKER_SEARCH_PAGE_SIZE ->
                currentPage + 1
            else -> null
        }

        return RuTrackerSearchPage(
            items = items,
            nextPage = nextPage,
        )
    }

    fun parseForum(rawHtml: String, pageUrl: String): List<LiveCatalogItemDto> =
        parseForumPage(rawHtml, pageUrl).items

    fun parseForumPage(
        rawHtml: String,
        pageUrl: String,
        expectedForumId: Int? = null,
    ): RuTrackerForumPage {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val actualForumId = forumPageId(document, rawHtml)
        if (expectedForumId != null && actualForumId != expectedForumId) {
            throw IOException(
                "RuTracker вернул раздел ${actualForumId ?: "неизвестный"} вместо $expectedForumId"
            )
        }

        val topicTable = document.selectFirst(
            "table.vf-table.vf-tor, table.vf-table"
        ) ?: return RuTrackerForumPage(
            items = emptyList(),
            nextPage = null,
            forumId = actualForumId,
        )
        val items = parseTopicRows(
            root = topicTable,
            forumPage = true,
        )
        val currentStart = Regex("[?&]start=(\\d+)")
            .find(pageUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
        val currentPage = currentStart / RUTRACKER_FORUM_PAGE_SIZE + 1

        val nextStart = document
            .select("a[href*=viewforum.php]")
            .mapNotNull { link ->
                Regex("[?&]start=(\\d+)")
                    .find(link.attr("href"))
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }
            .filter { it > currentStart }
            .minOrNull()

        val rawTopicRows = topicTable.select(
            "tr[data-topic_id], tr[id^=tr-], tr.hl-tr"
        ).count { row -> topicLink(row) != null }

        val nextPage = when {
            nextStart != null ->
                nextStart / RUTRACKER_FORUM_PAGE_SIZE + 1
            rawTopicRows >= RUTRACKER_FORUM_PAGE_SIZE ->
                currentPage + 1
            else -> null
        }

        return RuTrackerForumPage(
            items = items,
            nextPage = nextPage,
            forumId = actualForumId,
        )
    }

    private fun parseTopicRows(
        root: Element,
        audiobookOnly: Boolean = false,
        forumPage: Boolean = false,
    ): List<LiveCatalogItemDto> {
        val rows = if (forumPage) {
            root.select("tr[data-topic_id], tr[id^=tr-], tr.hl-tr")
        } else {
            root.select(
                "tr[data-topic_id], tr[id^=trs-tr-], tr.hl-tr, tr[id^=tor_], " +
                    "#tor-tbl tbody tr, table.forumline tr"
            )
        }
        val seen = linkedSetOf<String>()
        return rows.mapNotNull { row ->
            if (audiobookOnly) {
                val forumId = forumId(row) ?: return@mapNotNull null
                if (RUTRACKER_AUDIO_FORUMS.none { forum -> forum.id == forumId }) {
                    return@mapNotNull null
                }
            }

            // RuTracker also keeps informational/sticky forum topics in the same
            // table. A real torrent release row exposes dl.php?t=<topicId>.
            if (row.selectFirst("a.dl-stub[href*=dl.php], a[href*='dl.php?t=']") == null) {
                return@mapNotNull null
            }

            val link = topicLink(row) ?: return@mapNotNull null
            val id = topicId(row, link) ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val subject = clean(link.text()).takeIf(String::isNotBlank) ?: return@mapNotNull null
            val parsed = parseSubject(subject)
            val rowStats = searchStats(row)
            val sourceMeta = listOf(parsed.releaseMeta, rowStats)
                .filter(String::isNotBlank)
                .joinToString(" · ")
            LiveCatalogItemDto(
                key = "$RUTRACKER_SOURCE:$id",
                source = RUTRACKER_SOURCE,
                externalId = id,
                externalUrl = topicUrl(id),
                title = parsed.title,
                sourceMeta = sourceMeta,
                authors = parsed.author?.let(::listOf).orEmpty(),
                narrators = parsed.narrators,
            )
        }
    }

    fun parseCardPreview(
        rawHtml: String,
        pageUrl: String,
    ): RuTrackerCardPreview {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val body = firstPostBody(document) ?: document.body()
            ?: return RuTrackerCardPreview()
        val lines = normalizedLines(body)

        return RuTrackerCardPreview(
            coverUrl = cover(body, pageUrl),
            durationSeconds = durationSeconds(
                field(lines, "Время звучания", "Продолжительность", "Время")
            ),
            narrators = split(field(lines, "Исполнитель", "Читает", "Чтец")),
            genres = split(field(lines, "Жанр", "Жанры")),
        )
    }

    fun parseBook(
        rawHtml: String,
        pageUrl: String,
        bookId: String,
        topicId: String,
    ): BookDetailDto {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val body = firstPostBody(document) ?: document.body()
            ?: throw IOException("RuTracker не вернул тело топика")
        val lines = normalizedLines(body)

        val subject = firstNonBlank(
            clean(document.selectFirst("#topic-title")?.text().orEmpty()),
            clean(document.selectFirst("h1.maintitle, .maintitle, .topic-title")?.text().orEmpty()),
            clean(document.title().substringBefore("::").substringBefore("RuTracker")),
            "RuTracker #$topicId",
        )
        val parsedSubject = parseSubject(subject)

        val surname = field(lines, "Фамилия автора")
        val firstName = field(lines, "Имя автора")
        val author = firstNonBlank(
            listOf(surname, firstName).filter(String::isNotBlank).joinToString(" "),
            field(lines, "Автор", "Авторы"),
            parsedSubject.author,
        )
        val narrator = field(lines, "Исполнитель", "Читает", "Чтец")
        val duration = durationSeconds(field(lines, "Время звучания", "Продолжительность", "Время"))
        val genres = split(field(lines, "Жанр", "Жанры")).map { GenreDto("", it) }

        val magnet = document.selectFirst("a[href^=magnet:]")
            ?.attr("href")
            ?.replace("&amp;", "&")
            ?.trim()
            .orEmpty()
        if (!magnet.startsWith("magnet:?", ignoreCase = true)) {
            throw IOException("RuTracker не вернул magnet-ссылку для топика $topicId")
        }

        val sourceId = "live:$RUTRACKER_SOURCE:$topicId"
        return BookDetailDto(
            id = bookId,
            title = parsedSubject.title.ifBlank { subject },
            authors = split(author).map { PersonDto("", it) },
            narrators = split(narrator).map { PersonDto("", it) },
            genres = genres,
            coverUrl = cover(body, pageUrl),
            durationSeconds = duration,
            sourceCodes = listOf(RUTRACKER_SOURCE),
            primarySource = RUTRACKER_SOURCE,
            selectedSource = RUTRACKER_SOURCE,
            selectedBookSourceId = sourceId,
            sourceVariants = listOf(
                SourceVariantDto(
                    bookSourceId = sourceId,
                    sourceCode = RUTRACKER_SOURCE,
                    sourceName = RUTRACKER_SOURCE_NAME,
                    externalId = topicId,
                    externalUrl = pageUrl,
                    durationSeconds = duration,
                    magnetUri = magnet,
                )
            ),
            description = description(lines),
        )
    }

    fun isAuthenticationPage(rawHtml: String, pageUrl: String): Boolean {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val login = document.selectFirst(
            "form[action*=login.php] input[name=login_username], " +
                "form[action*=login.php] input[name=login_password]"
        )
        val content = document.selectFirst("tr[id^=trs-tr-], tr.hl-tr, .post_body, .post-body")
        return login != null && content == null
    }

    private fun forumPageId(document: Document, rawHtml: String): Int? {
        Regex("""\bFORUM_ID\s*:\s*(\d+)""")
            .find(rawHtml)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        val canonical = document.selectFirst(
            "link[rel=canonical][href*=viewforum.php]"
        )?.attr("href").orEmpty()
        Regex("[?&]f=(\\d+)")
            .find(canonical)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        val titleHref = document.selectFirst(
            "h1.maintitle a[href*=viewforum.php]"
        )?.attr("href").orEmpty()
        return Regex("[?&]f=(\\d+)")
            .find(titleHref)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun topicLink(row: Element): Element? {
        // A forum row may start with a "newest unread" icon link pointing to the
        // same viewtopic.php?t=... before the actual title anchor. Never use a
        // generic first viewtopic link here or rows with unread topics disappear
        // because that icon link has no text.
        return row.selectFirst(
            "a.torTopic.tt-text[href*=viewtopic.php], " +
                "a.tt-text[href*=viewtopic.php], " +
                "a.topictitle[href*=viewtopic.php], " +
                "a[id^=tt-][href*=viewtopic.php], " +
                "a.tLink[href*=viewtopic.php]"
        ) ?: row.selectFirst(
            "td.vf-col-t-title a[href*='viewtopic.php?t=']" +
                ":not(.t-is-unread):not([rel=nofollow])"
        )
    }

    private fun forumId(row: Element): Int? {
        val href = row.selectFirst(
            "a[href*='viewforum.php?f='], a[href*='viewforum.php'][href*='f=']"
        )?.attr("href").orEmpty()
        return Regex("[?&]f=(\\d+)")
            .find(href)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun topicId(row: Element, link: Element): String? {
        row.attr("data-topic_id").trim()
            .takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?.let { return it }
        link.attr("data-topic_id").trim()
            .takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?.let { return it }
        return Regex("[?&](?:t|id)=(\\d+)")
            .find(link.attr("href"))?.groupValues?.getOrNull(1)
    }

    private fun searchStats(row: Element): String {
        fun value(selector: String): String {
            val node = row.selectFirst(selector) ?: return ""
            return firstNonBlank(
                node.attr("data-ts_text"),
                node.selectFirst("[data-ts_text]")?.attr("data-ts_text"),
                clean(node.text()),
            )
        }
        return buildList {
            firstNonBlank(
                clean(row.selectFirst("a.dl-stub")?.text().orEmpty()),
                value("td.tor-size, .tor-size"),
            ).takeIf(String::isNotBlank)?.let { add(it) }
            value(".seedmed").takeIf(String::isNotBlank)?.let { add("S: $it") }
            value(".leechmed").takeIf(String::isNotBlank)?.let { add("L: $it") }
        }.joinToString(" · ")
    }

    private fun parseSubject(raw: String): Subject {
        val text = clean(raw)
            .replace(Regex("^(?:\\s*\\[[^\\]]{1,80}]\\s*)+"), "")
            .trim()

        val releaseMatch = RELEASE_SUFFIX.find(text)
        val releaseTokens = releaseMatch
            ?.groupValues
            ?.getOrNull(1)
            ?.split(',')
            ?.map(::clean)
            ?.filter(String::isNotBlank)
            .orEmpty()

        val hasTechnicalReleaseData = releaseTokens.any(::isTechnicalReleaseToken)
        val body = if (hasTechnicalReleaseData && releaseMatch != null) {
            clean(text.removeRange(releaseMatch.range))
        } else {
            text
        }

        val narratorTokens = if (hasTechnicalReleaseData) {
            releaseTokens.takeWhile { !isTechnicalReleaseToken(it) }
        } else {
            emptyList()
        }
        val technicalTokens = if (hasTechnicalReleaseData) {
            releaseTokens.drop(narratorTokens.size)
        } else {
            emptyList()
        }

        val separator = Regex("\\s+[-–—]\\s+").find(body)
        if (separator != null) {
            val author = clean(body.substring(0, separator.range.first))
            val title = clean(body.substring(separator.range.last + 1))
            if (author.isNotBlank() && author.length <= 120 && title.isNotBlank()) {
                return Subject(
                    author = author,
                    title = title,
                    narrators = narratorTokens,
                    releaseMeta = technicalTokens.joinToString(" · "),
                )
            }
        }
        return Subject(
            author = null,
            title = body,
            narrators = narratorTokens,
            releaseMeta = technicalTokens.joinToString(" · "),
        )
    }

    private fun isTechnicalReleaseToken(value: String): Boolean {
        val token = clean(value).lowercase()
        return YEAR_TOKEN.matches(token) ||
            "kbps" in token ||
            token in RELEASE_FORMATS ||
            token.startsWith("mp3 ") ||
            token.startsWith("m4b ") ||
            token.startsWith("aac ") ||
            token.startsWith("ogg ") ||
            token.startsWith("flac ")
    }

    private fun firstPostBody(document: Document): Element? = document.selectFirst(
        ".post_body, .post-body, td.post_body, div.post_body, " +
            "[id^=p-] .post_body, [id^=p-] .post-body"
    )

    private fun normalizedLines(root: Element): List<String> =
        runCatching { root.wholeText() }.getOrDefault(root.text())
            .replace('\u00a0', ' ')
            .replace("\u200b", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map(::clean)
            .filter(String::isNotBlank)

    private fun field(lines: List<String>, vararg labels: String): String {
        for (i in lines.indices) {
            for (label in labels) {
                val key = label.trim().trimEnd(':')
                if (!lines[i].startsWith(key, ignoreCase = true)) continue
                val remainder = lines[i].substring(key.length)
                // "Автор" must not match "Авторы", "Жанр" must not match
                // "Жанры", etc. Accept only a real label boundary.
                if (remainder.firstOrNull()?.isLetterOrDigit() == true) continue
                val sameLine = remainder
                    .trim().trimStart(':', '—', '-', '–').trim()
                if (sameLine.isNotBlank()) return sameLine
                lines.getOrNull(i + 1)?.let { return it }
            }
        }
        return ""
    }

    private fun description(lines: List<String>): String {
        val start = lines.indexOfFirst {
            it.startsWith("Описание", true) || it.startsWith("Аннотация", true)
        }
        if (start < 0) return ""
        val out = mutableListOf<String>()
        lines[start].substringAfter(':', "").trim().takeIf(String::isNotBlank)?.let(out::add)
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (DESCRIPTION_STOPS.any { line.startsWith(it, true) }) break
            out += line
        }
        return out.joinToString("\n").trim()
    }

    private fun cover(root: Element, pageUrl: String): String {
        // RuTracker topics can contain decorative postImg elements before the
        // actual book cover. The real cover is commonly marked as an aligned
        // right-side image (postImgAligned img-right). Prefer those elements
        // before falling back to generic post images.
        val orderedCandidates = buildList {
            addAll(
                root.select(
                    "var.postImg.postImgAligned.img-right[title], " +
                        "var.postImg.postImgAligned.img-right[data-src], " +
                        "img.postImg.postImgAligned.img-right"
                )
            )
            addAll(
                root.select(
                    "var.postImg.postImgAligned[title], " +
                        "var.postImg.postImgAligned[data-src], " +
                        "img.postImg.postImgAligned"
                )
            )
            addAll(root.select("var.postImg[title], var.postImg[data-src]"))
            addAll(root.select("img"))
        }.distinct()

        for (node in orderedCandidates) {
            val raw = if (node.tagName().equals("var", ignoreCase = true)) {
                firstNonBlank(
                    node.attr("title"),
                    node.attr("data-src"),
                )
            } else {
                firstNonBlank(
                    node.attr("data-original"),
                    node.attr("data-lazy-src"),
                    node.attr("src"),
                    node.attr("data-src"),
                )
            }

            val url = resolve(pageUrl, raw)
            if (url.isBlank()) continue
            val lower = url.lowercase()
            if (COVER_REJECT.any(lower::contains)) continue
            ApiClient.coverImageUrl(url)?.let { return it }
        }
        return ""
    }

    private fun resolve(base: String, raw: String): String {
        if (raw.isBlank()) return ""
        val value = raw.trim().replace("&amp;", "&")
        val normalized = if (value.startsWith("//")) "https:$value" else value
        return runCatching { URI(base).resolve(normalized).toString() }.getOrDefault("")
    }

    private fun cleanSeries(value: String): String = clean(value)
        .replace(Regex("\\s*[,;]?\\s*(?:книга|том)\\s*\\d+\\s*$", RegexOption.IGNORE_CASE), "")

    private fun durationSeconds(value: String): Long {
        val text = clean(value).lowercase()
        Regex("(\\d{1,3}):(\\d{2}):(\\d{2})").find(text)?.let {
            return (it.groupValues[1].toLongOrNull() ?: 0) * 3600 +
                (it.groupValues[2].toLongOrNull() ?: 0) * 60 +
                (it.groupValues[3].toLongOrNull() ?: 0)
        }
        val h = Regex("(\\d+)\\s*(?:ч|час|часа|часов)\\b").find(text)
            ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0
        val m = Regex("(\\d+)\\s*(?:мин|минута|минуты|минут)\\b").find(text)
            ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0
        return h * 3600 + m * 60
    }

    private fun split(value: String): List<String> = value.split(',', ';')
        .map(::clean).filter(String::isNotBlank).distinctBy(String::lowercase)

    private fun clean(value: String): String = value
        .replace('\u00a0', ' ').replace("\u200b", "")
        .replace(Regex("\\s+"), " ").trim()

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    private data class Subject(
        val author: String?,
        val title: String,
        val narrators: List<String> = emptyList(),
        val releaseMeta: String = "",
    )

    private val RELEASE_SUFFIX = Regex(
        """\s*\[([^\]]+)]\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val YEAR_TOKEN = Regex("""(?:19|20)\d{2}""")
    private val RELEASE_FORMATS = setOf(
        "mp3",
        "m4b",
        "aac",
        "ogg",
        "flac",
        "alac",
        "wav",
        "opus",
    )

    private val DESCRIPTION_STOPS = listOf(
        "Доп. информация",
        "Дополнительная информация",
        "Содержание",
        "Технические данные",
    )
    private val COVER_REJECT = listOf(
        "smile", "smilie", "avatar", "rank", "icon", "magnet",
        "spacer", "blank", "favicon", "logo",
    )
}

