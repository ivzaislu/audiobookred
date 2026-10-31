package com.example.data.textsource

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex

/** Original downloads live beside audio's files/downloads, not in cache or a user-chosen folder. */
internal class TextBookFileStore(private val filesDir: File) {
    companion object {
        // Always acquire before TextLibraryRepository's mutation mutex.
        internal val lifecycle = Mutex()
    }

    fun saveImported(displayName: String, bytes: ByteArray, checkpoint: () -> Unit = {}): File {
        val name = displayName.substringAfterLast('/').substringAfterLast('\\')
        val extension = if (name.endsWith(".fb2.zip", true)) "fb2.zip" else
            name.substringAfterLast('.', "txt").lowercase().takeIf { it in setOf("txt", "fb2", "epub", "zip") } ?: "txt"
        val stem = if (name.endsWith(".$extension", true)) name.dropLast(extension.length + 1) else name
        return save("imported", sha256(bytes), stem, RemoteTextDownload(extension, extension, "", extension), bytes, checkpoint)
    }

    /** Includes originals saved before managed deletion was introduced. */
    fun originalsFor(sourceSha256: String): List<File> {
        if (!sourceSha256.matches(Regex("[0-9a-f]{64}"))) return emptyList()
        val root = File(filesDir, "book")
        val canonicalRoot = root.canonicalFile
        return root.listFiles().orEmpty().filter { it.isDirectory }.flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.name.startsWith("$sourceSha256-") &&
                it.canonicalPath.startsWith(canonicalRoot.path + File.separator) }
    }
    internal fun originalFile(
        sourceCode: String, remoteId: String, title: String, option: RemoteTextDownload, bytes: ByteArray,
    ): File {
        val directory = File(filesDir, "book/${sha256((sourceCode + "\u0000" + remoteId).toByteArray())}")
        return File(directory, sha256(bytes) + "-" + option.fileName(title, maxStemBytes = 160))
    }

    fun save(
        sourceCode: String, remoteId: String, title: String, option: RemoteTextDownload,
        bytes: ByteArray, checkpoint: () -> Unit = {},
    ): File {
        require(option.canImport) { "Для библиотеки выберите FB2, EPUB или TXT." }
        require(bytes.isNotEmpty()) { "Источник вернул пустой файл." }
        val destination = originalFile(sourceCode, remoteId, title, option, bytes)
        val directory = checkNotNull(destination.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать папку book." }
        if (destination.exists()) {
            check(destination.readBytes().contentEquals(bytes)) { "Сохранённый файл повреждён. Исходная копия не заменена." }
            return destination
        }
        val partial = File(directory, ".${UUID.randomUUID()}.part")
        try {
            checkpoint()
            partial.outputStream().use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    checkpoint()
                    val size = minOf(32 * 1024, bytes.size - offset)
                    output.write(bytes, offset, size)
                    offset += size
                }
                output.fd.sync()
            }
            checkpoint()
            check(!destination.exists() && partial.renameTo(destination)) { "Не удалось сохранить книгу в папку book." }
            return destination
        } finally { partial.delete() }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
