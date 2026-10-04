package com.example.data.local

import com.example.data.api.ApiClient
import com.example.data.model.DownloadManifestDto

/**
 * Builds the next persisted download snapshot without reading or writing Room.
 *
 * The caller owns the per-book lifecycle mutex and supplies one coherent previous
 * Room snapshot. Physical file/public-storage inspection is allowed here, but the
 * resulting plan is persisted atomically by DownloadStore.
 */
internal class DownloadPreparationPlanner(
    private val fileLayout: DownloadFileLayout,
    private val completedStorage: DownloadCompletedStorage,
) {
    fun plan(
        manifest: DownloadManifestDto,
        wifiOnly: Boolean,
        previousBook: DownloadBookEntity?,
        existingFiles: List<DownloadFileEntity>,
        now: Long,
    ): DownloadPreparationPlan {
        val sameManifest = previousBook?.manifestId == manifest.manifestId
        val previousFiles = if (sameManifest) {
            existingFiles.associateBy(DownloadFileEntity::fileId)
        } else {
            emptyMap()
        }
        val staleSameManifestPublicFiles = mutableListOf<DownloadFileEntity>()
        val bookTitleForExistingArtifacts = previousBook?.title ?: manifest.title

        val files = manifest.files.map { item ->
            val previous = previousFiles[item.fileId]
            val expectedBytes = resolveDownloadExpectedBytes(
                manifestBytes = item.sizeBytes,
                persistedBytes = previous?.sizeBytes,
                previousState = previous?.state,
                previousDownloadedBytes = previous?.downloadedBytes ?: 0L,
            )
            val relativePath = previous?.localPath?.takeIf { sameManifest && it.isNotBlank() }
                ?: fileLayout.relativePath(
                    bookSourceId = manifest.bookSourceId,
                    manifestId = manifest.manifestId,
                    chapterPosition = item.chapterPosition,
                    fileId = item.fileId,
                    filename = item.filename,
                )
            val storageIdentityStable = previous != null &&
                previous.chapterPosition == item.chapterPosition &&
                previous.filename == item.filename
            if (sameManifest && previous != null && !storageIdentityStable) {
                staleSameManifestPublicFiles += previous
            }

            val candidate = DownloadFileEntity(
                bookSourceId = manifest.bookSourceId,
                fileId = item.fileId,
                chapterId = item.chapterId,
                chapterPosition = item.chapterPosition,
                title = item.title,
                durationSeconds = item.durationSeconds,
                filename = item.filename,
                mediaType = item.mediaType,
                sizeBytes = expectedBytes,
                downloadUrl = if (manifest.sourceCode.equals("rutracker", ignoreCase = true)) {
                    item.downloadUrl.trim()
                } else {
                    ApiClient.externalHttpUrl(item.downloadUrl).orEmpty()
                },
                localPath = relativePath,
                downloadedBytes = 0L,
                state = "queued",
                error = "",
                updatedAtMs = now,
            )
            val physical = completedStorage.inspect(
                bookSourceId = manifest.bookSourceId,
                bookTitle = bookTitleForExistingArtifacts,
                file = candidate,
                expectedBytes = expectedBytes,
                // Preserve prepare() semantics: only an unchanged persisted file
                // may adopt an already-published artifact from the same manifest.
                includePublic = sameManifest && previous != null && storageIdentityStable,
            )
            candidate.copy(
                downloadedBytes = physical.completedBytes ?: physical.partialBytes,
                state = if (physical.completedBytes != null) "completed" else "queued",
            )
        }

        val completedFiles = files.count { it.state == "completed" }
        val downloadedBytes = files.sumOf { it.downloadedBytes.coerceAtLeast(0L) }
        val complete = files.isNotEmpty() && completedFiles == files.size
        val book = DownloadBookEntity(
            bookSourceId = manifest.bookSourceId,
            bookId = manifest.bookId,
            sourceCode = manifest.sourceCode,
            sourceName = manifest.sourceName,
            title = manifest.title,
            coverUrl = manifest.coverUrl,
            manifestId = manifest.manifestId,
            state = if (complete) "completed" else "queued",
            totalSizeBytes = manifest.totalSizeBytes,
            downloadedBytes = downloadedBytes,
            filesCount = manifest.filesCount.coerceAtLeast(files.size),
            completedFiles = completedFiles,
            wifiOnly = wifiOnly,
            error = "",
            deletedAtMs = null,
            purgeAfterMs = null,
            createdAtMs = previousBook?.createdAtMs ?: now,
            updatedAtMs = now,
        )

        return DownloadPreparationPlan(
            book = book,
            files = files,
            replacedBook = previousBook?.takeUnless { sameManifest },
            staleSameManifestPublicFiles = staleSameManifestPublicFiles,
        )
    }
}

internal data class DownloadPreparationPlan(
    val book: DownloadBookEntity,
    val files: List<DownloadFileEntity>,
    val replacedBook: DownloadBookEntity?,
    val staleSameManifestPublicFiles: List<DownloadFileEntity>,
)
