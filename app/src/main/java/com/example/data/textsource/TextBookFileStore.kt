package com.example.data.textsource

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Original downloads live beside audio's files/downloads, not in cache or a user-chosen folder. */
internal class TextBookFileStore(private val filesDir: File) {
    fun save(
        sourceCode: String, remoteId: String, title: String, option: RemoteTextDownload,
        bytes: ByteArray, checkpoint: () -> Unit = {},
    ): File {
        require(option.canImport) { "Для библиотеки выберите FB2, EPUB или TXT." }
        require(bytes.isNotEmpty()) { "Источник вернул пустой файл." }
        val directory = File(filesDir, "book/${sha256((sourceCode + "\u0000" + remoteId).toByteArray())}")
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать папку book." }
        // Leave room for the 64-byte hash, separator and extension within a 255-byte filename.
        val destination = File(directory, sha256(bytes) + "-" + option.fileName(title, maxStemBytes = 160))
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
