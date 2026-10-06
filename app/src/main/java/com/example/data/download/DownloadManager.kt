package com.example.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.api.ApiClient
import com.example.data.local.DownloadBookEntity
import com.example.data.local.DownloadFileEntity
import com.example.data.local.DownloadStore
import com.example.data.repository.AudiobookRepository
import com.example.data.torrserve.TorrServeClient
import com.example.data.torrserve.isTransientStreamStatus
import com.example.di.DownloadHttpClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Coordinates manifest refresh + durable Room/WorkManager download jobs. */
class AudiobookDownloadManager(
    context: Context,
    private val repository: AudiobookRepository,
    private val store: DownloadStore,
) {
    private val appContext = context.applicationContext

    suspend fun start(bookSourceId: String, wifiOnly: Boolean): DownloadBookEntity {
        require(store.canPublishCompletedFiles()) {
            "Разрешите доступ к хранилищу, чтобы сохранять книги в папку Download."
        }
        val manifest = repository.downloadManifest(bookSourceId)
        require(manifest.files.isNotEmpty()) { "Источник не содержит файлов для скачивания" }
        val existing = store.book(bookSourceId)
        val already = existing?.downloadedBytes ?: 0L
        manifest.totalSizeBytes?.let { total ->
            val remaining = (total - already).coerceAtLeast(0L)
            require(appContext.filesDir.usableSpace > remaining + SPACE_RESERVE_BYTES) {
                "Недостаточно свободного места: нужно ещё примерно ${formatBytes(remaining)}"
            }
        } ?: require(appContext.filesDir.usableSpace > SPACE_RESERVE_BYTES) {
            "Недостаточно свободного места для начала загрузки"
        }

        // Stop the previous generation before replacing its Room rows. Cancellation
        // is asynchronous, so DownloadStore also isolates paths by manifest id and
        // rejects every stale worker mutation by expected manifest id.
        DownloadScheduler.cancel(appContext, bookSourceId)
        val book = store.prepare(manifest, wifiOnly)
        if (book.state != "completed") {
            DownloadScheduler.enqueue(
                context = appContext,
                bookSourceId = book.bookSourceId,
                manifestId = book.manifestId,
                wifiOnly = wifiOnly,
            )
        }
        return book
    }

    suspend fun pause(bookSourceId: String) {
        store.pause(bookSourceId)
        DownloadScheduler.cancel(appContext, bookSourceId)
    }

    suspend fun resume(bookSourceId: String, wifiOnly: Boolean) {
        require(store.canPublishCompletedFiles()) {
            "Разрешите доступ к хранилищу, чтобы сохранять книги в папку Download."
        }
        val current = store.book(bookSourceId) ?: error("Загрузка не найдена")
        require(current.deletedAtMs == null) { "Загрузка уже удалена" }
        store.setWifiOnly(bookSourceId, wifiOnly)
        store.queue(bookSourceId)
        DownloadScheduler.enqueue(
            context = appContext,
            bookSourceId = bookSourceId,
            manifestId = current.manifestId,
            wifiOnly = wifiOnly,
        )
    }

    suspend fun retry(bookSourceId: String, wifiOnly: Boolean): DownloadBookEntity {
        store.book(bookSourceId) ?: error("Загрузка не найдена")
        // Refreshing the manifest is mandatory after 409/stale source. DownloadStore
        // preserves partials only when manifest_id is still exactly the same.
        return start(bookSourceId, wifiOnly)
    }

    /** Apply the global network policy to all durable jobs and re-schedule active ones. */
    suspend fun updateNetworkPolicy(wifiOnly: Boolean) {
        val canPublish = store.canPublishCompletedFiles()
        store.books().forEach { book ->
            store.setWifiOnly(book.bookSourceId, wifiOnly)
            if (canPublish && (book.state == "queued" || book.state == "downloading")) {
                DownloadScheduler.enqueue(
                    context = appContext,
                    bookSourceId = book.bookSourceId,
                    manifestId = book.manifestId,
                    wifiOnly = wifiOnly,
                )
            }
        }
    }

    /** Re-arm download jobs after process death or upgrade and clean legacy state. */
    suspend fun reconcileLifecycle() {
        store.purgeExpired()
        val initialBooks = store.books()
        for (row in initialBooks) {
            try {
                // Public Downloads are user-visible and can be changed outside the app.
                // Reconcile completed rows before deciding whether a worker is needed.
                store.files(row.bookSourceId)
                store.cleanupObsoleteManifestDirectories(row.bookSourceId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(
                    "AudiobookDownloadManager",
                    "Lifecycle reconcile cleanup failed for ${row.bookSourceId}",
                    error,
                )
            }
        }
        if (!store.canPublishCompletedFiles()) {
            return
        }
        store.books()
            .filter { it.state == "queued" || it.state == "downloading" }
            .forEach { row ->
                DownloadScheduler.enqueue(
                    context = appContext,
                    bookSourceId = row.bookSourceId,
                    manifestId = row.manifestId,
                    wifiOnly = row.wifiOnly,
                )
            }
    }

    companion object {
        private const val SPACE_RESERVE_BYTES = 64L * 1024L * 1024L

        private fun formatBytes(value: Long): String {
            if (value < 1024L) return "$value Б"
            val kb = value / 1024.0
            if (kb < 1024.0) return "%.1f КБ".format(kb)
            val mb = kb / 1024.0
            if (mb < 1024.0) return "%.1f МБ".format(mb)
            return "%.2f ГБ".format(mb / 1024.0)
        }
    }
}

internal fun downloadWorkerFailureIsObsoleteOrStopped(
    current: DownloadBookEntity?,
    expectedManifestId: String,
): Boolean =
    current == null ||
        current.manifestId != expectedManifestId ||
        current.deletedAtMs != null ||
        current.state == "paused" ||
        current.state == "completed" ||
        current.state == "purged"

internal fun shouldPersistDownloadProgress(
    nowElapsedMs: Long,
    lastPersistElapsedMs: Long,
    intervalMs: Long = 1_000L,
): Boolean =
    nowElapsedMs >= lastPersistElapsedMs &&
        nowElapsedMs - lastPersistElapsedMs >= intervalMs.coerceAtLeast(1L)

internal object DownloadHttpPolicy {
    private val contentRange = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)

    fun contentRangeStartsAt(value: String?, expectedOffset: Long): Boolean {
        val match = value?.trim()?.let(contentRange::matchEntire) ?: return false
        return match.groupValues[1].toLongOrNull() == expectedOffset
    }

    fun expectedFinalSize(
        statusCode: Int,
        contentRangeHeader: String?,
        contentLength: Long,
        requestedOffset: Long,
    ): Long? {
        val match = contentRangeHeader?.trim()?.let(contentRange::matchEntire)
        val totalToken = match?.groupValues?.getOrNull(3)
        val declaredTotal = totalToken
            ?.takeUnless { it == "*" }
            ?.toLongOrNull()
        if (declaredTotal != null && declaredTotal >= 0L) return declaredTotal

        // A valid 206 Content-Range with an unknown total does not prove that the
        // end of this response is the end of the resource. redirectto.cc can end
        // a 206 at its CDN window boundary, so never turn one chunk into a fake
        // whole-file size.
        if (statusCode == 206 && match != null && totalToken == "*") return null

        if (contentLength < 0L) return null
        return if (statusCode == 206) {
            safeAdd(requestedOffset.coerceAtLeast(0L), contentLength)
        } else {
            contentLength
        }
    }

    fun shouldContinueRangedResponse(
        statusCode: Int,
        writtenBytes: Long,
        expectedBytes: Long?,
        continueUnknownLengthRange: Boolean = false,
    ): Boolean {
        if (statusCode != 206) return false
        return expectedBytes?.let { writtenBytes < it } ?: continueUnknownLengthRange
    }

    fun completedSizeFromRangeNotSatisfiable(
        statusCode: Int,
        requestedOffset: Long,
        expectedBytes: Long?,
        rangedCdnMp3: Boolean,
    ): Long? {
        if (statusCode != 416 || requestedOffset < 0L) return null
        if (expectedBytes != null) return requestedOffset.takeIf { it == expectedBytes }
        return requestedOffset.takeIf { rangedCdnMp3 && it > 0L }
    }

    fun wouldExceedLimit(currentBytes: Long, incomingBytes: Int, limitBytes: Long): Boolean {
        if (incomingBytes < 0 || currentBytes < 0L || limitBytes < 0L) return true
        val incoming = incomingBytes.toLong()
        return currentBytes > limitBytes || incoming > limitBytes - currentBytes
    }

    private fun safeAdd(left: Long, right: Long): Long? =
        if (right > Long.MAX_VALUE - left) null else left + right
}

