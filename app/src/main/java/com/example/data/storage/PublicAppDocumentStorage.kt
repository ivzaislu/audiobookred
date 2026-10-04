package com.example.data.storage

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

/**
 * Writes user-visible AudioBookRed documents into Download/AudioBookRed.
 *
 * Android 10+ uses MediaStore.Downloads. Android 7-9 uses the public Download
 * directory and therefore requires WRITE_EXTERNAL_STORAGE.
 */
internal class PublicAppDocumentStorage(context: Context) {
    private val appContext = context.applicationContext

    data class StoredDocument(
        val uri: Uri,
        val displayName: String,
        val relativePath: String,
        val sizeBytes: Long,
    )

    fun canWrite(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED

    fun writeBackup(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): StoredDocument = write(
        directory = BACKUPS_DIRECTORY,
        displayName = displayName,
        mimeType = mimeType,
        bytes = bytes,
    )

    fun writeExport(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): StoredDocument = write(
        directory = EXPORTS_DIRECTORY,
        displayName = displayName,
        mimeType = mimeType,
        bytes = bytes,
    )

    private fun write(
        directory: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): StoredDocument {
        if (!canWrite()) {
            throw IOException("Нет разрешения на запись в папку Download/AudioBookRed")
        }
        require(directory == BACKUPS_DIRECTORY || directory == EXPORTS_DIRECTORY)
        require(displayName.isNotBlank())

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeScoped(directory, displayName, mimeType, bytes)
        } else {
            writeLegacy(directory, displayName, mimeType, bytes)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeScoped(
        directory: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): StoredDocument {
        val resolver = appContext.contentResolver
        val relativeDirectory = rootRelativeDirectory() + directory + "/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Не удалось создать файл в Download/AudioBookRed/" + directory)

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw IOException("Не удалось открыть файл для записи")

            val finalized = resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null,
            )
            if (finalized <= 0) {
                throw IOException("Не удалось завершить публикацию файла")
            }

            val metadata = scopedMetadata(uri, displayName, bytes.size.toLong())
            if (metadata.second != bytes.size.toLong()) {
                throw IOException(
                    "Файл опубликован не полностью: " +
                        metadata.second + " из " + bytes.size + " байт"
                )
            }
            return StoredDocument(
                uri = uri,
                displayName = metadata.first,
                relativePath = relativeDirectory + metadata.first,
                sizeBytes = metadata.second,
            )
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun scopedMetadata(
        uri: Uri,
        fallbackName: String,
        expectedBytes: Long,
    ): Pair<String, Long> {
        var resolvedName = fallbackName
        var queriedSize = -1L
        val columns = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        appContext.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                )?.takeIf(String::isNotBlank)?.let { resolvedName = it }
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                queriedSize = if (cursor.isNull(sizeIndex)) -1L else cursor.getLong(sizeIndex)
            }
        }
        if (queriedSize == expectedBytes) return resolvedName to queriedSize

        val descriptorSize = appContext.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.statSize
        } ?: -1L
        val size = when {
            descriptorSize >= 0L -> descriptorSize
            queriedSize >= 0L -> queriedSize
            else -> throw IOException("Не удалось проверить созданный файл")
        }
        return resolvedName to size
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(
        directoryName: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): StoredDocument {
        val directory = File(legacyRoot(), directoryName)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Не удалось создать папку " + directory.absolutePath)
        }

        val target = uniqueLegacyFile(directory, displayName)
        val temporary = File(directory, "." + target.name + ".part")
        temporary.delete()
        try {
            temporary.outputStream().buffered().use { output ->
                output.write(bytes)
                output.flush()
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            if (!target.isFile || target.length() != bytes.size.toLong()) {
                throw IOException("Не удалось полностью сохранить " + target.name)
            }

            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(target.absolutePath),
                arrayOf(mimeType),
                null,
            )
            return StoredDocument(
                uri = Uri.fromFile(target),
                displayName = target.name,
                relativePath = Environment.DIRECTORY_DOWNLOADS +
                    "/" + ROOT_DIRECTORY + "/" + directoryName + "/" + target.name,
                sizeBytes = target.length(),
            )
        } catch (error: Exception) {
            temporary.delete()
            target.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyRoot(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            ROOT_DIRECTORY,
        )

    private fun uniqueLegacyFile(directory: File, displayName: String): File {
        val safeName = displayName
            .replace('\\', '_')
            .replace('/', '_')
            .ifBlank { "AudioBookRed-export" }
        val candidate = File(directory, safeName)
        if (!candidate.exists()) return candidate

        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val extension = if (dot > 0) safeName.substring(dot) else ""
        var suffix = 2
        while (suffix < 10_000) {
            val next = File(directory, base + " (" + suffix + ")" + extension)
            if (!next.exists()) return next
            suffix += 1
        }
        throw IOException("Не удалось подобрать свободное имя файла")
    }

    private fun rootRelativeDirectory(): String =
        Environment.DIRECTORY_DOWNLOADS + "/" + ROOT_DIRECTORY + "/"

    private companion object {
        const val ROOT_DIRECTORY = "AudioBookRed"
        const val BACKUPS_DIRECTORY = "Backups"
        const val EXPORTS_DIRECTORY = "Exports"
    }
}
