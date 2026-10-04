package com.example.data.parser

import com.example.data.model.BookDetailDto
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import org.jsoup.nodes.Element

internal data class AplNamedLink(
    val externalId: String,
    val name: String,
    val url: String,
)

internal object AplParserSupport {
    private val bookIdRegex = Regex("/(\\d+)/?$")
    private val durationHmsRegex = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})$")

    fun externalId(url: String): String =
        bookIdRegex.find(runCatching { URI(url).path }.getOrNull().orEmpty())?.groupValues?.getOrNull(1).orEmpty()

    fun absoluteUrl(base: String, raw: String): String {
        val value = raw.trim().replace("\\/", "/")
        if (value.isBlank()) return ""
        return runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
    }

    fun clean(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .filter { Character.getType(it) != Character.FORMAT.toInt() }
        return normalized.replace(Regex("\\s+"), " ").trim()
    }

    fun goodLabel(value: String): Boolean {
        val cleaned = clean(value)
        if (cleaned.isBlank() || cleaned.length > 160 || cleaned.all(Char::isDigit)) return false
        return cleaned.lowercase() !in setOf(
            "автор", "авторы", "диктор", "чтец", "исполнитель", "жанр", "цикл",
            "аудиополка", "следующая", "предыдущая", "аудиокнига",
        )
    }

    fun labels(root: Element, hrefParts: List<String>, limit: Int): List<String> =
        dedupe(
            root.select("a[href]")
                .filter { element -> hrefParts.any { it in element.attr("href") } }
                .map(Element::text),
            limit,
        )

    fun configuredLabels(root: Element, selector: String, hrefParts: List<String>, limit: Int): List<String> {
        val configured = dedupe(root.select(selector).map(Element::text), limit)
        return if (configured.isNotEmpty()) configured else labels(root, hrefParts, limit)
    }

    fun configuredNamedLinks(root: Element, selector: String, kind: String, limit: Int): List<AplNamedLink> {
        val configured = namedLinks(root.select(selector), kind, limit)
        if (configured.isNotEmpty()) return configured
        return namedLinks(root.select("a[href]"), kind, limit)
    }

    private fun namedLinks(elements: Iterable<Element>, kind: String, limit: Int): List<AplNamedLink> {
        val result = mutableListOf<AplNamedLink>()
        val seen = linkedSetOf<String>()
        for (element in elements) {
            val href = element.absUrl("href").ifBlank { element.attr("href") }
            val externalId = entityExternalId(href, kind)
            val name = clean(element.text())
            if (externalId.isBlank() || !goodLabel(name) || !seen.add(externalId)) continue
            result += AplNamedLink(externalId, name, href)
            if (result.size >= limit) break
        }
        return result
    }

    fun entityExternalId(url: String, kind: String): String {
        if (kind !in setOf("author", "voice", "genre", "series")) return ""
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        return Regex("/$kind/(\\d+)(?:/.*)?$").find(path)?.groupValues?.getOrNull(1).orEmpty()
    }

    fun entityRef(kind: String, externalId: String): String = "$AUDIOPOLKA_SOURCE:$kind:${externalId.trim()}"

    fun parseEntityRef(value: String, expectedKind: String): String? {
        val parts = value.trim().split(':')
        if (parts.size != 3) return null
        if (parts[0].lowercase() != AUDIOPOLKA_SOURCE || parts[1].lowercase() != expectedKind) return null
        return parts[2].takeIf { it.matches(Regex("\\d+")) }
    }

    fun durationSeconds(value: String): Long {
        val cleaned = clean(value).lowercase()
        durationHmsRegex.find(cleaned)?.let { match ->
            val hours = match.groupValues[1].toLongOrNull() ?: 0L
            val minutes = match.groupValues[2].toLongOrNull() ?: 0L
            val seconds = match.groupValues[3].toLongOrNull() ?: 0L
            return hours * 3600L + minutes * 60L + seconds
        }
        var total = 0L
        Regex("(\\d+)\\s*(?:ч|час)").find(cleaned)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { total += it * 3600L }
        Regex("(\\d+)\\s*(?:мин|м)\\b").find(cleaned)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { total += it * 60L }
        Regex("(\\d+)\\s*(?:сек|с)\\b").find(cleaned)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { total += it }
        return total
    }

    fun inferSeriesPosition(title: String, seriesName: String): Int? {
        if (title.isBlank() || seriesName.isBlank()) return null
        Regex(
            "(?:книга|том|часть|серия)\\s*(?:№|#)?\\s*[-–—.:]?\\s*([0-9]{1,3}|[IVXLCDMХВ]{1,8})(?=\\b|\\s|[.:,;!?)]|$)",
            RegexOption.IGNORE_CASE,
        ).find(title)?.groupValues?.getOrNull(1)?.let { token ->
            token.toIntOrNull()?.takeIf { it in 1..999 }?.let { return it }
            romanToInt(token)?.let { return it }
        }
        Regex("(?:^|\\s|[-–—])([0-9]{1,3})\\s*$")
            .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..999 }?.let { return it }
        return null
    }

    fun stableLiveId(kind: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$AUDIOPOLKA_SOURCE\u0000$kind\u0000$value".toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val suffix = buildString(20) {
            for (index in 0 until 10) {
                val number = digest[index].toInt() and 0xff
                append(hex[number ushr 4])
                append(hex[number and 0x0f])
            }
        }
        return "live-$kind-$suffix"
    }

    fun seriesExternalId(url: String): String = entityExternalId(url, "series")

    private fun dedupe(values: List<String>, limit: Int): List<String> {
        val result = mutableListOf<String>()
        for (raw in values) {
            val value = clean(raw)
            if (goodLabel(value) && value !in result) {
                result += value
                if (result.size >= limit) break
            }
        }
        return result
    }

    private fun romanToInt(value: String): Int? {
        val raw = value.trim().uppercase().replace('Х', 'X').replace('В', 'V')
        if (!Regex("[IVXLCDM]+").matches(raw)) return null
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        var previous = 0
        for (char in raw.reversed()) {
            val current = values[char] ?: return null
            total += if (current < previous) -current else current
            if (current > previous) previous = current
        }
        return total.takeIf { it in 1..999 }
    }
}

internal class PreviewOnlyAudiopolkaBook(
    reason: String,
    val previewBook: BookDetailDto? = null,
) : IOException(reason)
internal class UnavailableAudiopolkaBook(reason: String) : IOException(reason)
internal class MissingAudiopolkaChapters : IOException("audiopolka_missing_full_chapters")
