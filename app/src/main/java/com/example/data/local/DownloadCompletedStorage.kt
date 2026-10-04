package com.example.data.local

import android.content.Context
import android.net.Uri
import com.example.data.download.PublicAudiobookStorage

/**
 * Physical bridge for completed downloads.
 *
 * DownloadStore owns Room state/lifecycle orchestration; this component owns the
 * concrete public Downloads implementation boundary. Android 7-9 permission/path
 * compatibility remains encapsulated in PublicAudiobookStorage.
 *
 * No Room state is mutated here. Publishing bytes does not delete the private
 * completed file; DownloadStore does that only after its fenced Room completion
 * update succeeds.
 */
internal data class DownloadCompletedArtifactState(
    val publicStorageAccessible: Boolean,
    val publicBytes: Long?,
    val privateCompletedBytes: Long?,
    val partialBytes: Long,
) {
    val completedBytes: Long? get() = publicBytes ?: privateCompletedBytes
}

internal class DownloadCompletedStorage(
    context: Context,
    private val fileLayout: DownloadFileLayout,
) {
    private val publicStorage = PublicAudiobookStorage(context.applicationContext)

    fun canPublish(): Boolean = publicStorage.canPublish()

    fun inspect(
        bookSourceId: String,
        bookTitle: String,
        file: DownloadFileEntity,
        expectedBytes: Long?,
        includePublic: Boolean = true,
    ): DownloadCompletedArtifactState {
        val publicStorageAccessible = canPublish()
        val publicBytes = if (includePublic && publicStorageAccessible) {
            publicStorage.usableSizeBytes(
                bookSourceId = bookSourceId,
                bookTitle = bookTitle,
                file = file,
                expectedBytes = expectedBytes,
            )
        } else {
            null
        }
        val local = fileLayout.finalFile(file.localPath)
        val localBytes = local.takeIf { it.isFile }
            ?.length()
            ?.coerceAtLeast(0L)
        val privateCompletedBytes = localBytes
            ?.takeIf { expectedBytes == null || it == expectedBytes }
        val partialBytes = fileLayout.partialFile(file.localPath)
            .takeIf { it.isFile }
            ?.length()
            ?.coerceAtLeast(0L)
            ?: 0L
        return DownloadCompletedArtifactState(
            publicStorageAccessible = publicStorageAccessible,
            publicBytes = publicBytes,
            privateCompletedBytes = privateCompletedBytes,
            partialBytes = partialBytes,
        )
    }

    fun publicSizeBytes(
        bookSourceId: String,
        bookTitle: String,
        file: DownloadFileEntity,
        expectedBytes: Long?,
    ): Long? = publicStorage.usableSizeBytes(
        bookSourceId = bookSourceId,
        bookTitle = bookTitle,
        file = file,
        expectedBytes = expectedBytes,
    )

    fun publicPlaybackUri(
        bookSourceId: String,
        bookTitle: String,
        file: DownloadFileEntity,
        expectedBytes: Long?,
    ): Uri? = publicStorage.playbackUri(
        bookSourceId = bookSourceId,
        bookTitle = bookTitle,
        file = file,
        expectedBytes = expectedBytes,
    )

    fun publishPrivateCompletedToPublic(
        bookSourceId: String,
        bookTitle: String,
        file: DownloadFileEntity,
        expectedBytes: Long?,
    ): Long {
        val privateBytes = inspect(
            bookSourceId = bookSourceId,
            bookTitle = bookTitle,
            file = file,
            expectedBytes = expectedBytes,
        ).privateCompletedBytes
            ?: error("Готовый временный файл не найден: " + file.filename)
        val published = publicStorage.publish(
            bookSourceId = bookSourceId,
            bookTitle = bookTitle,
            file = file,
            source = fileLayout.finalFile(file.localPath),
        )
        check(published.sizeBytes == privateBytes) {
            "Опубликованный файл имеет неожиданный размер: ${published.sizeBytes} из $privateBytes"
        }
        return published.sizeBytes
    }

    fun deletePrivateArtifacts(file: DownloadFileEntity) {
        fileLayout.finalFile(file.localPath).delete()
        fileLayout.partialFile(file.localPath).delete()
    }

    fun diskBytes(
        bookSourceId: String,
        bookTitle: String,
        file: DownloadFileEntity,
        expectedBytes: Long?,
    ): Long {
        val publicBytes = publicStorage.usableSizeBytes(
            bookSourceId = bookSourceId,
            bookTitle = bookTitle,
            file = file,
            expectedBytes = expectedBytes,
        ) ?: 0L
        val privateBytes = fileLayout.finalFile(file.localPath)
            .takeIf { it.isFile }
            ?.length()
            ?.coerceAtLeast(0L)
            ?: 0L
        val partialBytes = fileLayout.partialFile(file.localPath)
            .takeIf { it.isFile }
            ?.length()
            ?.coerceAtLeast(0L)
            ?: 0L
        return publicBytes + privateBytes + partialBytes
    }

    fun deletePublicFile(
        bookSourceId: String,
        bookTitle: String,
        file: DownloadFileEntity,
    ) {
        publicStorage.delete(
            bookSourceId = bookSourceId,
            bookTitle = bookTitle,
            file = file,
        )
    }

    fun deletePublicBookArtifacts(
        bookSourceId: String,
        bookTitle: String,
    ) {
        publicStorage.deleteBookArtifacts(
            bookSourceId = bookSourceId,
            bookTitle = bookTitle,
        )
    }
}
