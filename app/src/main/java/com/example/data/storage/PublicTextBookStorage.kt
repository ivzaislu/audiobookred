package com.example.data.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.FileNotFoundException
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal interface TextBookOriginalPublisher {
    fun canPublish(): Boolean
    fun publish(original: File, displayName: String, mimeType: String, bytes: ByteArray, checkpoint: () -> Unit)
}

/** Publishes downloaded originals beside Audiobooks without coupling reading state to public files. */
internal class PublicTextBookStorage(context: Context) : TextBookOriginalPublisher {
    private val resolver = context.contentResolver
    private val documents = PublicAppDocumentStorage(context)
    private val copies = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun canPublish(): Boolean = documents.canWrite()

    override fun publish(
        original: File,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        checkpoint: () -> Unit,
    ) = publishTracked(original, displayName, mimeType, bytes, checkpoint, forceNew = false)

    /** A fresh destination must exist before deleting a source that might alias an old public copy. */
    fun publishForMove(original: File, displayName: String, mimeType: String, bytes: ByteArray, checkpoint: () -> Unit) =
        publishTracked(original, displayName, mimeType, bytes, checkpoint, forceNew = true)

    private fun key(original: File): String = MessageDigest.getInstance("SHA-256")
        .digest(original.absolutePath.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun publishTracked(
        original: File, displayName: String, mimeType: String, bytes: ByteArray,
        checkpoint: () -> Unit, forceNew: Boolean,
    ) {
        check(canPublish()) { "Разрешите доступ к хранилищу для сохранения книг в Download/AudioBookRed/Books." }
        require(bytes.isNotEmpty()) { "Источник вернул пустой файл." }
        checkpoint()
        val key = key(original)
        val previous = copies.getString(key, null)
        if (!forceNew && previous != null && matches(Uri.parse(previous), bytes, checkpoint)) return
        val published = documents.writeBook(displayName, mimeType, bytes, checkpoint)
        val edit = copies.edit().putString(key, published.uri.toString())
        if (previous != null && previous != published.uri.toString()) {
            edit.putStringSet("retired:$key", copies.getStringSet("retired:$key", emptySet()).orEmpty() + previous)
        }
        if (!edit.commit()) {
            // A retry must not leave a second untracked public copy.
            runCatching {
                if (published.uri.scheme == "file") published.uri.path?.let { File(it).delete() }
                else resolver.delete(published.uri, null, null)
            }
            throw IOException("Не удалось сохранить расположение скачанной книги.")
        }
    }

    fun hasPublicCopies(original: File): Boolean {
        val key = key(original)
        return copies.contains(key) || copies.getStringSet("retired:$key", emptySet()).orEmpty().isNotEmpty()
    }

    /** Delete only URIs recorded when this app published the managed original. */
    fun remove(original: File) {
        val key = key(original)
        val uris = copies.getStringSet("retired:$key", emptySet()).orEmpty() +
            listOfNotNull(copies.getString(key, null))
        uris.forEach { removePublicUri(Uri.parse(it)) }
        check(copies.edit().remove(key).remove("retired:$key").commit()) {
            "Не удалось обновить сведения об удалённых файлах книги."
        }
    }

    @Suppress("DEPRECATION")
    private fun removePublicUri(uri: Uri) {
        if (uri.scheme == "file") {
            val file = File(checkNotNull(uri.path))
            val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "AudioBookRed/Books").canonicalFile
            check(file.canonicalFile.parentFile == root) { "Файл находится вне папки книг приложения." }
            check(!file.exists() || file.delete()) { "Не удалось удалить файл книги из Books." }
        } else {
            require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY)
            // A file moved out of Books by the user is no longer managed here.
            val inBooks = try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
                    it.moveToFirst() && it.getString(0) == Environment.DIRECTORY_DOWNLOADS + "/AudioBookRed/Books/"
                } ?: false
            } catch (_: FileNotFoundException) { false }
            if (!inBooks) return
            val deleted = resolver.delete(uri, null, null)
            val remains = if (deleted > 0) false else try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } ?: false
            } catch (_: FileNotFoundException) { false }
            check(!remains) { "Не удалось удалить файл книги из Books." }
        }
    }

    internal fun matches(uri: Uri, bytes: ByteArray, checkpoint: () -> Unit): Boolean {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(32 * 1024)
                var offset = 0
                while (offset < bytes.size) {
                    checkpoint()
                    val count = input.read(buffer, 0, minOf(buffer.size, bytes.size - offset))
                    if (count <= 0) return false
                    for (index in 0 until count) if (buffer[index] != bytes[offset + index]) return false
                    offset += count
                }
                input.read() == -1
            } ?: false
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    internal companion object {
        const val PREFS_NAME = "abred_public_text_books"
    }
}
