package com.example.data.repository

import com.example.data.model.*
import com.example.data.source.StandaloneSourceRegistry
import com.example.data.torrserve.TorrServeTorrentFile
import java.security.MessageDigest

internal fun normalizeSearchValue(value: String): String = value
    .lowercase()
    .replace('ё', 'е')
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()

internal fun interleaveAll(groups: List<List<LiveCatalogItemDto>>): List<LiveCatalogItemDto> {
    if (groups.isEmpty()) return emptyList()
    val result = ArrayList<LiveCatalogItemDto>(groups.sumOf { it.size })
    var index = 0
    while (true) {
        var added = false
        for (group in groups) {
            group.getOrNull(index)?.let {
                result += it
                added = true
            }
        }
        if (!added) break
        index++
    }
    return result
}

internal fun localManifestId(bookSourceId: String, book: BookDetailDto): String {
    val payload = buildString {
        append(bookSourceId).append('\n')
        book.chapters.sortedBy { it.position }.forEach { chapter ->
            append(chapter.id).append('|')
            append(chapter.position).append('|')
            append(chapter.durationSeconds).append('|')
            append(chapter.streamUrl).append('\n')
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "abred-local-${digest.take(24)}"
}

internal fun torrServeDownloadFilename(
    index: Int,
    file: TorrServeTorrentFile,
): String {
    val original = file.path
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
        .ifBlank { "audio" }
    return "${(index + 1).toString().padStart(4, '0')} - $original"
}

internal fun torrServeMediaType(file: TorrServeTorrentFile): String = when (
    file.path.substringAfterLast('.', "").lowercase()
) {
    "m4a", "m4b" -> "audio/mp4"
    "aac" -> "audio/aac"
    "ogg", "opus" -> "audio/ogg"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    else -> "audio/mpeg"
}

internal fun downloadFilename(index: Int, chapter: ChapterDto): String {
    val title = chapter.title.trim().ifBlank { "Часть ${index + 1}" }
    return "${(index + 1).toString().padStart(4, '0')} - $title.mp3"
}

internal fun sourceName(source: String): String =
    StandaloneSourceRegistry.displayNameOrNull(source) ?: source

internal fun LiveCatalogItemDto.toBookCard(): BookCardDto = BookCardDto(
    id = key,
    title = title,
    authors = authors.filter(String::isNotBlank).map { PersonDto(id = "", name = it) },
    narrators = narrators.filter(String::isNotBlank).map { PersonDto(id = "", name = it) },
    genres = genres.filter(String::isNotBlank).map { GenreDto(id = "", name = it) },
    coverUrl = coverUrl,
    durationSeconds = durationSeconds,
    sourceMeta = sourceMeta,
    isFavorite = false,
    progressPercent = 0.0,
    sourceCodes = listOf(source),
    primarySource = source,
    sourceSeriesName = seriesName,
    sourceSeriesPosition = seriesPosition,
)
