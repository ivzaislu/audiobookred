package com.example.data.narration

import android.content.Context
import android.util.AtomicFile
import android.net.Uri
import com.example.data.image.LocalBookCover
import com.example.data.textbook.EbookImportParser
import com.example.data.download.DownloadScheduler
import com.example.data.local.DownloadBookEntity
import com.example.data.local.DownloadStore
import com.example.data.model.DownloadFileDto
import com.example.data.model.DownloadManifestDto
import com.example.data.textbook.TextLibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val TEXT_AUDIO_SOURCE = "text_audio"
internal fun isTextAudioExport(sourceId: String) = sourceId.startsWith("$TEXT_AUDIO_SOURCE:")
internal fun textAudioSourceId(bookId: String, chapterId: String?) = "$TEXT_AUDIO_SOURCE:$bookId:${chapterId ?: "all"}"

internal fun ruVoiceExportManifestId(
    sourceId: String,
    voiceName: String,
    engineVersion: Long,
    settingsRevision: Long,
): String {
    require(isTextAudioExport(sourceId)) { "Некорректная текстовая аудиокнига." }
    require(engineVersion > 0L && settingsRevision >= 0L) { "RuVoice отсутствует или настройки повреждены." }
    val identity = "${NarrationSpec.ENGINE_ID}:ruvoice:$sourceId:$voiceName:$engineVersion:$settingsRevision"
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal data class TextAudioExportRequest(
    val bookId: String,
    val chapterId: String?,
    val voiceName: String,
    val engineVersion: Long,
    val settingsRevision: Long,
)

/** Immutable RuVoice voice/engine snapshot; storage and Media3 playback remain unchanged. */
@Singleton
internal class TextAudioExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val books: TextLibraryRepository,
    private val ruVoice: RuVoiceTtsEngine,
    private val voices: RuVoiceVoicePreferences,
    private val store: DownloadStore,
) {
    private val commands = Mutex()
    private val locks = mutableMapOf<String, Mutex>()
    internal fun workerLock(sourceId: String): Mutex = synchronized(locks) { locks.getOrPut(sourceId) { Mutex() } }
    private val folder get() = File(context.filesDir, "text-audio-exports")

    suspend fun start(bookId: String, chapterId: String?): DownloadBookEntity = commands.withLock {
        withContext(Dispatchers.IO) {
            require(store.canPublishCompletedFiles()) { "Разрешите доступ к хранилищу для сохранения аудио." }
            val source = books.narrationBook(bookId)
            val chapters = source.chapters.filter { chapterId == null || it.id == chapterId }
            require(chapters.isNotEmpty()) { "Глава не найдена." }
            val sourceId = textAudioSourceId(bookId, chapterId)
            // Repeated taps must not replace an active generation or change its voice snapshot.
            store.book(sourceId)?.takeIf { it.state in setOf("queued", "downloading") }?.let { return@withContext it }
            val version = ruVoice.requireInstalled()
            val voiceName = voices.selected()
            val settingsRevision = voices.revision()
            val key = ruVoiceExportManifestId(sourceId, voiceName, version, settingsRevision)
            val previousFiles = if (store.book(sourceId)?.manifestId == key) store.files(sourceId).associateBy { it.fileId } else emptyMap()
            check(folder.isDirectory || folder.mkdirs())
            val sourceCover = LocalBookCover.uri(context, source.metadata.coverUri)?.path?.let(::File)
            val savedCover = File(folder, "$bookId.cover")
            if (sourceCover?.isFile == true && sourceCover.length() <= EbookImportParser.MAX_COVER_BYTES && !savedCover.exists()) {
                val atomic = AtomicFile(savedCover)
                val output = atomic.startWrite()
                try { sourceCover.inputStream().use { it.copyTo(output) }; atomic.finishWrite(output) }
                catch (error: Throwable) { atomic.failWrite(output); throw error }
            }
            val descriptor = AtomicFile(File(folder, "$key.json"))
            val stream = descriptor.startWrite()
            try {
                val data = JSONObject().put("engine", NarrationSpec.ENGINE_ID).put("version", 2)
                    .put("bookId", bookId).put("chapterId", chapterId ?: "")
                    .put("voiceName", voiceName).put("engineVersion", version)
                    .put("settingsRevision", settingsRevision)
                    .put("author", source.metadata.author)
                stream.write(data.toString().toByteArray(Charsets.UTF_8)); descriptor.finishWrite(stream)
            } catch (error: Throwable) { descriptor.failWrite(stream); throw error }
            val manifest = DownloadManifestDto(
                manifestId = key, bookId = "audio_${bookId}_${chapterId?.substringAfterLast(':') ?: "all"}",
                bookSourceId = sourceId, sourceCode = TEXT_AUDIO_SOURCE, sourceName = "RuVoice · ${source.metadata.format}",
                coverUrl = if (savedCover.isFile) Uri.fromFile(savedCover).toString() else "",
                title = if (chapterId == null) source.book.title else "${source.book.title} · ${chapters.single().title}",
                filesCount = chapters.size,
                files = chapters.mapIndexed { index, chapter -> DownloadFileDto(
                    fileId = chapter.id, chapterId = chapter.id, chapterPosition = index, title = chapter.title,
                    durationSeconds = previousFiles[chapter.id]?.durationSeconds ?: 0,
                    filename = "${chapter.title.take(100)}.wav", mediaType = "audio/wav", downloadUrl = "",
                ) },
            )
            store.prepare(manifest, wifiOnly = false).also {
                DownloadScheduler.enqueue(context, sourceId, key, wifiOnly = false)
            }
        }
    }

    internal suspend fun request(sourceId: String, manifestId: String): TextAudioExportRequest = withContext(Dispatchers.IO) {
        require(manifestId.matches(Regex("[0-9a-f]{64}")))
        val file = AtomicFile(File(folder, "$manifestId.json"))
        val data = file.openRead().use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= 128 * 1024)
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
        require(data.getString("engine") == NarrationSpec.ENGINE_ID && data.optInt("version") == 2) {
            "Схема RuVoice изменилась. Запустите сохранение заново из книги."
        }
        val bookId = data.getString("bookId")
        val chapterId = data.getString("chapterId").ifEmpty { null }
        require(textAudioSourceId(bookId, chapterId) == sourceId)
        val voice = data.getString("voiceName")
        val version = data.getLong("engineVersion")
        val revision = data.getLong("settingsRevision")
        require(version > 0 && revision >= 0 &&
            ruVoiceExportManifestId(sourceId, voice, version, revision) == manifestId) {
            "Снимок голоса RuVoice повреждён."
        }
        TextAudioExportRequest(bookId, chapterId, voice, version, revision)
    }
}
