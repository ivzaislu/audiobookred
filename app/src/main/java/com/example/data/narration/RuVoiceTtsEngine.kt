package com.example.data.narration

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** RuVoice is the only TTS backend; sessions are reused across sequential fragments. */
@Singleton
internal class RuVoiceTtsEngine @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        const val PACKAGE = "ru.kost.ruvoice"
        private const val INITIALIZATION_TIMEOUT_MS = 20_000L
        // Bound each *short* Android TTS request, not a whole book fragment.
        private const val SYNTHESIS_TIMEOUT_MS = 85_000L
        private const val CHUNK_ATTEMPTS = 2
    }

    @Suppress("DEPRECATION")
    fun installedVersion(): Long? = runCatching {
        val pkg = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(
            PACKAGE, PackageManager.PackageInfoFlags.of(0L)
        ) else context.packageManager.getPackageInfo(PACKAGE, 0)
        if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode
        else pkg.versionCode.toLong()
    }.getOrNull()

    fun requireInstalled(): Long = installedVersion()
        ?: throw IllegalStateException("RuVoice не установлен. Настройки → Чтение и озвучка → Установить RuVoice.")

    // One app-wide RuVoice connection for successive book fragments. Reconnecting
    // for every 1,000-character fragment made whole-book exports needlessly slow.
    // The mutex also prevents voice changes from affecting an active synthesis.
    private val engineMutex = Mutex()
    private var connected: TextToSpeech? = null
    private var connectedVersion: Long? = null

    private suspend fun dispose(tts: TextToSpeech) {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            tts.stop()
            tts.shutdown()
        }
    }

    private fun hasRuVoiceVoices(voices: Set<Voice>): Boolean = voices.any { voice ->
        voice.locale.language == "ru" && !voice.isNetworkConnectionRequired &&
            voice.name in setOf("xenia-ru", "aidar-ru", "baya-ru", "kseniya-ru", "eugene-ru")
    }

    private suspend fun <T> useEngine(block: suspend (TextToSpeech) -> T): T =
        engineMutex.withLock {
            val version = requireInstalled()
            var tts = connected
            if (tts != null && connectedVersion != version) {
                connected = null
                connectedVersion = null
                dispose(tts)
                tts = null
            }
            if (tts == null) {
                val initialized = CompletableDeferred<Int>()
                val created = withContext(Dispatchers.Main.immediate) {
                    TextToSpeech(context.applicationContext, { status ->
                        initialized.complete(status)
                    }, PACKAGE)
                }
                try {
                    val status = withTimeout(INITIALIZATION_TIMEOUT_MS) { initialized.await() }
                    // Android's requested engine can silently fall back; disallow that.
                    check(status == TextToSpeech.SUCCESS &&
                        hasRuVoiceVoices(created.voices.orEmpty())) {
                        "Android не подключился к RuVoice или его русские голоса недоступны. " +
                            "Откройте RuVoice и проверьте установку полной модели."
                    }
                    connected = created
                    connectedVersion = version
                    tts = created
                } catch (error: Throwable) {
                    dispose(created)
                    throw error
                }
            }
            val active = checkNotNull(tts)
            try {
                block(active)
            } catch (error: Throwable) {
                // Broken TTS binder, timeout or user cancellation: next request
                // gets a fresh service connection instead of reusing a bad one.
                connected = null
                connectedVersion = null
                dispose(active)
                throw error
            }
        }

    suspend fun voices(): List<Voice> = useEngine { tts ->
        (tts.voices ?: emptySet()).filter {
            it.locale.language == "ru" && !it.isNetworkConnectionRequired
        }.sortedBy { it.name }
    }

    /**
     * A reader fragment can have ~1,000 characters; a single request of that
     * size may hang RuVoice on low-memory phones. Export and live narration use
     * the same bounded synth and retain their existing WAV cache identities.
     */
    suspend fun synthesize(text: String, voiceName: String, output: File) {
        val pieces = RuVoiceTextChunks.split(text)
        if (pieces.size == 1) {
            synthesizePieceWithRetry(pieces.single(), voiceName, output)
            return
        }
        val parent = checkNotNull(output.parentFile) { "Недоступен каталог WAV-кеша." }
        try {
            ChapterWavWriter(output, 0).use { writer ->
                for (piece in pieces) {
                    currentCoroutineContext().ensureActive()
                    val temporary = File(parent, ".ruvoice-${UUID.randomUUID()}.wav")
                    try {
                        synthesizePieceWithRetry(piece, voiceName, temporary)
                        writer.append(temporary)
                    } finally {
                        temporary.delete()
                    }
                }
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
        // All chunks are validated before this WAV becomes visible to Media3.
        PreviewWav.read(output)
    }

    private suspend fun synthesizePieceWithRetry(text: String, voiceName: String, output: File) {
        for (attempt in 1..CHUNK_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            output.delete()
            try {
                synthesizeSingle(text, voiceName, output)
                return
            } catch (failure: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive() // User-initiated cancellation must propagate.
                if (attempt == CHUNK_ATTEMPTS) {
                    throw IOException(
                        "RuVoice не ответил за ${SYNTHESIS_TIMEOUT_MS / 1000} с на короткий фрагмент " +
                            "из ${text.length} символов (две попытки). Проверьте RuVoice или перезапустите озвучку.",
                        failure,
                    )
                }
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                if (attempt == CHUNK_ATTEMPTS) throw IOException(
                    "RuVoice дважды завершил запрос с ошибкой: ${failure.message}", failure,
                )
            }
        }
        throw IOException("Не удалось завершить короткий запрос RuVoice.")
    }

    /** One short request. useEngine reopens the engine after an error or timeout. */
    private suspend fun synthesizeSingle(text: String, voiceName: String, output: File) = useEngine { tts ->

        require(text.length <= TextToSpeech.getMaxSpeechInputLength()) {
            "Фрагмент слишком длинный для RuVoice."
        }
        // No Silero preprocessing: RuVoice owns punctuation, stress and normalization.
        val voices = tts.voices.orEmpty()
        val voice = if (voiceName.isNotBlank()) voices.firstOrNull { it.name == voiceName }
            ?: throw IllegalStateException("Выбранный голос RuVoice больше недоступен.") else null
        if (voice != null) {
            check(tts.setVoice(voice) == TextToSpeech.SUCCESS) { "RuVoice не принял выбранный голос." }
        } else {
            check(tts.setLanguage(Locale.forLanguageTag("ru-RU")) >= TextToSpeech.LANG_AVAILABLE) {
                "RuVoice не предоставил русский голос. Проверьте установленные голосовые модели."
            }
        }
        check(tts.setSpeechRate(1f) == TextToSpeech.SUCCESS) { "RuVoice не принял скорость." }
        val utterance = UUID.randomUUID().toString()
        val completed = CompletableDeferred<Unit>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                if (utteranceId == utterance) completed.complete(Unit)
            }
            @Deprecated("Android TTS callback")
            override fun onError(utteranceId: String) {
                if (utteranceId == utterance) completed.completeExceptionally(IOException("RuVoice не создал WAV."))
            }
            override fun onError(utteranceId: String, errorCode: Int) {
                if (utteranceId == utterance) completed.completeExceptionally(
                    IOException("Ошибка RuVoice при синтезе: $errorCode.")
                )
            }
            override fun onStop(utteranceId: String, interrupted: Boolean) {
                if (utteranceId == utterance) completed.completeExceptionally(
                    IOException("RuVoice неожиданно остановил синтез WAV.")
                )
            }
        })
        val result = withContext(Dispatchers.Main.immediate) {
            tts.synthesizeToFile(text, null, output, utterance)
        }
        if (result != TextToSpeech.SUCCESS) {
            throw IOException("RuVoice отклонил запрос синтеза WAV.")
        }
        withTimeout(SYNTHESIS_TIMEOUT_MS) { completed.await() }
        currentCoroutineContext().ensureActive()
        if (!output.isFile || output.length() <= 44L) {
            throw IOException("RuVoice завершил запрос, но не создал WAV.")
        }
        try {
            PreviewWav.read(output)
        } catch (invalid: IllegalArgumentException) {
            throw IOException("RuVoice вернул повреждённый WAV: ${invalid.message}", invalid)
        }
    }
}
