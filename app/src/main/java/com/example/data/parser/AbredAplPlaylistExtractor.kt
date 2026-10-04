package com.example.data.parser

import com.example.data.api.ApiClient
import com.example.data.model.ChapterDto
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Track extraction for Abred's Audiopolka provider: CSS -> JS playlist -> audio. */
internal object AbredAplPlaylistExtractor {
    private val chapterObjectRegex = Regex(
        "\\{[^{}]{0,4000}?\\\"fileId\\\"\\s*:\\s*(\\d+)[^{}]{0,4000}?\\}",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val chapterTitleRegex = Regex(
        "\\\"title\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val chapterSrcRegex = Regex(
        "\\\"src\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val chapterDurationRegex = Regex("\\\"duration\\\"\\s*:\\s*(\\d+)")
    private val mp3Regex = Regex(
        "[\\\"']((?:https?:)?(?:\\\\?/){2}[^\\\"'\\s]+?\\.mp3(?:\\?[^\\\"'\\s]*)?)[\\\"']",
        RegexOption.IGNORE_CASE,
    )

    fun extract(
        document: Document,
        rawHtml: String,
        pageUrl: String,
        bookKey: String,
        config: AbredAplConfig,
    ): List<ChapterDto> {
        css(document, pageUrl, bookKey, config).takeIf { it.isNotEmpty() }?.let { return it }
        javascript(rawHtml, pageUrl, bookKey, config).takeIf { it.isNotEmpty() }?.let { return it }
        return audio(document, rawHtml, pageUrl, bookKey, config)
    }

    private fun css(
        document: Document,
        pageUrl: String,
        bookKey: String,
        config: AbredAplConfig,
    ): List<ChapterDto> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<ChapterDto>()
        for (item in document.select(config.playlistItemSelector)) {
            val urlNode = item.selectFirst(config.playlistUrlSelector) ?: item
            val raw = sequenceOf("data-url", "data-file", "src", "href")
                .map { attribute -> urlNode.attr(attribute) }
                .firstOrNull(String::isNotBlank)
                .orEmpty()
            val url = safeMediaUrl(pageUrl, raw)
            if (url.isBlank() || !seen.add(url)) continue
            val title = AplParserSupport.clean(item.selectFirst(config.playlistItemNameSelector)?.text().orEmpty())
                .ifBlank { "Часть ${result.size + 1}" }
            val duration = AplParserSupport.durationSeconds(
                item.selectFirst(config.playlistItemTimeSelector)?.text().orEmpty()
            )
            val externalId = item.attr("data-id").ifBlank { (result.size + 1).toString() }
            result += ChapterDto(
                id = "$bookKey:chapter:$externalId",
                position = result.size,
                title = title,
                durationSeconds = duration,
                streamUrl = url,
            )
        }
        return result
    }

