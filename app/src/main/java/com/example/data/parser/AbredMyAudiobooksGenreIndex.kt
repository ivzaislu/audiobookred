package com.example.data.parser

import com.example.data.model.GenreDto
import java.net.URI
import org.jsoup.Jsoup

internal const val MYAUDIOBOOKS_GENRES_URL = "$MYAUDIOBOOKS_BASE_URL/knigi-po-zhanram.html"

/** Parses the dedicated MY-AUDIOBOOKS genre directory instead of the seven-item sidebar preview. */
internal object AbredMyAudiobooksGenreIndexParser {
    private val bookCountSuffix = Regex(
        """\s*\d[\d\s]*\s*(?:книг(?:а|и)?|книг\(и\))\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(rawHtml: String, pageUrl: String): List<GenreDto> {
        val document = Jsoup.parse(rawHtml, pageUrl)
        val result = mutableListOf<GenreDto>()
        val seen = linkedSetOf<String>()

        // Do not depend on a single CSS class: older DLE templates moved the
        // static genre directory around. URL shape is the stable discriminator.
        for (anchor in document.select("body a[href]")) {
            val href = anchor.absUrl("href").ifBlank {
                absoluteUrl(pageUrl, anchor.attr("href"))
            }
            val externalId = rootGenreExternalId(href) ?: continue
            if (!seen.add(externalId.lowercase())) continue

            val ownName = clean(anchor.ownText())
            val fullName = clean(anchor.text()).replace(bookCountSuffix, "").trim()
            val name = ownName.ifBlank { fullName }.replace(bookCountSuffix, "").trim()
            if (name.isBlank()) continue

            result += GenreDto(
                id = "$MYAUDIOBOOKS_SOURCE:genre:$externalId",
                name = name,
            )
        }
        return result
    }

    private fun rootGenreExternalId(url: String): String? = try {
        val uri = URI(url)
        if (!uri.host.equals("my-audiobooks.com", ignoreCase = true)) {
            null
        } else {
            val parts = uri.rawPath.orEmpty().trim('/').split('/').filter(String::isNotBlank)
            parts.singleOrNull()
                ?.takeIf { '.' !in it }
                ?.takeIf { it.lowercase() !in RESERVED_ROOTS }
        }
    } catch (_: Exception) {
        null
    }

    private fun absoluteUrl(base: String, value: String): String = try {
        URI(base).resolve(value.trim()).toString()
    } catch (_: Exception) {
        ""
    }

    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private val RESERVED_ROOTS = setOf(
        "engine",
        "templates",
        "uploads",
        "xfsearch",
        "tags",
        "page",
        "blog",
        "top",
        "top100",
        "news",
        "login",
        "register",
        "rules",
        "contacts",
        "favorites",
    )
}
