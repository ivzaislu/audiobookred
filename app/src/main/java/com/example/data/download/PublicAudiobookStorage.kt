package com.example.data.download

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.data.local.DownloadFileEntity
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Publishes completed audiobook chapters into the user-visible Downloads collection.
 *
 * In-progress/resumable bytes stay in app-private storage. A published file is located
 * deterministically by bookSourceId + chapter metadata, so Room does not need to persist
 * MediaStore URIs and older download rows remain compatible.
 */
internal class PublicAudiobookStorage(context: Context) {
    private val appContext = context.applicationContext

    data class PublishedArtifact(
        val uri: Uri,
        val sizeBytes: Long,
    )

    fun canPublish(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED

    fun playbackUri(
        bookSourceId: String,
        bookTitle: String,
        file: DownloadFileEntity,
        expectedBytes: Long?,
    ): Uri? = findArtifacts(bookSourceId, bookTitle, file)
        .firstOrNull { artifact -> expectedBytes == null || artifact.sizeBytes == expectedBytes }
        ?.uri

    fun usableSizeBytes(
        bookSourceId: String,
        bookTitle: String,
        file: DownloadFileEntity,
        expectedBytes: Long?,
    ): Long? = findArtifacts(bookSourceId, bookTitle, file)
        .firstOrNull { artifact -> expectedBytes == null || artifact.sizeBytes == expectedBytes }
        ?.sizeBytes

    fun publish(
        bookSourceId: String,
        bookTitle: String,
        file: DownloadFileEntity,
        source: File,
    ): PublishedArtifact {
        if (!source.isFile) throw IOException("Временный файл загрузки не найден: " + file.filename)
        if (!canPublish()) {
            throw IOException("Нет разрешения на сохранение аудиокниг в папку Download")
        }

        val sourceBytes = source.length().coerceAtLeast(0L)
        findArtifacts(bookSourceId, bookTitle, file)
            .firstOrNull { artifact -> artifact.sizeBytes == sourceBytes }
            ?.let { artifact -> return PublishedArtifact(artifact.uri, artifact.sizeBytes) }

        delete(bookSourceId, bookTitle, file)
        val published = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishScoped(bookSourceId, bookTitle, file, source)
        } else {
            publishLegacy(bookSourceId, bookTitle, file, source)
        }
        if (published.sizeBytes != sourceBytes) {
            runCatching { delete(bookSourceId, bookTitle, file) }
            throw IOException(
                "Файл " + file.filename + " опубликован не полностью: " +
                    published.sizeBytes + " из " + sourceBytes
            )
        }
        return published
    }

