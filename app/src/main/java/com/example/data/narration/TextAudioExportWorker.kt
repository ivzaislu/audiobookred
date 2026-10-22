package com.example.data.narration

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.data.local.DownloadFileEntity
import com.example.data.local.DownloadStore
import com.example.data.textbook.TextLibraryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

@HiltWorker
internal class TextAudioExportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val exports: TextAudioExportRepository,
    private val books: TextLibraryRepository,
    private val narration: NarrationRepository,
    private val ruVoice: RuVoiceTtsEngine,
    private val voices: RuVoiceVoicePreferences,
    private val store: DownloadStore,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sourceId = inputData.getString("book_source_id") ?: return@withContext Result.failure()
        val manifestId = inputData.getString("manifest_id") ?: return@withContext Result.failure()
        exports.workerLock(sourceId).withLock {
            var workingFile: DownloadFileEntity? = null
            store.markDownloadGenerationActive(sourceId, manifestId)
            try {
                checkActive(sourceId, manifestId)
                setForeground(notification(sourceId))
                val request = exports.request(sourceId, manifestId)
                check(ruVoice.requireInstalled() == request.engineVersion &&
                    voices.revision() == request.settingsRevision) {
                    "RuVoice или его настройки изменились. Запустите сохранение аудиокниги заново."
                }
                val source = books.narrationBook(request.bookId)
                val fragments = source.fragments.groupBy { it.chapterId }
                for (file in store.files(sourceId)) {
                    checkActive(sourceId, manifestId)
                    require(request.chapterId == null || file.chapterId == request.chapterId)
                    if (file.state == "completed" && store.hasUsableCompletedArtifact(file)) {
                        // Also repairs a private final recovered by prepare() after process death.
                        val recoveredDuration = store.finalFile(file).takeIf { it.isFile && it.length() > 44 }
                            ?.let { runCatching { PreviewWav.readExport(it).durationMs / 1_000L }.getOrNull() }
                        store.publishCompletedFile(file, manifestId, durationSeconds = file.durationSeconds.takeIf { it > 0 } ?: recoveredDuration)
                        continue
                    }
                    workingFile = file
                    val chapter = checkNotNull(fragments[file.chapterId]) { "Текст главы больше не найден." }
                    require(chapter.isNotEmpty())
                    val partial = store.partialFile(file)
                    val checkpoint = AtomicFile(File(partial.path + ".json"))
                    var next = 0
                    var bytes = 0L
                    var sync: TextAudioSyncMap? = null
                    if (checkpoint.baseFile.exists() || File(checkpoint.baseFile.path + ".bak").exists()) {
                        val data = checkpoint.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
                        require(data.getString("manifest") == manifestId)
                        next = data.getInt("next")
                        bytes = data.getLong("bytes")
                        require(next in 0..chapter.size)
                        // The same atomic checkpoint commits both PCM length and timing rows.
                        sync = data.optJSONObject("syncMap")?.let { TextAudioSyncMap.parse(it) }?.also {
                            require(it.manifestId == manifestId && it.chapterId == file.chapterId)
                            it.validate(bytes, chapter.take(next).map { fragment -> fragment.id })
                        }
                    }
                    sync = sync?.copy(bookId = source.book.id, sourceSha256 = source.book.sourceSha256)
                    // Death between rename and public publication leaves a complete private WAV.
                    val privateFinal = store.finalFile(file)
                    if (!partial.exists() && privateFinal.isFile && next == chapter.size && privateFinal.length() == bytes + 44) {
                        check(privateFinal.renameTo(partial))
                    }
                    store.markFileState(sourceId, manifestId, file.fileId, "downloading", bytes)
                    ChapterWavWriter(partial, bytes).use { writer ->
                        sync?.let { require(it.sampleRate == writer.sampleRate) }
                        for (index in next until chapter.size) {
                            checkActive(sourceId, manifestId)
                            check(ruVoice.requireInstalled() == request.engineVersion &&
                                voices.revision() == request.settingsRevision) {
                                "RuVoice или его настройки изменились. Запустите сохранение заново."
                            }
                            val ticket = narration.enqueue(
                                NarrationSpec.create(
                                    chapter[index].text, request.voiceName,
                                    request.engineVersion, request.settingsRevision,
                                ),
                                NarrationPriority.SAVE_CHAPTER,
                            )
                            try {
                                val audio = ticket.await()
                                checkActive(sourceId, manifestId)
                                val start = writer.dataBytes
                                writer.append(audio.file)
                                // A legacy partial can finish without a map; do not invent missing boundaries.
                                if (index == 0 || sync != null) {
                                    sync = TextAudioSyncMap(manifestId, file.chapterId, writer.sampleRate,
                                        sync?.entries.orEmpty() + TextAudioSyncEntry(chapter[index].id, start, writer.dataBytes),
                                        bookId = source.book.id, sourceSha256 = source.book.sourceSha256)
                                        .validate(writer.dataBytes, chapter.take(index + 1).map { it.id })
                                }
                                val stream = checkpoint.startWrite()
                                try {
                                    stream.write(JSONObject().put("manifest", manifestId).put("next", index + 1)
                                        .put("bytes", writer.dataBytes).put("syncMap", sync?.json()).toString().toByteArray(Charsets.UTF_8))
                                    checkpoint.finishWrite(stream)
                                } catch (error: Throwable) { checkpoint.failWrite(stream); throw error }
                                store.markFileState(sourceId, manifestId, file.fileId, "downloading", writer.dataBytes + 44)
                            } finally { ticket.release() }
                        }
                        bytes = writer.dataBytes
                    }
                    checkActive(sourceId, manifestId)
                    require(bytes > 0) { "В главе нет звука." }
                    // Publication and deletion are fenced by DownloadStore's lifecycle mutex.
                    val final = store.finalFile(file)
                    sync?.validate(bytes, chapter.map { it.id })?.let { map ->
                        val sidecar = AtomicFile(File(final.path + ".sync.json"))
                        val output = sidecar.startWrite()
                        try {
                            output.write(map.json().toString().toByteArray(Charsets.UTF_8))
                            sidecar.finishWrite(output)
                        } catch (error: Throwable) { sidecar.failWrite(output); throw error }
                    }
                    if (sync == null) AtomicFile(File(final.path + ".sync.json")).delete()
                    check(partial.renameTo(final)) { "Не удалось завершить аудиоглаву." }
                    val durationSeconds = (PreviewWav.readExport(final).durationMs / 1_000L).coerceAtLeast(1)
                    store.publishCompletedFile(file, manifestId, durationSeconds = durationSeconds)
                    checkpoint.delete()
                    workingFile = null
                    setForeground(notification(sourceId))
                }
                store.recalculate(sourceId, manifestId)
                Result.success()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val current = store.book(sourceId)
                if (current == null || current.manifestId != manifestId || current.state == "paused" || current.deletedAtMs != null) {
                    Result.success()
                } else {
                    val message = error.message ?: "Не удалось сохранить озвучку."
                    workingFile?.let { store.markFileState(sourceId, manifestId, it.fileId, "failed", store.partialFile(it).length(), message) }
                    store.markBookState(sourceId, manifestId, "failed", message)
                    Result.failure()
                }
            } finally {
                withContext(NonCancellable) {
                    val current = store.book(sourceId)
                    if (current?.manifestId == manifestId && current.state == "downloading") {
                        store.markBookState(sourceId, manifestId, "queued")
                    }
                    store.markDownloadGenerationInactive(sourceId, manifestId)
                    runCatching { store.cleanupObsoleteManifestDirectories(sourceId) }
                }
            }
        }
    }

    private suspend fun checkActive(sourceId: String, manifestId: String) {
        currentCoroutineContext().ensureActive()
        val book = store.book(sourceId)
        if (book == null || book.manifestId != manifestId || book.deletedAtMs != null || book.state == "paused") {
            throw CancellationException("Подготовка остановлена.")
        }
    }

    private suspend fun notification(sourceId: String): ForegroundInfo {
        val book = checkNotNull(store.book(sourceId))
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("abred-text-audio", "Подготовка озвучки", NotificationManager.IMPORTANCE_LOW),
        )
        val notice = NotificationCompat.Builder(applicationContext, "abred-text-audio")
            .setSmallIcon(com.example.R.mipmap.ic_launcher).setContentTitle("Подготовка аудиокниги")
            .setContentText("${book.title} · ${book.completedFiles} из ${book.filesCount} глав")
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(book.filesCount, book.completedFiles, false).build()
        val id = 40_000 + (sourceId.hashCode() and 0x3fffffff) % 10_000
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id, notice)
    }
}
