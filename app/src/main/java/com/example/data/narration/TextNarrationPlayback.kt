package com.example.data.narration

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.example.data.model.BookDetailDto
import com.example.data.model.ChapterDto
import com.example.data.model.PersonDto
import com.example.data.player.PlaybackResumeStore
import com.example.data.textbook.TextLibraryRepository
import com.example.domain.playback.PlaybackSpeedCheckpoint
import com.example.domain.playback.resolvePlaybackSpeed
import com.example.data.settings.PlayerSettingsStore
import com.example.domain.playback.PreparedPlayback
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

internal fun textNarrationContractKey(
    voiceName: String,
    engineVersion: Long,
    settingsRevision: Long = 0L,
): String {
    require(engineVersion > 0L) { "Для озвучки необходимо установить RuVoice." }
    require(settingsRevision >= 0L) { "Неверная ревизия настроек RuVoice." }
    // No legacy Silero branch: all new contracts belong to the external engine.
    val identity = "${NarrationSpec.ENGINE_ID}:ruvoice:$engineVersion:$voiceName:$settingsRevision"
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Text sources remain in their own library. Playback uses the existing service, controls and queue. */
@Singleton
class TextNarrationPlayback @Inject internal constructor(
    @ApplicationContext context: Context,
    private val books: TextLibraryRepository,
    private val narration: NarrationRepository,
    private val ruVoice: RuVoiceTtsEngine,
    private val voicePreferences: RuVoiceVoicePreferences,
    private val syncMaps: TextAudioSyncStore,
    private val playerSettings: PlayerSettingsStore,
) {
    internal val resumeStore = PlaybackResumeStore(context, "abred_text_audio_resume")
    private val folder = File(context.filesDir, "text-narration-contracts")
    private data class ContractSnapshot(val voiceName: String, val engineVersion: Long, val settingsRevision: Long)
    private val mutex = Mutex()
    @Volatile internal var activeContract: String? = null
    private val hash = Regex("[0-9a-f]{64}")

    private fun contract(voiceName: String, engineVersion: Long, settingsRevision: Long): String =
        textNarrationContractKey(voiceName, engineVersion, settingsRevision)

    private suspend fun saveContract(voiceName: String, engineVersion: Long, settingsRevision: Long): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(folder.isDirectory || folder.mkdirs()) { "Не удалось сохранить настройки озвучки." }
            val key = contract(voiceName, engineVersion, settingsRevision)
            val file = AtomicFile(File(folder, "$key.json"))
            val stream = file.startWrite()
            try {
                val data = JSONObject().put("version", 2).put("engine", NarrationSpec.ENGINE_ID)
                    .put("voiceName", voiceName).put("engineVersion", engineVersion)
                    .put("settingsRevision", settingsRevision)
                stream.write(data.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Throwable) { file.failWrite(stream); throw error }
            val lastContract = resumeStore.latestSnapshot()?.chapterId?.substringAfter('|', "")
            folder.listFiles().orEmpty().filter { it.name.endsWith(".json") && it.nameWithoutExtension !in setOf(key, activeContract, lastContract) }
                .sortedByDescending { it.lastModified() }.drop(61).forEach { AtomicFile(it).delete() }
            key
        }
    }

    private suspend fun snapshot(key: String): ContractSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(hash.matches(key)) { "Некорректная сессия озвучки." }
            val bytes = AtomicFile(File(folder, "$key.json")).openRead().use { input ->
                val buffer = ByteArray(128 * 1024 + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                require(count < buffer.size)
                buffer.copyOf(count)
            }
            val data = JSONObject(bytes.toString(Charsets.UTF_8))
            require(data.getInt("version") == 2 && data.getString("engine") == NarrationSpec.ENGINE_ID) { "Настройки голоса изменились. Откройте книгу заново." }
            val voice = data.getString("voiceName")
            val version = data.getLong("engineVersion")
            val settingsRevision = data.optLong("settingsRevision", 0L)
            require(version > 0L && contract(voice, version, settingsRevision) == key) {
                "Снимок голоса RuVoice повреждён."
            }
            ContractSnapshot(voice, version, settingsRevision)
        }
    }

    internal suspend fun book(bookId: String, existingContract: String? = null): BookDetailDto = withContext(Dispatchers.Default) {
        val source = books.narrationBook(bookId)
        require(source.fragments.isNotEmpty()) { "В книге нет текста." }
        val installedVersion = ruVoice.requireInstalled()
        val voiceName = voicePreferences.selected()
        val settingsRevision = voicePreferences.revision()
        val key = if (existingContract != null) {
            val previous = snapshot(existingContract)
            require(previous.engineVersion == installedVersion) { "RuVoice обновился. Откройте книгу заново." }
            existingContract
        } else saveContract(voiceName, installedVersion, settingsRevision)
        val chapters = source.chapters.associateBy { it.id }
        BookDetailDto(
            id = bookId, title = source.book.title, narrators = listOf(PersonDto("tts:ruvoice", "RuVoice TTS")), selectedSource = TEXT_NARRATION_SOURCE,
            coverUrl = source.metadata.coverUri,
            authors = if (source.metadata.author.isBlank()) emptyList() else listOf(PersonDto("local:author", source.metadata.author)),
            primarySource = TEXT_NARRATION_SOURCE, sourceCodes = listOf(TEXT_NARRATION_SOURCE),
            description = "Локальная озвучка ${source.metadata.format} · RuVoice",
            chapters = source.fragments.mapIndexed { index, fragment ->
                ChapterDto(
                    id = "${fragment.id}|$key", position = index,
                    title = chapters.getValue(fragment.chapterId).title,
                    streamUrl = Uri.Builder().scheme(TEXT_NARRATION_SCHEME).authority("fragment")
                        .appendPath(key).appendPath(bookId).appendPath(fragment.id).build().toString(),
                )
            },
        )
    }

    /**
     * Find the text corresponding to Media3's current playback position.
     * Live synthesis knows the actual fragment; a previously exported WAV only
     * stores chapter-level timings, so its location is an explicit estimate.
     */
    internal suspend fun readingPosition(
        audioBook: BookDetailDto?,
        chapterIndex: Int,
        positionMs: Long,
        durationMs: Long,
    ): TextAudioReadingPosition? {
        val book = audioBook ?: return null
        val chapter = book.chapters.getOrNull(chapterIndex) ?: return null
        val sourceId = book.selectedBookSourceId
        val bookId = when (book.selectedSource) {
            TEXT_NARRATION_SOURCE -> book.id.takeIf { it.matches(Regex("txt_[0-9a-f]{64}")) }
            TEXT_AUDIO_SOURCE -> Regex("^text_audio:(txt_[0-9a-f]{64}):").find(sourceId)?.groupValues?.get(1)
            else -> null
        } ?: return null

        val text = books.narrationBook(bookId)
        return if (book.selectedSource == TEXT_NARRATION_SOURCE) {
            val id = textFragmentId(chapter.id)
            val fragment = text.fragments.firstOrNull { it.id == id } ?: return null
            val ratio = if (durationMs > 0L) {
                (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
            } else 0.0
            TextAudioReadingPosition(bookId, fragment.chapterId, fragment.id, ratio, true)
        } else {
            val fragments = text.fragments.filter { it.chapterId == chapter.id }
            val duration = durationMs.takeIf { it > 0L }
                ?: (chapter.durationSeconds.coerceAtLeast(0L) * 1000L)
            val map = syncMaps.read(sourceId, chapter.id, fragments.map { it.id }, bookId, text.book.sourceSha256)
            val (id, ratio) = map?.textAt(positionMs) ?: approximateAudioFragment(fragments, positionMs, duration) ?: return null
            // Even with a map, the within-fragment fraction is an estimate.
            TextAudioReadingPosition(bookId, chapter.id, id, ratio, ratio > 0.0 || map == null)
        }
    }

    internal suspend fun savedAudioTime(sourceId: String, chapterId: String, fragmentId: String, fraction: Double, durationMs: Long): Long {
        val bookId = Regex("^text_audio:(txt_[0-9a-f]{64}):").find(sourceId)?.groupValues?.get(1)
            ?: error("Текстовая книга не найдена.")
        val text = books.narrationBook(bookId)
        val fragments = text.fragments.filter { it.chapterId == chapterId }
        val map = syncMaps.read(sourceId, chapterId, fragments.map { it.id }, bookId, text.book.sourceSha256)
        return map?.timeAt(fragmentId, fraction)
            ?: approximateTextAudioTime(fragments, fragmentId, fraction, durationMs)
            ?: error("Фрагмент в аудиоглаве не найден.")
    }

    internal fun snapshot(book: BookDetailDto): PlaybackResumeStore.Snapshot? =
        normalizeTextNarrationResume(book.chapters.map { it.id }, resumeStore.get(book.id, TEXT_NARRATION_SOURCE))

    /** The reader shares the player's default and remember-book-speed policy. */
    internal fun playbackSpeed(book: BookDetailDto): Float {
        val settings = playerSettings.state.value
        val saved = snapshot(book)
        return resolvePlaybackSpeed(
            rememberBookSpeed = settings.rememberBookSpeed,
            defaultSpeed = settings.defaultSpeed,
            chapterIds = book.chapters.mapTo(mutableSetOf()) { it.id },
            sourceVariantCount = book.sourceVariants.size,
            persisted = null,
            immediate = saved?.let { PlaybackSpeedCheckpoint(it.chapterId, it.speed, preferOverPersisted = true) },
        )
    }

    internal suspend fun prepare(bookId: String, fragmentId: String? = null, useSavedContract: Boolean = true): PreparedPlayback {
        val previousContract = if (fragmentId == null && useSavedContract) resumeStore.get(bookId, TEXT_NARRATION_SOURCE)
            ?.chapterId?.substringAfter('|', "")?.takeIf { hash.matches(it) } else null
        val book = book(bookId, previousContract)
        val saved = snapshot(book)
        val index = if (fragmentId != null) book.chapters.indexOfFirst { textFragmentId(it.id) == fragmentId }.also {
            require(it >= 0) { "Фрагмент больше не найден." }
        } else saved?.chapterIndex ?: 0
        return PreparedPlayback(book, index, if (fragmentId == null) saved?.positionMs ?: 0 else 0, playbackSpeed(book))
    }

    internal suspend fun prepareAt(bookId: String, fragmentId: String, fraction: Double): PreparedPlayback {
        val prepared = prepare(bookId, fragmentId)
        if (fraction <= 0.0) return prepared
        val chapter = prepared.book.chapters[prepared.chapterIndex]
        val ticket = acquire(Uri.parse(chapter.streamUrl), NarrationPriority.PLAYBACK)
        return try {
            val audio = ticket.await()
            prepared.copy(positionMs = (audio.wav.durationMs * fraction.coerceIn(0.0, 1.0)).toLong())
        } finally { ticket.release() }
    }

    internal suspend fun acquire(uri: Uri, priority: NarrationPriority): NarrationTicket {
        require(uri.scheme == TEXT_NARRATION_SCHEME && uri.authority == "fragment" && uri.query == null && uri.fragment == null)
        val parts = uri.pathSegments
        require(parts.size == 3 && hash.matches(parts[0]) && parts[1].matches(Regex("txt_[0-9a-f]{64}")))
        val fragment = checkNotNull(books.narrationFragment(parts[1], parts[2])) { "Текстовый фрагмент удалён." }
        val settings = snapshot(parts[0])
        require(ruVoice.requireInstalled() == settings.engineVersion) {
            "RuVoice был обновлён. Откройте текстовую книгу заново."
        }
        return narration.enqueue(
            NarrationSpec.create(
                fragment.text, settings.voiceName, settings.engineVersion, settings.settingsRevision,
            ),
            priority,
        )
    }
}