    private fun javascript(
        rawHtml: String,
        pageUrl: String,
        bookKey: String,
        config: AbredAplConfig,
    ): List<ChapterDto> {
        extractBalancedObjectAfter(rawHtml, config.playlistScriptMarker)?.let { json ->
            runCatching {
                val root = JSONObject(json)
                val array = root.optJSONArray(config.playlistKey) ?: JSONArray()
                parseJsonArray(array, pageUrl, bookKey, config)
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        val seen = linkedSetOf<String>()
        val result = mutableListOf<ChapterDto>()
        for (match in chapterObjectRegex.findAll(rawHtml)) {
            val chunk = match.value
            val externalId = match.groupValues[1]
            val srcRaw = chapterSrcRegex.find(chunk)?.groupValues?.getOrNull(1) ?: continue
            val src = safeMediaUrl(pageUrl, decodeJsonString(srcRaw))
            if (src.isBlank() || !seen.add(src)) continue
            val titleRaw = chapterTitleRegex.find(chunk)?.groupValues?.getOrNull(1).orEmpty()
            val title = AplParserSupport.clean(Jsoup.parse(decodeJsonString(titleRaw)).text())
                .ifBlank { "Часть ${result.size + 1}" }
            val duration = chapterDurationRegex.find(chunk)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            result += ChapterDto(
                id = "$bookKey:chapter:$externalId",
                position = result.size,
                title = title,
                durationSeconds = duration,
                streamUrl = src,
            )
        }
        return result
    }

    private fun parseJsonArray(
        array: JSONArray,
        pageUrl: String,
        bookKey: String,
        config: AbredAplConfig,
    ): List<ChapterDto> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<ChapterDto>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val src = safeMediaUrl(pageUrl, item.optString(config.playlistUrlField))
            if (src.isBlank() || !seen.add(src)) continue
            val externalId = item.opt(config.playlistIdField)?.toString()
                ?.takeIf(String::isNotBlank) ?: (index + 1).toString()
            val title = AplParserSupport.clean(Jsoup.parse(item.optString(config.playlistTitleField)).text())
                .ifBlank { "Часть ${result.size + 1}" }
            result += ChapterDto(
                id = "$bookKey:chapter:$externalId",
                position = result.size,
                title = title,
                durationSeconds = item.optLong(config.playlistDurationField, 0L),
                streamUrl = src,
            )
        }
        return result
    }

    private fun audio(
        document: Document,
        rawHtml: String,
        pageUrl: String,
        bookKey: String,
        config: AbredAplConfig,
    ): List<ChapterDto> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<ChapterDto>()
        for (node in document.select(config.audioSelector)) {
            val src = safeMediaUrl(pageUrl, node.attr(config.audioUrlAttribute))
            if (src.isBlank() || !seen.add(src)) continue
            result += ChapterDto(
                id = "$bookKey:chapter:${result.size + 1}",
                position = result.size,
                title = "Часть ${result.size + 1}",
                streamUrl = src,
            )
        }
        if (result.isNotEmpty()) return result
        for (match in mp3Regex.findAll(rawHtml)) {
            val src = safeMediaUrl(pageUrl, match.groupValues[1])
            if (src.isBlank() || !seen.add(src)) continue
            result += ChapterDto(
                id = "$bookKey:chapter:${result.size + 1}",
                position = result.size,
                title = "Часть ${result.size + 1}",
                streamUrl = src,
            )
        }
        return result
    }

    private fun safeMediaUrl(baseUrl: String, raw: String): String =
        ApiClient.externalHttpUrl(AplParserSupport.absoluteUrl(baseUrl, raw)).orEmpty()

    private fun extractBalancedObjectAfter(raw: String, marker: String): String? {
        val markerIndex = raw.indexOf(marker)
        if (markerIndex < 0) return null
        var index = markerIndex + marker.length
        while (index < raw.length && raw[index].isWhitespace()) index++
        if (index >= raw.length || raw[index] != '{') return null
        val start = index
        var depth = 0
        var quoted = false
        var escaped = false
        while (index < raw.length) {
            val char = raw[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else {
                if (char == '"') quoted = true
                else if (char == '{') depth++
                else if (char == '}') {
                    depth--
                    if (depth == 0) return raw.substring(start, index + 1)
                }
            }
            index++
        }
        return null
    }

    private fun decodeJsonString(raw: String): String {
        val output = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char != '\\' || index + 1 >= raw.length) {
                output.append(char)
                index++
                continue
            }
            when (val escaped = raw[index + 1]) {
                '"' -> output.append('"')
                '\\' -> output.append('\\')
                '/' -> output.append('/')
                'b' -> output.append('\b')
                'f' -> output.append('\u000C')
                'n' -> output.append('\n')
                'r' -> output.append('\r')
                't' -> output.append('\t')
                'u' -> {
                    val end = index + 6
                    if (end <= raw.length) {
                        raw.substring(index + 2, end).toIntOrNull(16)?.let { output.append(it.toChar()) }
                        index = end
                        continue
                    } else output.append('u')
                }
                else -> output.append(escaped)
            }
            index += 2
        }
        return output.toString()
    }
}
