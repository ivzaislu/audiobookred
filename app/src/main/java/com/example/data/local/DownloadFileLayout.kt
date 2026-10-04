package com.example.data.local

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Deterministic app-private download layout.
 *
 * This type deliberately owns only path construction/sanitization. It has no Room,
 * WorkManager, lifecycle lock or generation-liveness knowledge; those invariants stay
 * with DownloadStore/generation orchestration.
 *
 * Persisted DownloadFileEntity.localPath values are part of the on-disk compatibility
 * contract, so changes here must preserve existing path strings unless accompanied by
 * an explicit migration.
 */
internal class DownloadFileLayout(
    private val filesDir: File,
) {
    fun finalFile(localPath: String): File = File(filesDir, localPath)

    fun partialFile(localPath: String): File =
        File(finalFile(localPath).absolutePath + PART_SUFFIX)

    fun sourceDirectory(bookSourceId: String): File =
        File(filesDir, "downloads/${stableDownloadSegment(bookSourceId)}")

    fun legacySourceDirectory(bookSourceId: String): File =
        File(filesDir, "downloads/${legacySafeSegment(bookSourceId)}")

    fun manifestDirectory(bookSourceId: String, manifestId: String): File =
        File(sourceDirectory(bookSourceId), stableDownloadSegment(manifestId))

    fun relativePath(
        bookSourceId: String,
        manifestId: String,
        chapterPosition: Int,
        fileId: String,
        filename: String,
    ): String {
        val leaf = safeLeaf(filename)
        return buildString {
            append("downloads/")
            append(stableDownloadSegment(bookSourceId))
            append('/')
            append(stableDownloadSegment(manifestId))
            append('/')
            append(chapterPosition.toString().padStart(4, '0'))
            append('-')
            append(stableDownloadSegment(fileId))
            append('-')
            append(leaf)
        }
    }

    private fun safeLeaf(value: String): String = value
        .replace('\\', '_')
        .replace('/', '_')
        .replace(Regex("[\\u0000-\\u001f\\u007f]"), "")
        .trim(' ', '.')
        .take(180)
        .ifBlank { "audio.bin" }

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}

internal fun stableDownloadSegment(value: String): String {
    val readable = legacySafeSegment(value).take(48)
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        .take(16)
    return "$readable-$hash"
}

internal fun legacySafeSegment(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "item" }
