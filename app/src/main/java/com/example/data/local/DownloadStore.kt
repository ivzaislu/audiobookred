package com.example.data.local

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.example.data.model.DownloadManifestDto
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock

/**
 * Durable download state. Resumable bytes stay app-private while completed audio
 * is published into Download/AudioBookRed/Audiobooks.
 *
 * Refactor invariants — keep these boundaries intact when extracting helpers:
 *
 * 1. Per-book lifecycle mutations that couple Room identity with physical files
 *    stay serialized by [lifecycleMutex]: prepare/manifest replacement, synchronous
 *    delete, completed-file publication and obsolete-generation GC.
 * 2. Worker state writes do not need the lifecycle mutex, but every mutation is
 *    fenced by the worker's expected manifest id. A stale worker must never adopt,
 *    update or complete the replacement manifest.
 * 3. Generation GC may delete only a directory that is neither the current Room
 *    manifest nor registered as active. WorkManager REPLACE can overlap workers,
 *    so the active-generation registry remains reference-counted and participates
 *    in selection/deletion atomically.
 * 4. Physical cleanup must not make Room claim that a completed artifact exists
 *    when it does not. Read-time reconciliation may requeue a missing completed
 *    file only after re-checking that the same manifest is still current.
 * 5. Delete/publication failure handling must preserve recoverable authoritative
 *    Room state; best-effort cleanup must not weaken the manifest fencing above.
 */
