package com.example.data.narration

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
internal class NarrationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ruVoice: RuVoiceTtsEngine,
    private val voicePreferences: RuVoiceVoicePreferences,
) {
    private val queue by lazy {
        NarrationQueue(NarrationTaskStore(File(context.filesDir, "narration-queue"), File(context.cacheDir, "narration-audio"))) { spec, output ->
            check(output.parentFile!!.usableSpace >= PreviewWav.MAX_BYTES) { "Для озвучки освободите хотя бы 64 МБ памяти." }
            check(ruVoice.requireInstalled() == spec.engineVersion) {
                "Версия RuVoice изменилась. Откройте текстовую книгу заново."
            }
            ruVoice.synthesize(spec.source, spec.voiceName, output)
        }
    }

    suspend fun enqueue(
        text: String,
        priority: NarrationPriority,
    ): NarrationTicket {
        val installed = ruVoice.requireInstalled()
        val selectedVoice = voicePreferences.selected()
        val spec = withContext(Dispatchers.Default) {
            NarrationSpec.create(text, selectedVoice, installed, voicePreferences.revision())
        }
        return queue.enqueue(spec, priority)
    }

    suspend fun listTasks(): List<NarrationTaskState> = queue.listTasks()
    /** No cached output or queued work can bypass the installed RuVoice requirement. */
    internal suspend fun enqueue(spec: NarrationSpec, priority: NarrationPriority): NarrationTicket {
        check(spec.engineVersion > 0L && spec.engineVersion == ruVoice.requireInstalled()) {
            "RuVoice отсутствует или обновлён. Откройте книгу заново."
        }
        return queue.enqueue(spec, priority)
    }
    suspend fun retry(key: String, priority: NarrationPriority): NarrationTicket {
        ruVoice.requireInstalled()
        return queue.retry(key, priority)
    }
}
