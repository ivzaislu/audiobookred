package com.example.data.download

import android.os.Environment
import android.webkit.MimeTypeMap
import com.example.data.local.DownloadFileEntity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal fun relativeDirectory(bookSourceId: String, bookTitle: String): String =
    ROOT_RELATIVE + bookDirectoryName(bookSourceId, bookTitle) + "/"

internal fun bookDirectoryName(bookSourceId: String, bookTitle: String): String =
    safeBookName(bookTitle) + " [" + shortHash(bookSourceId) + "]"

internal fun displayName(file: DownloadFileEntity): String {
    val position = (file.chapterPosition.toLong().coerceAtLeast(0L) + 1L)
        .toString()
        .padStart(4, '0')
    val leaf = safeLeaf(file.filename)
    val identity = fileIdentityToken(file)
    val dot = leaf.lastIndexOf('.')
    val identifiedLeaf = if (dot > 0 && dot < leaf.lastIndex) {
        leaf.substring(0, dot) + " " + identity + leaf.substring(dot)
    } else {
        leaf + " " + identity
    }
    return position + " - " + identifiedLeaf
}

internal fun fileIdentityToken(file: DownloadFileEntity): String =
    "[" + shortHash(
        file.fileId + "|" +
            file.localPath + "|" +
            file.chapterPosition + "|" +
            file.filename
    ).take(8) + "]"

internal fun mimeType(file: DownloadFileEntity): String {
    file.mediaType.trim().takeIf { value -> value.contains('/') }?.let { return it }
    val extension = file.filename.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: "application/octet-stream"
}

internal fun safeBookName(value: String): String =
    truncateUtf8(
        value
            .replace('\\', '_')
            .replace(Regex("[/:*?\"<>|]"), "_")
            .replace(Regex("[\\u0000-\\u001f\\u007f]"), "")
            .trim(' ', '.'),
        MAX_READABLE_NAME_BYTES,
    ).ifBlank { "Audiobook" }

internal fun safeLeaf(value: String): String =
    truncateUtf8(
        value
            .replace('\\', '_')
            .replace('/', '_')
            .replace(Regex("[\\u0000-\\u001f\\u007f]"), "")
            .trim(' ', '.'),
        MAX_READABLE_NAME_BYTES,
    ).ifBlank { "audio.bin" }

internal fun truncateUtf8(value: String, maxBytes: Int): String {
    if (value.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return value
    val result = StringBuilder()
    var index = 0
    var usedBytes = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val chunk = String(Character.toChars(codePoint))
        val chunkBytes = chunk.toByteArray(StandardCharsets.UTF_8).size
        if (usedBytes + chunkBytes > maxBytes) break
        result.append(chunk)
        usedBytes += chunkBytes
        index += Character.charCount(codePoint)
    }
    return result.toString().trimEnd(' ', '.')
}

internal fun shortHash(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        .take(12)

internal const val MAX_READABLE_NAME_BYTES = 180
    internal val ROOT_RELATIVE: String =
        Environment.DIRECTORY_DOWNLOADS + "/AudioBookRed/Audiobooks/"
