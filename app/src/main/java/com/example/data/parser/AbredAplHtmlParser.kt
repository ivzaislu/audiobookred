package com.example.data.parser

import com.example.data.model.AudioSeriesBriefDto
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

internal data class AplCollectionMetadata(
    val name: String,
    val totalCount: Int,
)

/** Abred's on-device Audiopolka HTML parser. */
internal object AbredAplHtmlParser {
    fun catalogPageUrl(baseUrl: String, page: Int): String =
        catalogPageUrl(baseUrl, page, AbredAplConfig.defaults())

    internal fun catalogPageUrl(baseUrl: String, page: Int, config: AbredAplConfig): String {
        val base = baseUrl.trimEnd('/')
        val normalized = page.coerceAtLeast(1)
        if (normalized == 1) return "$base/"
        return AplParserSupport.absoluteUrl(
            "$base/",
            config.paginationPattern.replace("{N}", (normalized - 1).toString()),
        )
    }

    fun collectionPageUrl(baseUrl: String, kind: String, externalId: String, page: Int): String {
        require(kind in setOf("author", "voice", "genre", "series")) { "Unsupported Audiopolka collection: $kind" }
        require(externalId.matches(Regex("\\d+"))) { "Invalid Audiopolka $kind id: $externalId" }
        val root = "${baseUrl.trimEnd('/')}/$kind/$externalId/"
        val normalized = page.coerceAtLeast(1)
        return if (normalized == 1) root else "${root}p${normalized - 1}/"
    }

    fun searchUrl(baseUrl: String, query: String): String =
        searchUrl(baseUrl, query, AbredAplConfig.defaults())

    internal fun searchUrl(baseUrl: String, query: String, config: AbredAplConfig): String {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        return AplParserSupport.absoluteUrl(
            baseUrl.trimEnd('/') + "/",
            config.searchUrlTemplate.replace("{query}", encoded),
        )
    }

    fun bookUrl(baseUrl: String, externalId: String): String =
        baseUrl.trimEnd('/') + "/" + externalId.trim('/') + "/"