class DownloadStore(
    context: Context,
    private val cacheStore: LocalCacheStore,
) {
    private val appContext = context.applicationContext
    private val db = AbredDatabase.get(appContext)
    private val dao = db.downloads()
    private val fileLayout = DownloadFileLayout(appContext.filesDir)
    private val completedStorage = DownloadCompletedStorage(
        context = appContext,
        fileLayout = fileLayout,
    )
    private val preparationPlanner = DownloadPreparationPlanner(
        fileLayout = fileLayout,
        completedStorage = completedStorage,
    )
    private val generationStorage = DownloadGenerationStorage(
        dao = dao,
        fileLayout = fileLayout,
    )

    data class Stats(
        val books: Int,
        val bytes: Long
    )

    fun observeBooks(): Flow<List<DownloadBookEntity>> = dao.observeBooks()
    suspend fun books(): List<DownloadBookEntity> = dao.books()
    suspend fun book(bookSourceId: String): DownloadBookEntity? = dao.book(bookSourceId)
    suspend fun files(bookSourceId: String): List<DownloadFileEntity> {
        val book = dao.book(bookSourceId)
        val rawFiles = dao.files(bookSourceId)
        val normalized = rawFiles.map { file ->
            normalizeCompletedFile(file, book?.title.orEmpty())
        }

        if (book != null && book.deletedAtMs == null && book.state != "purged") {
            val missingCompleted = rawFiles.zip(normalized)
                .filter { (persisted, resolved) ->
                    persisted.state == "completed" && resolved.state != "completed"
                }
            if (missingCompleted.isNotEmpty()) {
                val now = System.currentTimeMillis()
                db.withTransaction {
                    val currentBook = dao.book(bookSourceId)
                    if (
                        currentBook != null &&
                        currentBook.deletedAtMs == null &&
                        currentBook.state != "purged" &&
                        currentBook.manifestId == book.manifestId
                    ) {
                        var changed = false
                        for ((persisted, resolved) in missingCompleted) {
                            changed = dao.requeueMissingCompletedFile(
                                bookSourceId = bookSourceId,
                                fileId = persisted.fileId,
                                downloadedBytes = resolved.downloadedBytes.coerceAtLeast(0L),
                                now = now,
                            ) > 0 || changed
                        }
                        if (changed) {
                            recalculateLocked(bookSourceId, currentBook.manifestId, now)
                        }
                    }
                }
            }
        }

        return normalized
    }

    suspend fun stats(): Stats = Stats(
        books = dao.completedBookCount(),
        bytes = dao.downloadedBytes().coerceAtLeast(0L)
    )

    fun canPublishCompletedFiles(): Boolean = completedStorage.canPublish()

    internal fun markDownloadGenerationActive(bookSourceId: String, manifestId: String) {
        generationStorage.markActive(bookSourceId, manifestId)
    }

    internal fun markDownloadGenerationInactive(bookSourceId: String, manifestId: String) {
        generationStorage.markInactive(bookSourceId, manifestId)
    }

    /**
     * Lifecycle-critical GC. The generation component uses the same process-wide
     * per-book mutex as prepare/delete/publication.
     */
    suspend fun cleanupObsoleteManifestDirectories(bookSourceId: String) {
        generationStorage.cleanupObsoleteManifestDirectories(bookSourceId)
    }

    /**
     * Lifecycle-critical manifest replacement. Keep Room replacement and the file
     * identity decisions under the same per-book mutex; old worker cancellation is
     * asynchronous and cannot be used as the correctness boundary.
     */
    suspend fun prepare(manifest: DownloadManifestDto, wifiOnly: Boolean): DownloadBookEntity {
        require(manifest.bookSourceId.isNotBlank())
        require(manifest.manifestId.isNotBlank())

        val book = lifecycleMutex(manifest.bookSourceId).withLock {
            val now = System.currentTimeMillis()
            val previousBook = dao.book(manifest.bookSourceId)
            val existingFiles = dao.files(manifest.bookSourceId)

            // New manifests use a manifest-specific hashed directory. Never delete
            // the previous directory here: WorkManager cancellation is asynchronous
            // and an old worker may still be unwinding a blocking HTTP request.
            fileLayout.manifestDirectory(manifest.bookSourceId, manifest.manifestId).mkdirs()

            val plan = preparationPlanner.plan(
                manifest = manifest,
                wifiOnly = wifiOnly,
                previousBook = previousBook,
                existingFiles = existingFiles,
                now = now,
            )

            db.withTransaction {
                dao.deleteFiles(manifest.bookSourceId)
                if (plan.files.isNotEmpty()) dao.putFiles(plan.files)
                dao.putBook(plan.book)
            }

            plan.replacedBook?.let { replaced ->
                runCatching {
                    completedStorage.deletePublicBookArtifacts(
                        bookSourceId = replaced.bookSourceId,
                        bookTitle = replaced.title,
                    )
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "Failed to remove public artifacts for replaced manifest ${replaced.bookSourceId}",
                        error,
                    )
                }
            } ?: plan.staleSameManifestPublicFiles.forEach { stale ->
                runCatching {
                    completedStorage.deletePublicFile(
                        bookSourceId = manifest.bookSourceId,
                        bookTitle = previousBook?.title ?: manifest.title,
                        file = stale,
                    )
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "Failed to remove stale public file ${stale.fileId} for ${manifest.bookSourceId}",
                        error,
                    )
                }
            }
            plan.book
        }

        cacheStore.setDownloadedReference(manifest.bookId, true)
        try {
            cleanupObsoleteManifestDirectories(manifest.bookSourceId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Cleanup is opportunistic; prepared Room state stays authoritative.
            Log.w(
                TAG,
                "Post-prepare generation cleanup failed for ${manifest.bookSourceId}",
                error,
            )
        }
        return book
    }

    /**
     * Worker mutation fenced by [expectedManifestId]. Do not replace this fence
     * with "latest/current manifest" lookup when extracting DAO-facing state code.
     */
    suspend fun markBookState(
        bookSourceId: String,
        expectedManifestId: String,
        state: String,
        error: String = "",
    ) {
        db.withTransaction {
            val current = dao.book(bookSourceId) ?: return@withTransaction
            if (current.deletedAtMs != null || current.manifestId != expectedManifestId) return@withTransaction
            dao.putBook(
                current.copy(
                    state = state,
                    error = error.take(1000),
                    updatedAtMs = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Worker file mutation fenced by [expectedManifestId]. A worker from an older
     * generation must become a no-op after prepare() installs a replacement manifest.
     */
    suspend fun markFileState(
        bookSourceId: String,
        expectedManifestId: String,
        fileId: String,
        state: String,
        downloadedBytes: Long,
        error: String = "",
    ) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val book = dao.book(bookSourceId) ?: return@withTransaction
            if (book.deletedAtMs != null || book.manifestId != expectedManifestId) return@withTransaction
            val current = dao.files(bookSourceId).firstOrNull { it.fileId == fileId }
                ?: return@withTransaction
            dao.putFiles(
                listOf(
                    current.copy(
                        state = state,
                        downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                        error = error.take(1000),
                        updatedAtMs = now,
                    )
                )
            )
            recalculateLocked(bookSourceId, expectedManifestId, now)
        }
    }

    suspend fun recalculate(
        bookSourceId: String,
        expectedManifestId: String,
        now: Long = System.currentTimeMillis(),
    ): DownloadBookEntity? = db.withTransaction {
        recalculateLocked(bookSourceId, expectedManifestId, now)
    }

    private suspend fun recalculateLocked(
        bookSourceId: String,
        expectedManifestId: String,
        now: Long,
    ): DownloadBookEntity? {
        val book = dao.book(bookSourceId) ?: return null
        if (book.deletedAtMs != null || book.manifestId != expectedManifestId || book.state == "purged") {
            return book
        }
        val files = dao.files(bookSourceId)
        val complete = files.isNotEmpty() && files.all { it.state == "completed" }
        val failed = files.firstOrNull { it.state == "failed" }
        val downloading = files.any { it.state == "downloading" }
        val paused = book.state == "paused"
        val nextState = when {
            complete -> "completed"
            paused -> "paused"
            failed != null -> "failed"
            downloading -> "downloading"
            else -> "queued"
        }
        val updated = book.copy(
            state = nextState,
            downloadedBytes = files.sumOf { it.downloadedBytes.coerceAtLeast(0L) },
            completedFiles = files.count { it.state == "completed" },
            error = failed?.error.orEmpty().take(1000),
            updatedAtMs = now,
        )
        dao.putBook(updated)
        return updated
    }

    suspend fun pause(bookSourceId: String) {
        db.withTransaction {
            val book = dao.book(bookSourceId) ?: return@withTransaction
            if (book.deletedAtMs != null) return@withTransaction
            dao.putBook(book.copy(state = "paused", error = "", updatedAtMs = System.currentTimeMillis()))
        }
    }

    suspend fun queue(bookSourceId: String) {
        db.withTransaction {
            val book = dao.book(bookSourceId) ?: return@withTransaction
            if (book.deletedAtMs != null) return@withTransaction
            val now = System.currentTimeMillis()
            dao.putBook(book.copy(state = "queued", error = "", updatedAtMs = now))
            val files = dao.files(bookSourceId).map { file ->
                if (file.state == "completed") file else file.copy(state = "queued", error = "", updatedAtMs = now)
            }
            if (files.isNotEmpty()) dao.putFiles(files)
        }
    }

    suspend fun setWifiOnly(bookSourceId: String, wifiOnly: Boolean) {
        dao.setWifiOnly(bookSourceId, wifiOnly, System.currentTimeMillis())
    }

    /**
     * Lifecycle-critical synchronous delete.
     *
     * The per-book mutex must cover the pending-delete Room transition, physical
     * cleanup and final row removal/rollback so prepare/publish cannot interleave
     * with a half-deleted generation.
     *
     * Compatibility name kept for existing callers. Deletion is now synchronous:
     * audio bytes, partials, file rows and the download row are removed before return.
     */
    suspend fun markPendingDelete(bookSourceId: String, nowMs: Long = System.currentTimeMillis()): DownloadBookEntity? =
        lifecycleMutex(bookSourceId).withLock {
            val originalBook = dao.book(bookSourceId) ?: return@withLock null
            val trackedFiles = dao.files(bookSourceId)
            val hasPublicCompletedArtifacts = trackedFiles.any { file -> file.state == "completed" }

            // Android 7-9 needs WRITE_EXTERNAL_STORAGE only when public completed
            // artifacts may need deletion. Private queued/partial bytes can still
            // be removed after permission was revoked. Check before touching Room
            // so a denied public cleanup never strands pending_delete state.
            if (hasPublicCompletedArtifacts && !completedStorage.canPublish()) {
                error("Разрешите доступ к хранилищу, чтобы удалить скачанные файлы из папки Download.")
            }

            val book = db.withTransaction {
                val current = dao.book(bookSourceId) ?: return@withTransaction null
                val pending = current.copy(
                    state = "pending_delete",
                    error = "",
                    deletedAtMs = nowMs,
                    purgeAfterMs = nowMs,
                    updatedAtMs = nowMs,
                )
                dao.putBook(pending)
                pending
            } ?: return@withLock null

            try {
                // Delete the currently tracked paths first. The hashed current root is
                // safe to remove recursively; a legacy unhashed root is removed only
                // when no other source id maps to the same old lossy segment.
                if (hasPublicCompletedArtifacts || completedStorage.canPublish()) {
                    completedStorage.deletePublicBookArtifacts(
                        bookSourceId = book.bookSourceId,
                        bookTitle = book.title,
                    )
                }
                trackedFiles.forEach { file ->
                    finalFile(file).delete()
                    partialFile(file).delete()
                }
                fileLayout.sourceDirectory(bookSourceId).deleteRecursively()
                val legacyCollision = dao.allBooks().any { other ->
                    other.bookSourceId != bookSourceId &&
                        legacySafeSegment(other.bookSourceId) == legacySafeSegment(bookSourceId)
                }
                if (!legacyCollision) fileLayout.legacySourceDirectory(bookSourceId).deleteRecursively()
            } catch (error: Exception) {
                // Keep delete idempotent without stranding a live download in
                // pending_delete when public cleanup fails. Physical cleanup can be
                // partial; files() will reconcile any missing completed artifact on
                // the next read after the original Room state is restored.
                db.withTransaction {
                    val current = dao.book(bookSourceId)
                    if (
                        current != null &&
                        current.manifestId == originalBook.manifestId &&
                        current.state == "pending_delete" &&
                        current.deletedAtMs == nowMs
                    ) {
                        dao.putBook(
                            originalBook.copy(
                                updatedAtMs = System.currentTimeMillis(),
                            )
                        )
                    }
                }
                throw error
            }

            db.withTransaction {
                val current = dao.book(bookSourceId)
                if (current?.deletedAtMs != null) {
                    dao.deleteFiles(bookSourceId)
                    dao.deleteBook(bookSourceId)
                }
            }

            val anotherPhysicalCopy = dao.allBooks().any { other ->
                other.bookId == book.bookId &&
                    other.deletedAtMs == null &&
                    other.state != "purged" &&
                    other.downloadedBytes > 0L
            }
            if (!anotherPhysicalCopy) cacheStore.setDownloadedReference(book.bookId, false)

            // Return a detached deletion result to the caller. The Room row/files are
            // already gone; purgeAfterMs stays null because deletion is immediate.
            book.copy(
                state = "purged",
                downloadedBytes = 0L,
                completedFiles = 0,
                error = "",
                deletedAtMs = nowMs,
                purgeAfterMs = null,
                updatedAtMs = nowMs,
            )
        }

    /**
     * Migration cleanup for old builds that left one-hour pending-delete rows.
     * Deadlines are intentionally ignored: every deleted row is purged immediately.
     */
    suspend fun purgeExpired(nowMs: Long = System.currentTimeMillis()): List<DownloadBookEntity> {
        val deleted = dao.deletedBooks()
        val purged = mutableListOf<DownloadBookEntity>()
        for (book in deleted) {
            markPendingDelete(book.bookSourceId, nowMs)?.let(purged::add)
        }
        return purged
    }

    fun finalFile(file: DownloadFileEntity): File = fileLayout.finalFile(file.localPath)
    fun partialFile(file: DownloadFileEntity): File = fileLayout.partialFile(file.localPath)

    suspend fun hasUsableCompletedArtifact(file: DownloadFileEntity): Boolean {
        if (file.state != "completed") return false
        val expectedBytes = expectedBytes(file)
        val bookTitle = dao.book(file.bookSourceId)?.title.orEmpty()
        return completedStorage.inspect(
            bookSourceId = file.bookSourceId,
            bookTitle = bookTitle,
            file = file,
            expectedBytes = expectedBytes,
        ).completedBytes != null
    }

    suspend fun playbackUri(file: DownloadFileEntity): Uri? {
        if (file.state != "completed") return null
        val expectedBytes = expectedBytes(file)
        val bookTitle = dao.book(file.bookSourceId)?.title.orEmpty()
        completedStorage.publicPlaybackUri(
            file.bookSourceId,
            bookTitle,
            file,
            expectedBytes,
        )?.let { return it }

        val physical = completedStorage.inspect(
            bookSourceId = file.bookSourceId,
            bookTitle = bookTitle,
            file = file,
            expectedBytes = expectedBytes,
        )
        return physical.privateCompletedBytes
            ?.let { Uri.fromFile(finalFile(file)) }
    }

    /**
     * Lifecycle-critical publication. Keep the expected-manifest check, physical
     * publication and Room completion update under the same per-book mutex.
     */
    suspend fun publishCompletedFile(
        file: DownloadFileEntity,
        expectedManifestId: String,
    ): Long = lifecycleMutex(file.bookSourceId).withLock {
        val book = dao.book(file.bookSourceId)
            ?: error("Загрузка больше не существует")
        check(
            book.manifestId == expectedManifestId &&
                book.deletedAtMs == null &&
                book.state != "purged"
        ) {
            "Поколение загрузки устарело"
        }
        val current = dao.files(file.bookSourceId).firstOrNull { row -> row.fileId == file.fileId }
            ?: error("Файл загрузки больше не существует")
        val expectedBytes = expectedBytes(current)
        val physical = completedStorage.inspect(
            bookSourceId = book.bookSourceId,
            bookTitle = book.title,
            file = current,
            expectedBytes = expectedBytes,
        )
        val publishedBytes = physical.publicBytes ?: completedStorage.publishPrivateCompletedToPublic(
            bookSourceId = book.bookSourceId,
            bookTitle = book.title,
            file = current,
            expectedBytes = expectedBytes,
        )

        val now = System.currentTimeMillis()
        db.withTransaction {
            dao.putFiles(
                listOf(
                    current.copy(
                        state = "completed",
                        downloadedBytes = publishedBytes,
                        error = "",
                        updatedAtMs = now,
                    )
                )
            )
            recalculateLocked(book.bookSourceId, expectedManifestId, now)
        }
        completedStorage.deletePrivateArtifacts(current)
        publishedBytes
    }

    suspend fun diskBytes(): Long {
        var total = 0L
        for (book in dao.books()) {
            for (file in dao.files(book.bookSourceId)) {
                total += completedStorage.diskBytes(
                    bookSourceId = book.bookSourceId,
                    bookTitle = book.title,
                    file = file,
                    expectedBytes = expectedBytes(file),
                )
            }
        }
        return total.coerceAtLeast(0L)
    }

    private fun normalizeCompletedFile(
        file: DownloadFileEntity,
        bookTitle: String,
    ): DownloadFileEntity {
        if (file.state != "completed") return file
        val expectedBytes = expectedBytes(file)
        val physical = completedStorage.inspect(
            bookSourceId = file.bookSourceId,
            bookTitle = bookTitle,
            file = file,
            expectedBytes = expectedBytes,
        )
        physical.completedBytes?.let { actualBytes ->
            return file.copy(
                sizeBytes = expectedBytes,
                downloadedBytes = actualBytes.coerceAtLeast(0L),
            )
        }
        if (!physical.publicStorageAccessible) {
            // On legacy Android without storage permission we cannot prove that
            // the public completed artifact disappeared, so keep persisted state.
            return file
        }
        return file.copy(
            sizeBytes = expectedBytes,
            state = "queued",
            downloadedBytes = physical.partialBytes,
            error = "",
        )
    }

    private fun expectedBytes(file: DownloadFileEntity): Long? =
        resolveDownloadExpectedBytes(
            manifestBytes = file.sizeBytes,
            persistedBytes = null,
            previousState = file.state,
            previousDownloadedBytes = file.downloadedBytes,
        )

    private fun lifecycleMutex(bookSourceId: String) =
        generationStorage.lifecycleMutex(bookSourceId)

    private companion object {
        const val TAG = "DownloadStore"
    }

}

internal fun resolveDownloadExpectedBytes(
    manifestBytes: Long?,
    persistedBytes: Long?,
    previousState: String?,
    previousDownloadedBytes: Long,
): Long? = manifestBytes
    ?: persistedBytes
    ?: previousDownloadedBytes.takeIf { previousState == "completed" && it > 0L }