    fun delete(
        bookSourceId: String,
        bookTitle: String,
        file: DownloadFileEntity,
    ) {
        val artifacts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scopedArtifacts(bookSourceId, file, includePending = true)
        } else {
            legacyArtifacts(bookSourceId, bookTitle, file)
        }
        artifacts.forEach(::deleteArtifact)
    }

    fun deleteBookArtifacts(
        bookSourceId: String,
        bookTitle: String,
    ) {
        if (!canPublish()) {
            throw IOException("Нет разрешения на доступ к папке Download")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val artifacts = scopedBookArtifacts(bookSourceId)
            val knownDirectories = artifacts
                .mapNotNull { artifact -> artifact.dataPath?.let(::File)?.parentFile }
            artifacts.forEach { artifact -> deleteContentUri(artifact.uri) }
            deleteEmptyScopedBookDirectories(
                bookSourceId = bookSourceId,
                bookTitle = bookTitle,
                knownDirectories = knownDirectories,
            )
        } else {
            deleteLegacyBookDirectories(bookSourceId)
        }
    }

    private fun deleteArtifact(artifact: Artifact) {
        deleteContentUri(artifact.uri)
    }

    private fun deleteContentUri(uri: Uri) {
        if (uri.scheme == "file") {
            val target = uri.path?.let(::File) ?: return
            if (target.exists() && !target.delete()) {
                throw IOException("Не удалось удалить " + target.name + " из Download/AudioBookRed")
            }
            return
        }

        val resolver = appContext.contentResolver
        val deleted = resolver.delete(uri, null, null)
        if (deleted > 0) return

        val stillExists = try {
            resolver.query(
                uri,
                arrayOf(MediaStore.Downloads._ID),
                null,
                null,
                null,
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } catch (error: Exception) {
            throw IOException("Не удалось проверить удаление файла из Download/AudioBookRed", error)
        }
        if (stillExists) {
            throw IOException("Не удалось удалить файл из Download/AudioBookRed")
        }
    }

    private fun findArtifacts(
        bookSourceId: String,
        bookTitle: String,
        file: DownloadFileEntity,
    ): List<Artifact> {
        if (!canPublish()) return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scopedArtifacts(bookSourceId, file, includePending = false)
        } else {
            legacyArtifacts(bookSourceId, bookTitle, file)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun scopedBookArtifacts(bookSourceId: String): List<BookArtifact> {
        val result = mutableListOf<BookArtifact>()
        val columns = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATA,
        )
        val suffix = "[" + shortHash(bookSourceId) + "]/"
        appContext.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            columns,
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(pathIndex).orEmpty()
                if (!relativePath.startsWith(ROOT_RELATIVE) || !relativePath.endsWith(suffix)) continue
                val uri = ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idIndex),
                )
                val dataPath = dataIndex
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString)
                    ?.takeIf(String::isNotBlank)
                result += BookArtifact(uri = uri, dataPath = dataPath)
            }
        }
        return result
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun scopedArtifacts(
        bookSourceId: String,
        file: DownloadFileEntity,
        includePending: Boolean,
    ): List<Artifact> {
        val result = mutableListOf<Artifact>()
        val columns = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING,
        )
        val identity = fileIdentityToken(file)
        val selection = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?"
        val args = arrayOf("%" + identity + "%")
        val suffix = "[" + shortHash(bookSourceId) + "]/"
        appContext.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            columns,
            selection,
            args,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val pendingIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(pathIndex).orEmpty()
                if (!relativePath.startsWith(ROOT_RELATIVE) || !relativePath.endsWith(suffix)) continue
                if (!includePending && cursor.getInt(pendingIndex) != 0) continue
                val uri = ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idIndex),
                )
                val declaredSize = if (cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex)
                resolvedSize(uri, declaredSize)?.let { size ->
                    result += Artifact(uri, size)
                }
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun legacyArtifacts(
        bookSourceId: String,
        bookTitle: String,
        file: DownloadFileEntity,
    ): List<Artifact> {
        val root = publicAudiobooksRoot()
        if (!root.isDirectory) return emptyList()
        val suffix = "[" + shortHash(bookSourceId) + "]"
        val expectedDirectory = File(root, bookDirectoryName(bookSourceId, bookTitle))
        val directories = buildList {
            if (expectedDirectory.isDirectory) add(expectedDirectory)
            root.listFiles()
                ?.asSequence()
                ?.filter(File::isDirectory)
                ?.filter { directory -> directory != expectedDirectory && directory.name.endsWith(suffix) }
                ?.forEach(::add)
        }
        return directories.mapNotNull { directory ->
            File(directory, displayName(file))
                .takeIf(File::isFile)
                ?.let { target -> Artifact(Uri.fromFile(target), target.length().coerceAtLeast(0L)) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishScoped(
        bookSourceId: String,
        bookTitle: String,
        file: DownloadFileEntity,
        source: File,
    ): PublishedArtifact {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName(file))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(file))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectory(bookSourceId, bookTitle))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Не удалось создать файл в Download/AudioBookRed")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: throw IOException("Не удалось открыть файл для записи в Download/AudioBookRed")
            val ready = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(uri, ready, null, null) <= 0) {
                throw IOException("Не удалось завершить публикацию файла " + file.filename)
            }
            val size = resolvedSize(uri, null)
                ?: throw IOException("Не удалось проверить опубликованный файл " + file.filename)
            return PublishedArtifact(uri, size)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun deleteEmptyScopedBookDirectories(
        bookSourceId: String,
        bookTitle: String,
        knownDirectories: List<File>,
    ) {
        val root = publicAudiobooksRoot()
        val suffix = "[" + shortHash(bookSourceId) + "]"
        val expectedDirectory = File(root, bookDirectoryName(bookSourceId, bookTitle))
        val directories = linkedSetOf<File>().apply {
            addAll(knownDirectories)
            add(expectedDirectory)
            root.listFiles()
                ?.asSequence()
                ?.filter(File::isDirectory)
                ?.filter { directory -> directory.name.endsWith(suffix) }
                ?.forEach(::add)
        }

        directories.forEach { directory ->
            if (!directory.isDirectory) return@forEach
            val children = directory.listFiles() ?: return@forEach
            if (children.isNotEmpty()) return@forEach
            // The media rows/files are the authoritative user data. Directory
            // cleanup is best-effort because Android 10 scoped storage can deny
            // direct File operations in shared Download even for app-owned media.
            // Never turn a cosmetic empty-directory residue into a failed book delete.
            runCatching { directory.delete() }
        }
    }

    @Suppress("DEPRECATION")
    private fun deleteLegacyBookDirectories(bookSourceId: String) {
        val root = publicAudiobooksRoot()
        if (!root.isDirectory) return
        val suffix = "[" + shortHash(bookSourceId) + "]"
        root.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.filter { directory -> directory.name.endsWith(suffix) }
            ?.forEach { directory ->
                if (!directory.deleteRecursively() && directory.exists()) {
                    throw IOException("Не удалось удалить " + directory.name + " из Download/AudioBookRed")
                }
            }
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(
        bookSourceId: String,
        bookTitle: String,
        file: DownloadFileEntity,
        source: File,
    ): PublishedArtifact {
        if (!canPublish()) throw IOException("Нет разрешения на запись в Download")
        val directory = File(publicAudiobooksRoot(), bookDirectoryName(bookSourceId, bookTitle))
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Не удалось создать папку " + directory.absolutePath)
        }
        val target = File(directory, displayName(file))
        val temporary = File(directory, "." + target.name + ".part")
        temporary.delete()
        source.copyTo(temporary, overwrite = true)
        if (target.exists() && !target.delete()) {
            temporary.delete()
            throw IOException("Не удалось заменить " + target.name)
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        MediaScannerConnection.scanFile(
            appContext,
            arrayOf(target.absolutePath),
            arrayOf(mimeType(file)),
            null,
        )
        return PublishedArtifact(Uri.fromFile(target), target.length().coerceAtLeast(0L))
    }

    @Suppress("DEPRECATION")
    private fun publicAudiobooksRoot(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AudioBookRed/Audiobooks",
        )

    private fun relativeDirectory(bookSourceId: String, bookTitle: String): String =
        ROOT_RELATIVE + bookDirectoryName(bookSourceId, bookTitle) + "/"

    private fun bookDirectoryName(bookSourceId: String, bookTitle: String): String =
        safeBookName(bookTitle) + " [" + shortHash(bookSourceId) + "]"

    private fun displayName(file: DownloadFileEntity): String {
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

    private fun fileIdentityToken(file: DownloadFileEntity): String =
        "[" + shortHash(
            file.fileId + "|" +
                file.localPath + "|" +
                file.chapterPosition + "|" +
                file.filename
        ).take(8) + "]"

    private fun mimeType(file: DownloadFileEntity): String {
        file.mediaType.trim().takeIf { value -> value.contains('/') }?.let { return it }
        val extension = file.filename.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun resolvedSize(uri: Uri, declaredSize: Long?): Long? {
        declaredSize?.takeIf { it >= 0L }?.let { return it }
        if (uri.scheme == "file") {
            return uri.path?.let(::File)?.takeIf(File::isFile)?.length()?.coerceAtLeast(0L)
        }
        return runCatching {
            appContext.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize.takeIf { it >= 0L }
            }
        }.getOrNull()
    }

    private fun safeBookName(value: String): String =
        truncateUtf8(
            value
                .replace('\\', '_')
                .replace(Regex("[/:*?\"<>|]"), "_")
                .replace(Regex("[\\u0000-\\u001f\\u007f]"), "")
                .trim(' ', '.'),
            MAX_READABLE_NAME_BYTES,
        ).ifBlank { "Audiobook" }

    private fun safeLeaf(value: String): String =
        truncateUtf8(
            value
                .replace('\\', '_')
                .replace('/', '_')
                .replace(Regex("[\\u0000-\\u001f\\u007f]"), "")
                .trim(' ', '.'),
            MAX_READABLE_NAME_BYTES,
        ).ifBlank { "audio.bin" }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
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

    private fun shortHash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            .take(12)

    private data class Artifact(
        val uri: Uri,
        val sizeBytes: Long,
    )

    private data class BookArtifact(
        val uri: Uri,
        val dataPath: String?,
    )

    private companion object {
        const val MAX_READABLE_NAME_BYTES = 180
        val ROOT_RELATIVE: String =
            Environment.DIRECTORY_DOWNLOADS + "/AudioBookRed/Audiobooks/"
    }
}