    fun parseGenres(rawHtml: String, pageUrl: String): List<GenreDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val seen = linkedSetOf<String>()
        val result = mutableListOf<GenreDto>()
        for (anchor in document.select("a[href^='/genre/'], a[href*='audiopolka.club/genre/']")) {
            val href = anchor.absUrl("href").ifBlank {
                AplParserSupport.absoluteUrl(pageUrl, anchor.attr("href"))
            }
            val externalId = AplParserSupport.entityExternalId(href, "genre")
            val name = AplParserSupport.clean(anchor.text())
            if (externalId.isBlank() || !AplParserSupport.goodLabel(name) || !seen.add(externalId)) continue
            result += GenreDto(AplParserSupport.entityRef("genre", externalId), name)
        }
        return result.sortedBy { it.name.lowercase() }
    }

    fun parseCatalog(rawHtml: String, pageUrl: String): List<LiveCatalogItemDto> =
        parseCatalog(rawHtml, pageUrl, AbredAplConfig.defaults())

    internal fun parseCatalog(rawHtml: String, pageUrl: String, config: AbredAplConfig): List<LiveCatalogItemDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        return parseBookRows(document, pageUrl, config, config.listItemSelector, config.listTitleSelector)
    }

    internal fun parseCollectionCatalog(
        rawHtml: String,
        pageUrl: String,
        config: AbredAplConfig,
    ): List<LiveCatalogItemDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        return parseBookRows(document, pageUrl, config, config.collectionItemSelector, config.collectionTitleSelector)
    }

    private fun parseBookRows(
        document: Document,
        pageUrl: String,
        config: AbredAplConfig,
        itemSelector: String,
        titleSelector: String,
    ): List<LiveCatalogItemDto> {
        val roots = document.select(itemSelector)
        val seen = linkedSetOf<String>()
        val result = mutableListOf<LiveCatalogItemDto>()
        for (root in roots) {
            val titleLink = root.selectFirst(titleSelector) ?: root.selectFirst(config.listTitleSelector) ?: continue
            val externalUrl = titleLink.absUrl("href").ifBlank {
                AplParserSupport.absoluteUrl(pageUrl, titleLink.attr("href"))
            }
            val externalId = AplParserSupport.externalId(externalUrl)
            if (externalId.isBlank() || !seen.add(externalId)) continue
            val title = AplParserSupport.clean(titleLink.text())
            if (!AplParserSupport.goodLabel(title)) continue

            val coverNode = root.selectFirst(config.listCoverSelector)
            val coverRaw = coverNode?.attr("data-src")?.takeIf(String::isNotBlank)
                ?: coverNode?.attr("src").orEmpty()
            val authors = AplParserSupport.configuredLabels(
                root, ".book-list-item-author-link", listOf("/author/"), 5,
            )
            val narrators = AplParserSupport.configuredLabels(
                root, ".book-list-item-reader-link", listOf("/voice/", "/reader/"), 8,
            )
            val genres = AplParserSupport.configuredLabels(
                root, ".book-list-item-genre-link", listOf("/genre/"), 5,
            )
            val seriesLink = root.select("a[href*='/series/'],a[href*='/cycle/']")
                .firstOrNull { AplParserSupport.goodLabel(AplParserSupport.clean(it.text())) }
            val seriesName = AplParserSupport.clean(seriesLink?.text().orEmpty())
            val seriesExternalId = seriesLink?.let {
                AplParserSupport.seriesExternalId(it.absUrl("href").ifBlank { it.attr("href") })
            }.orEmpty()

            result += LiveCatalogItemDto(
                key = "$AUDIOPOLKA_SOURCE:$externalId",
                source = AUDIOPOLKA_SOURCE,
                externalId = externalId,
                externalUrl = externalUrl,
                title = title,
                coverUrl = AplParserSupport.absoluteUrl(pageUrl, coverRaw),
                durationSeconds = AplParserSupport.durationSeconds(
                    root.selectFirst(".book-list-item-duration-link")?.text().orEmpty()
                ),
                authors = authors,
                narrators = narrators,
                genres = genres,
                seriesName = seriesName,
                seriesExternalId = seriesExternalId,
                seriesPosition = AplParserSupport.inferSeriesPosition(title, seriesName),
            )
        }
        return result
    }

    fun parseCollectionMetadata(rawHtml: String, pageUrl: String, fallbackName: String = ""): AplCollectionMetadata {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val heading = AplParserSupport.clean(document.selectFirst("h1")?.text().orEmpty())
        val name = heading.takeIf(AplParserSupport::goodLabel) ?: AplParserSupport.clean(fallbackName)
        val text = AplParserSupport.clean(document.text())
        val totalCount = Regex(
            "(?:Автор|Диктор|Жанр|Цикл)?\\s*[•·]?\\s*([0-9][0-9\\s]*)\\s+(?:книга|книги|книг)\\b",
            RegexOption.IGNORE_CASE,
        ).find(text)?.groupValues?.getOrNull(1)?.replace(" ", "")?.toIntOrNull() ?: 0
        return AplCollectionMetadata(name, totalCount)
    }

    fun parseBook(rawHtml: String, externalUrl: String, bookKey: String): BookDetailDto =
        parseBook(rawHtml, externalUrl, bookKey, AbredAplConfig.defaults())

    internal fun parseBook(
        rawHtml: String,
        externalUrl: String,
        bookKey: String,
        config: AbredAplConfig,
    ): BookDetailDto {
        val parsed = parseLiveBookKey(bookKey) ?: error("Invalid live book key: $bookKey")
        require(parsed.first == AUDIOPOLKA_SOURCE) { "Not an Audiopolka key: $bookKey" }
        val document = Jsoup.parse(rawHtml, externalUrl)
        if (isExplicitlyUnavailable(document)) throw UnavailableAudiopolkaBook("audiopolka_rightsholder_removed")

        val root = document.selectFirst(config.detailRootSelector) ?: document
        val title = titleFromPage(document, config)
        val chapters = AbredAplPlaylistExtractor.extract(document, rawHtml, externalUrl, bookKey, config)
        if (chapters.isEmpty()) throw MissingAudiopolkaChapters()

        val authors = AplParserSupport.configuredNamedLinks(
            root, config.detailAuthorSelector, "author", 5,
        ).map { PersonDto(AplParserSupport.entityRef("author", it.externalId), it.name) }
        val narrators = AplParserSupport.configuredNamedLinks(
            root, config.detailPerformerSelector, "voice", 8,
        ).map { PersonDto(AplParserSupport.entityRef("voice", it.externalId), it.name) }
        val genres = AplParserSupport.configuredNamedLinks(
            root, "a[href^='/genre/'], a[href*='/genre/']", "genre", 5,
        ).map { GenreDto(AplParserSupport.entityRef("genre", it.externalId), it.name) }
        val seriesLink = root.selectFirst(config.detailSeriesSelector)
            ?: root.select("a[href*='/series/'],a[href*='/cycle/']")
                .firstOrNull { AplParserSupport.goodLabel(AplParserSupport.clean(it.text())) }
        val seriesName = AplParserSupport.clean(seriesLink?.text().orEmpty())
        val seriesExternalId = seriesLink?.let {
            AplParserSupport.seriesExternalId(it.absUrl("href").ifBlank { it.attr("href") })
        }.orEmpty()
        val seriesPosition = AplParserSupport.inferSeriesPosition(title, seriesName)
        val description = descriptionFromPage(document, config)
        var duration = AplParserSupport.durationSeconds(
            root.selectFirst(config.detailDurationSelector)?.text().orEmpty()
        )
        if (duration <= 0L) duration = chapters.sumOf { it.durationSeconds.coerceAtLeast(0L) }
        val sourceIdentity = "live:$AUDIOPOLKA_SOURCE:${parsed.second}"
        val audioSeries = if (seriesName.isBlank()) emptyList() else listOf(
            AudioSeriesBriefDto(
                id = if (seriesExternalId.isNotBlank()) "source:$AUDIOPOLKA_SOURCE:$seriesExternalId"
                else AplParserSupport.stableLiveId("series", seriesName),
                name = seriesName,
                position = seriesPosition?.toDouble(),
                provider = AUDIOPOLKA_SOURCE,
                externalId = seriesExternalId,
                sourceName = "Audiopolka",
            )
        )

        val detail = BookDetailDto(
            id = bookKey,
            title = title,
            authors = authors,
            narrators = narrators,
            genres = genres,
            coverUrl = coverFromPage(document, externalUrl, config),
            durationSeconds = duration,
            sourceCodes = listOf(AUDIOPOLKA_SOURCE),
            primarySource = AUDIOPOLKA_SOURCE,
            selectedSource = AUDIOPOLKA_SOURCE,
            selectedBookSourceId = sourceIdentity,
            sourceVariants = listOf(
                SourceVariantDto(
                    bookSourceId = sourceIdentity,
                    sourceCode = AUDIOPOLKA_SOURCE,
                    sourceName = "Audiopolka",
                    seriesName = seriesName,
                )
            ),
            description = description,
            seriesName = seriesName,
            seriesPosition = seriesPosition,
            sourceSeriesName = seriesName,
            sourceSeriesPosition = seriesPosition,
            audioSeries = audioSeries,
            chapters = chapters,
        )
        if (isPreviewOnly(document, chapters)) {
            throw PreviewOnlyAudiopolkaBook(
                reason = "audiopolka_preview_only",
                previewBook = detail.copy(
                    chapters = emptyList(),
                ),
            )
        }
        return detail
    }

    private fun titleFromPage(document: Document, config: AbredAplConfig): String {
        AplParserSupport.clean(document.selectFirst(config.detailTitleSelector)?.text().orEmpty())
            .takeIf(AplParserSupport::goodLabel)?.let { return it }
        AplParserSupport.clean(document.selectFirst("meta[property='og:title']")?.attr("content").orEmpty())
            .let(::stripTitleSuffix)
            .takeIf(AplParserSupport::goodLabel)?.let { return it }
        throw IOException("Audiopolka detail title is missing")
    }

    private fun coverFromPage(document: Document, pageUrl: String, config: AbredAplConfig): String {
        document.selectFirst(config.detailCoverSelector)?.let { node ->
            val raw = node.attr("data-src").takeIf(String::isNotBlank) ?: node.attr("src")
            if (raw.isNotBlank()) return AplParserSupport.absoluteUrl(pageUrl, raw)
        }
        return document.selectFirst("meta[property='og:image']")?.attr("content")
            ?.takeIf(String::isNotBlank)?.let { AplParserSupport.absoluteUrl(pageUrl, it) }.orEmpty()
    }

    private fun descriptionFromPage(document: Document, config: AbredAplConfig): String {
        val configured = AplParserSupport.clean(document.selectFirst(config.detailDescriptionSelector)?.text().orEmpty())
        if (configured.isNotBlank()) return configured
        return AplParserSupport.clean(document.selectFirst("meta[property='og:description']")?.attr("content").orEmpty())
    }

    private fun stripTitleSuffix(value: String): String = AplParserSupport.clean(
        value.replace(Regex("\\s*[|—-]\\s*Аудиополка.*$", RegexOption.IGNORE_CASE), "")
    )

    private fun isPreviewOnly(document: Document, chapters: List<com.example.data.model.ChapterDto>): Boolean {
        if (chapters.isEmpty()) return false
        if (chapters.all { isPreviewMediaUrl(it.streamUrl) }) return true
        if (chapters.size != 1) return false
        val hasPurchaseCta = document.select("#book_buy,#buy_wrap button,.book_buy,.book_buy_wrap button").any {
            val text = AplParserSupport.clean(it.text()).lowercase()
            "слушать полностью" in text || "купить" in text
        }
        if (!hasPurchaseCta) return false
        val chapter = chapters.first()
        return AplParserSupport.clean(chapter.title).lowercase() in setOf(
            "начало", "фрагмент", "ознакомительный фрагмент", "демо", "пробный фрагмент",
        ) && chapter.durationSeconds <= 0L
    }

    private fun isPreviewMediaUrl(url: String): Boolean = runCatching {
        val uri = URI(url.replace("\\/", "/"))
        val host = uri.host?.lowercase().orEmpty()
        (host == "litres.ru" || host.endsWith(".litres.ru")) &&
            uri.path.orEmpty().lowercase().contains("/audiotrial")
    }.getOrDefault(false)

    private fun isExplicitlyUnavailable(document: Document): Boolean {
        val text = AplParserSupport.clean(document.text()).lowercase()
        val phrases = listOf(
            "аудиокнига удалена по требованию правообладателя",
            "аудиокнига удалена по требованию правообладателей",
            "аудиозапись удалена по требованию правообладателя",
            "аудиозапись удалена по требованию правообладателей",
            "контент удален по требованию правообладателя",
            "контент удалён по требованию правообладателя",
        )
        return phrases.any(text::contains) ||
            ("правообладател" in text && ("удален" in text || "удалён" in text || "недоступ" in text))
    }
}
