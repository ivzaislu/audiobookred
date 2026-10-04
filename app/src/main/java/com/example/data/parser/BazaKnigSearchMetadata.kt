package com.example.data.parser

import com.example.data.model.LiveCatalogItemDto
import java.net.URI

/**
 * Zero-request metadata recovery for Baza-Knig's lightweight search endpoint.
 *
 * The lightweight `/search?text=...` markup often contains only an audiobook
 * link whose visible text is `Title - Author`. Rich catalog/collection cards
 * already carry structured metadata. This helper remembers the current cover
 * CDN origin from already-loaded rich cards/details and never opens detail pages
 * solely to enrich search results.
 */
internal object BazaKnigSearchMetadata {
    @Volatile
    private var coverOrigin: String = ""

    fun observe(items: List<LiveCatalogItemDto>): List<LiveCatalogItemDto> {
        items.asSequence()
            .map(LiveCatalogItemDto::coverUrl)
            .firstOrNull(AbredBazaKnigHtmlParser::isAllowedMediaHost)
            ?.let(::observeCoverUrl)
        return items
    }

    fun observeCoverUrl(value: String) {
        if (!AbredBazaKnigHtmlParser.isAllowedMediaHost(value)) return
        val uri = runCatching { URI(value) }.getOrNull() ?: return
        val host = uri.host?.lowercase().orEmpty()
        if (!uri.scheme.equals("https", ignoreCase = true) || host.isBlank()) return
        coverOrigin = "https://$host"
    }

    fun enrich(items: List<LiveCatalogItemDto>): List<LiveCatalogItemDto> {
        observe(items)
        val origin = coverOrigin
        return items.map { item ->
            val authors = if (item.authors.isNotEmpty()) item.authors else inferAuthors(item.title)
            val cover = item.coverUrl.ifBlank {
                coverUrl(origin, item.externalId)
            }
            if (authors == item.authors && cover == item.coverUrl) item
            else item.copy(authors = authors, coverUrl = cover)
        }
    }

    internal fun inferAuthors(title: String): List<String> {
        val separator = title.lastIndexOf(" - ")
        if (separator <= 0 || separator >= title.length - 3) return emptyList()

        val suffix = title.substring(separator + 3).trim()
        if (suffix.length !in 2..120) return emptyList()

        val names = suffix.split(',')
            .map { it.trim() }
            .filter(String::isNotBlank)
        if (names.isEmpty() || names.size > 4) return emptyList()
        if (names.any { !looksLikeAuthorName(it) }) return emptyList()
        return names.distinct()
    }

    internal fun coverPath(externalId: String): String {
        val clean = externalId.trim().trim('/')
        val separator = clean.indexOf('-')
        if (separator <= 0 || separator >= clean.lastIndex) return ""
        val numericId = clean.substring(0, separator)
        val slug = clean.substring(separator + 1)
        if (numericId.isBlank() || !numericId.all(Char::isDigit)) return ""
        if (slug.isBlank() || slug.any { it == '/' || it == '\\' || it == '?' || it == '#' }) return ""
        return "/s01/${numericId.toCharArray().joinToString("/")}/$slug.jpg"
    }

    internal fun coverUrl(origin: String, externalId: String): String {
        val safeOrigin = origin.trim().trimEnd('/')
        val path = coverPath(externalId)
        if (safeOrigin.isBlank() || path.isBlank()) return ""
        return "$safeOrigin$path"
    }

    private fun looksLikeAuthorName(value: String): Boolean {
        val words = value.split(Regex("\\s+"))
            .map { it.trim() }
            .filter(String::isNotBlank)
        if (words.size !in 1..5) return false

        return words.all { word ->
            val firstLetter = word.firstOrNull(Char::isLetter) ?: return@all false
            firstLetter.isUpperCase()
        }
    }
}
