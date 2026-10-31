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
import com.example.data.settings.ReaderSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class TextAudioExportRequest(
    val bookId: String,
    val chapterId: String?,
    val voiceName: String,
    val engineVersion: Long,
    val settingsRevision: Long,
    val encoding: TextAudioExportEncoding = TextAudioExportEncoding(),
)

/** Immutable RuVoice voice/engine snapshot; storage and Media3 playback remain unchanged. */
@Singleton
internal class TextAudioExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val books: TextLibraryRepository,
    private val ruVoice: RuVoiceTtsEngine,
    private val voices: RuVoiceVoicePreferences,
    private val store: DownloadStore,
    private val readerSettings: ReaderSettingsStore,
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
            val voiceName = ruVoice.resolveVoiceName(voices.selected())
            val settingsRevision = voices.revision()
            val preferences = readerSettings.state.value
            val encoding = TextAudioExportEncoding(preferences.savedNarrationBitrateKbps.takeIf { preferences.compressSavedNarration })
            val key = ruVoiceExportManifestId(sourceId, voiceName, version, settingsRevision, encoding)
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
                val data = JSONObject().put("engine", NarrationSpec.ENGINE_ID).put("version", 3)
                    .put("bookId", bookId).put("chapterId", chapterId ?: "")
                    .put("voiceName", voiceName).put("engineVersion", version)
                    .put("settingsRevision", settingsRevision)
                    .put("format", encoding.extension).put("bitrateKbps", encoding.bitrateKbps ?: 0)
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
                    filename = "${chapter.title.take(100)}.${encoding.extension}", mediaType = encoding.mimeType, downloadUrl = "",
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
        val descriptorVersion = data.optInt("version")
        require(data.getString("engine") == NarrationSpec.ENGINE_ID && descriptorVersion in 2..3) {
            "Схема RuVoice изменилась. Запустите сохранение заново из книги."
        }
        val bookId = data.getString("bookId")
        val chapterId = data.getString("chapterId").ifEmpty { null }
        require(textAudioSourceId(bookId, chapterId) == sourceId)
        val voice = data.getString("voiceName")
        val version = data.getLong("engineVersion")
        val revision = data.getLong("settingsRevision")
        val encoding = if (descriptorVersion == 2) TextAudioExportEncoding() else when (data.getString("format")) {
            "wav" -> TextAudioExportEncoding().also { require(data.getInt("bitrateKbps") == 0) }
            "m4a" -> TextAudioExportEncoding(data.getInt("bitrateKbps"))
            else -> error("Неизвестный формат сохранённой озвучки.")
        }
        require(version > 0 && revision >= 0 &&
            ruVoiceExportManifestId(sourceId, voice, version, revision, encoding) == manifestId) {
            "Снимок голоса RuVoice повреждён."
        }
        TextAudioExportRequest(bookId, chapterId, voice, version, revision, encoding)
    }
}