object DownloadScheduler {
    private fun workName(bookSourceId: String) = "abred-download-v1-$bookSourceId"

    fun enqueue(context: Context, bookSourceId: String, manifestId: String, wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(BookDownloadWorker.KEY_BOOK_SOURCE_ID, bookSourceId)
                    .putString(BookDownloadWorker.KEY_MANIFEST_ID, manifestId)
                    .build()
            )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName(bookSourceId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context, bookSourceId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(bookSourceId))
    }
}

@HiltWorker
class BookDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val store: DownloadStore,
    @DownloadHttpClient private val http: OkHttpClient,
    private val torrServeClient: TorrServeClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val bookSourceId = inputData.getString(KEY_BOOK_SOURCE_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        // Jobs persisted by APKs before manifest generations existed cannot prove
        // which manifest they belong to. Never let one adopt today's generation;
        // startup reconciliation re-enqueues every live job with an explicit id.
        val expectedManifestId = inputData.getString(KEY_MANIFEST_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return Result.success()

        store.markDownloadGenerationActive(bookSourceId, expectedManifestId)
        return try {
            val initialBook = store.book(bookSourceId) ?: return Result.success()
            if (initialBook.manifestId != expectedManifestId) return Result.success()
            if (
                initialBook.deletedAtMs != null || initialBook.state == "paused" ||
                initialBook.state == "completed" || initialBook.state == "purged"
            ) return Result.success()
            if (!store.canPublishCompletedFiles()) {
                store.markBookState(
                    bookSourceId,
                    expectedManifestId,
                    "queued",
                    "Разрешите доступ к хранилищу, чтобы сохранять книги в папку Download.",
                )
                return Result.success()
            }
            setForeground(createForegroundInfo(initialBook))

            try {
                store.markBookState(bookSourceId, expectedManifestId, "downloading")
                val files = store.files(bookSourceId)
                for (file in files) {
                    val current = store.book(bookSourceId) ?: return Result.success()
                    if (current.manifestId != expectedManifestId) return Result.success()
                    if (current.deletedAtMs != null || current.state == "paused" || current.state == "purged") {
                        return Result.success()
                    }
                    if (file.state == "completed" && store.hasUsableCompletedArtifact(file)) continue
                    downloadFile(file, expectedManifestId, current.sourceCode)
                }
                store.recalculate(bookSourceId, expectedManifestId)
                val finished = store.book(bookSourceId)
                if (finished?.manifestId == expectedManifestId && finished.state == "completed") {
                    Result.success()
                } else if (finished?.manifestId != expectedManifestId) {
                    Result.success()
                } else {
                    retryOrFail(bookSourceId, expectedManifestId, "Не все файлы были загружены")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (stale: PermanentDownloadException) {
                val current = store.book(bookSourceId)
                if (downloadWorkerFailureIsObsoleteOrStopped(current, expectedManifestId)) {
                    Result.success()
                } else {
                    store.markBookState(bookSourceId, expectedManifestId, "failed", stale.message.orEmpty())
                    Result.failure()
                }
            } catch (error: Exception) {
                if (!store.canPublishCompletedFiles()) {
                    store.markBookState(
                        bookSourceId,
                        expectedManifestId,
                        "queued",
                        "Разрешите доступ к хранилищу, чтобы сохранять книги в папку Download.",
                    )
                    Result.success()
                } else {
                    retryOrFail(bookSourceId, expectedManifestId, error.message ?: "Ошибка загрузки")
                }
            }
        } finally {
            withContext(NonCancellable) {
                store.markDownloadGenerationInactive(bookSourceId, expectedManifestId)
                try {
                    store.cleanupObsoleteManifestDirectories(bookSourceId)
                } catch (error: Exception) {
                    // Generation GC must never change the worker result, but a
                    // persistent cleanup failure still needs a diagnostic trace.
                    Log.w(
                        "BookDownloadWorker",
                        "Generation cleanup failed for $bookSourceId manifest=$expectedManifestId",
                        error,
                    )
                }
            }
        }
    }

    private suspend fun retryOrFail(
        bookSourceId: String,
        expectedManifestId: String,
        message: String,
    ): Result {
        val current = store.book(bookSourceId)
        if (downloadWorkerFailureIsObsoleteOrStopped(current, expectedManifestId)) {
            return Result.success()
        }
        return if (runAttemptCount < MAX_RETRIES) {
            store.markBookState(bookSourceId, expectedManifestId, "queued", message)
            Result.retry()
        } else {
            store.markBookState(bookSourceId, expectedManifestId, "failed", message)
            Result.failure()
        }
    }

    private suspend fun downloadFile(
        file: DownloadFileEntity,
        expectedManifestId: String,
        sourceCode: String,
    ) = withContext(Dispatchers.IO) {
        val finalFile = store.finalFile(file)
        val partFile = store.partialFile(file)
        val initialLifecycle = store.book(file.bookSourceId)
        if (shouldDiscardFiles(initialLifecycle, expectedManifestId)) {
            deleteOrphanFiles(file)
            return@withContext
        }
        if (initialLifecycle?.state == "paused") return@withContext

        try {
            finalFile.parentFile?.mkdirs()

            if (finalFile.isFile && (file.sizeBytes == null || finalFile.length() == file.sizeBytes)) {
                store.publishCompletedFile(file, expectedManifestId)
                return@withContext
            }

            if (file.sizeBytes != null) {
                val remaining = (file.sizeBytes - partFile.length()).coerceAtLeast(0L)
                ensureFreeSpace(remaining, file.filename)
            } else {
                ensureFreeSpace(null, file.filename)
            }

            var initialOffset = if (partFile.isFile) partFile.length().coerceAtLeast(0L) else 0L
            if (file.sizeBytes != null && initialOffset > file.sizeBytes) {
                partFile.delete()
                initialOffset = 0L
            }
            if (file.sizeBytes == null && initialOffset > MAX_UNKNOWN_FILE_BYTES) {
                throw PermanentDownloadException("Частичный файл ${file.filename} превышает безопасный предел размера")
            }

            val isRuTrackerTorrServe = sourceCode.equals("rutracker", ignoreCase = true)
            val directUrl = if (isRuTrackerTorrServe) {
                torrServeClient.configuredStreamUrl(file.downloadUrl)
                    ?: throw PermanentDownloadException(
                        "Адрес TorrServe изменился или stream URL устарел. " +
                            "Нажмите «Повторить», чтобы заново подготовить загрузку."
                    )
            } else {
                ApiClient.externalHttpUrl(file.downloadUrl)
                    ?: throw PermanentDownloadException(
                        "Некорректный URL загрузки: ожидается прямой HTTP(S) URL"
                    )
            }
            val parsedUrl = directUrl.toHttpUrlOrNull()
                ?: throw PermanentDownloadException("Некорректный URL загрузки: ожидается прямой HTTP(S) URL")
            val rangedCdnMp3 = !isRuTrackerTorrServe &&
                ApiClient.isRedirectToCdnHost(parsedUrl.host) &&
                parsedUrl.encodedPath.endsWith(".mp3", ignoreCase = true)
            val providerHeaders = if (isRuTrackerTorrServe) {
                emptyMap()
            } else {
                ApiClient.standaloneProviderRequestHeaders(sourceCode)
            }
            val requestHttp = if (isRuTrackerTorrServe) {
                torrServeClient.streamingHttpClient()
            } else {
                http
            }

            RandomAccessFile(partFile, "rw").use { output ->
                if (initialOffset > 0L) output.seek(initialOffset) else output.setLength(0L)
                var written = initialOffset
                var expectedBytes: Long? = file.sizeBytes
                var lastPersistBytes = written
                var lastPersistAt = SystemClock.elapsedRealtime()
                var lastSpaceCheckBytes = written
                var lastSpaceCheckAt = lastPersistAt

                store.markFileState(
                    file.bookSourceId,
                    expectedManifestId,
                    file.fileId,
                    "downloading",
                    written,
                )

                var torrServeTransientAttempts = 0
                var torrServeUnauthorizedRetried = false
                downloadLoop@ while (true) {
                    ensureActive()
                    val lifecycle = store.book(file.bookSourceId)
                    if (shouldDiscardFiles(lifecycle, expectedManifestId)) {
                        throw RemovedDownloadException()
                    }
                    if (lifecycle?.state == "paused") {
                        store.markFileState(
                            file.bookSourceId,
                            expectedManifestId,
                            file.fileId,
                            "queued",
                            written,
                        )
                        return@withContext
                    }
                    if (expectedBytes != null && written >= expectedBytes) break

                    val requestedOffset = written
                    val requestHasRange = requestedOffset > 0L || rangedCdnMp3
                    val requestHeaders = if (isRuTrackerTorrServe) {
                        torrServeClient.authorizationHeaders()
                    } else {
                        providerHeaders
                    }
                    val request = Request.Builder()
                        .url(directUrl)
                        .get()
                        .apply {
                            requestHeaders.forEach { (name, value) -> header(name, value) }
                            if (requestHasRange) header("Range", "bytes=$requestedOffset-")
                        }
                        .build()

                    val response = try {
                        requestHttp.newCall(request).execute()
                    } catch (error: IOException) {
                        throw error
                    }

                    if (
                        isRuTrackerTorrServe &&
                        response.code == 401 &&
                        !torrServeUnauthorizedRetried
                    ) {
                        response.close()
                        torrServeUnauthorizedRetried = true
                        delay(100L)
                        continue@downloadLoop
                    }

                    if (isRuTrackerTorrServe && isTransientStreamStatus(response.code)) {
                        val code = response.code
                        response.close()
                        if (torrServeTransientAttempts < TORRSERVE_STREAM_OPEN_RETRIES) {
                            torrServeTransientAttempts += 1
                            delay(TORRSERVE_STREAM_OPEN_RETRY_MS)
                            continue@downloadLoop
                        }
                        throw IOException(
                            "TorrServe ещё готовит поток " + file.filename + ": HTTP " + code
                        )
                    }
                    torrServeTransientAttempts = 0
                    if (response.code in 200..299) {
                        torrServeUnauthorizedRetried = false
                    }

                    var continueRange = false
                    response.use { res ->
                        when (res.code) {
                            401 -> if (isRuTrackerTorrServe) {
                                throw PermanentDownloadException(
                                    "TorrServe отклонил авторизацию. Проверьте логин и пароль."
                                )
                            }
                            403 -> if (isRuTrackerTorrServe) {
                                throw PermanentDownloadException(
                                    "TorrServe запретил скачивание. Проверьте права пользователя."
                                )
                            }
                            409 -> throw PermanentDownloadException("Источник изменился. Нажмите «Повторить», чтобы получить новый manifest")
                            410 -> throw PermanentDownloadException("Файл больше недоступен в выбранном источнике")
                            404 -> throw PermanentDownloadException("Файл загрузки не найден")
                        }
                        val completedAt416 = DownloadHttpPolicy.completedSizeFromRangeNotSatisfiable(
                            statusCode = res.code,
                            requestedOffset = requestedOffset,
                            expectedBytes = expectedBytes,
                            rangedCdnMp3 = rangedCdnMp3,
                        )
                        if (completedAt416 != null) {
                            if (expectedBytes == null) expectedBytes = completedAt416
                            return@use
                        }
                        if (!res.isSuccessful) {
                            if (res.code in 400..499 && res.code != 408 && res.code != 429) {
                                throw PermanentDownloadException("HTTP ${res.code} при скачивании ${file.filename}")
                            }
                            throw IOException("HTTP ${res.code} при скачивании ${file.filename}")
                        }

                        val body = res.body ?: throw IOException("Пустой ответ для ${file.filename}")
                        if (
                            res.code == 206 && requestHasRange &&
                            !DownloadHttpPolicy.contentRangeStartsAt(res.header("Content-Range"), requestedOffset)
                        ) {
                            throw PermanentDownloadException("Некорректный Content-Range при продолжении ${file.filename}")
                        }

                        // If a generic server ignores a resume Range and returns 200,
                        // restart from byte zero. redirectto.cc follows the captured 206
                        // contract and therefore appends every subsequent chunk.
                        val append = requestedOffset > 0L && res.code == 206
                        if (append) {
                            output.seek(requestedOffset)
                            written = requestedOffset
                        } else if (requestedOffset > 0L) {
                            output.setLength(0L)
                            output.seek(0L)
                            written = 0L
                        } else {
                            output.setLength(0L)
                            output.seek(0L)
                            written = 0L
                        }

                        val responseExpectedBytes = DownloadHttpPolicy.expectedFinalSize(
                            statusCode = res.code,
                            contentRangeHeader = res.header("Content-Range"),
                            contentLength = body.contentLength(),
                            requestedOffset = written,
                        )
                        if (
                            file.sizeBytes == null && expectedBytes != null && responseExpectedBytes != null &&
                            expectedBytes != responseExpectedBytes
                        ) {
                            throw PermanentDownloadException("Источник изменил размер ${file.filename} во время загрузки")
                        }
                        expectedBytes = file.sizeBytes ?: expectedBytes ?: responseExpectedBytes
                        val currentExpectedBytes = expectedBytes
                        if (currentExpectedBytes != null) {
                            if (currentExpectedBytes < written) {
                                throw PermanentDownloadException("Сервер вернул некорректный размер ${file.filename}")
                            }
                            if (file.sizeBytes == null && currentExpectedBytes > MAX_UNKNOWN_FILE_BYTES) {
                                throw PermanentDownloadException("Файл ${file.filename} превышает безопасный предел размера")
                            }
                            ensureFreeSpace((currentExpectedBytes - written).coerceAtLeast(0L), file.filename)
                        } else {
                            ensureFreeSpace(null, file.filename)
                        }

                        val responseStart = written
                        val input = body.byteStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        while (true) {
                            ensureActive()
                            val activeLifecycle = store.book(file.bookSourceId)
                            if (shouldDiscardFiles(activeLifecycle, expectedManifestId)) {
                                throw RemovedDownloadException()
                            }
                            if (activeLifecycle?.state == "paused") {
                                store.markFileState(
                                    file.bookSourceId,
                                    expectedManifestId,
                                    file.fileId,
                                    "queued",
                                    written,
                                )
                                return@withContext
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            val limit = expectedBytes ?: MAX_UNKNOWN_FILE_BYTES
                            if (DownloadHttpPolicy.wouldExceedLimit(written, count, limit)) {
                                val message = if (expectedBytes != null) {
                                    "Файл ${file.filename} превысил ожидаемый размер"
                                } else {
                                    "Файл ${file.filename} превысил безопасный предел размера"
                                }
                                throw PermanentDownloadException(message)
                            }
                            val now = SystemClock.elapsedRealtime()
                            if (
                                written - lastSpaceCheckBytes >= SPACE_CHECK_BYTES ||
                                now - lastSpaceCheckAt >= SPACE_CHECK_MS
                            ) {
                                ensureFreeSpace(null, file.filename)
                                lastSpaceCheckBytes = written
                                lastSpaceCheckAt = now
                            }
                            output.write(buffer, 0, count)
                            written += count.toLong()
                            if (shouldPersistDownloadProgress(now, lastPersistAt)) {
                                store.markFileState(
                                    file.bookSourceId,
                                    expectedManifestId,
                                    file.fileId,
                                    "downloading",
                                    written,
                                )
                                lastPersistBytes = written
                                lastPersistAt = now
                            }
                        }

                        if (written <= responseStart) {
                            throw IOException("Источник вернул пустой диапазон для ${file.filename} с позиции $requestedOffset")
                        }
                        continueRange = DownloadHttpPolicy.shouldContinueRangedResponse(
                            statusCode = res.code,
                            writtenBytes = written,
                            expectedBytes = expectedBytes,
                            continueUnknownLengthRange = rangedCdnMp3,
                        )
                        if (!continueRange && expectedBytes != null && written != expectedBytes) {
                            throw IOException("Файл ${file.filename} загружен не полностью: $written из $expectedBytes")
                        }
                    }

                    if (!continueRange) break
                }

                output.fd.sync()
                val finalLifecycle = store.book(file.bookSourceId)
                if (shouldDiscardFiles(finalLifecycle, expectedManifestId)) {
                    throw RemovedDownloadException()
                }
                if (finalLifecycle?.state == "paused") {
                    store.markFileState(
                        file.bookSourceId,
                        expectedManifestId,
                        file.fileId,
                        "queued",
                        written,
                    )
                    return@withContext
                }
                if (expectedBytes != null && written != expectedBytes) {
                    store.markFileState(
                        file.bookSourceId,
                        expectedManifestId,
                        file.fileId,
                        "queued",
                        written,
                    )
                    throw IOException("Файл ${file.filename} загружен не полностью: $written из $expectedBytes")
                }
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                store.publishCompletedFile(file, expectedManifestId)
            }
        } catch (_: RemovedDownloadException) {
            deleteOrphanFiles(file)
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                if (shouldDiscardFiles(store.book(file.bookSourceId), expectedManifestId)) {
                    deleteOrphanFiles(file)
                }
            }
            throw cancel
        }
    }

    private fun ensureFreeSpace(requiredBytes: Long?, filename: String) {
        val usable = applicationContext.filesDir.usableSpace.coerceAtLeast(0L)
        val afterReserve = (usable - SPACE_RESERVE_BYTES).coerceAtLeast(0L)
        if (usable <= SPACE_RESERVE_BYTES || (requiredBytes != null && afterReserve <= requiredBytes)) {
            throw PermanentDownloadException("Недостаточно свободного места для $filename")
        }
    }

    private fun shouldDiscardFiles(book: DownloadBookEntity?, expectedManifestId: String): Boolean =
        book == null ||
            book.manifestId != expectedManifestId ||
            book.deletedAtMs != null ||
            book.state == "purged"

    private fun deleteOrphanFiles(file: DownloadFileEntity) {
        val finalFile = store.finalFile(file)
        val partFile = store.partialFile(file)
        partFile.delete()
        finalFile.delete()
        finalFile.parentFile
            ?.takeIf { directory -> directory.isDirectory && directory.list()?.isEmpty() == true }
            ?.delete()
    }

    private fun createForegroundInfo(book: DownloadBookEntity): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Загрузка аудиокниг",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(com.example.R.mipmap.ic_launcher)
            .setContentTitle("Скачивание аудиокниги")
            .setContentText(book.title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        val id = NOTIFICATION_BASE + (book.bookSourceId.hashCode() and 0x3fffffff) % 10_000
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private class RemovedDownloadException : IOException()
    private class PermanentDownloadException(message: String) : IOException(message)

    companion object {
        const val KEY_BOOK_SOURCE_ID = "book_source_id"
        const val KEY_MANIFEST_ID = "manifest_id"
        private const val CHANNEL_ID = "abred-downloads"
        private const val NOTIFICATION_BASE = 7200
        private const val MAX_RETRIES = 8
        private const val TORRSERVE_STREAM_OPEN_RETRIES = 8
        private const val TORRSERVE_STREAM_OPEN_RETRY_MS = 500L
        private const val SPACE_RESERVE_BYTES = 64L * 1024L * 1024L
        private const val MAX_UNKNOWN_FILE_BYTES = 8L * 1024L * 1024L * 1024L
        private const val SPACE_CHECK_BYTES = 512L * 1024L
        private const val SPACE_CHECK_MS = 1_000L
        private const val PROGRESS_MS = 1_000L
    }
}
