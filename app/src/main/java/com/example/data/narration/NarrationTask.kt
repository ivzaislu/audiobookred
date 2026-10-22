package com.example.data.narration

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class NarrationPriority(val rank: Int) { PREFETCH(0), SAVE_CHAPTER(1), PLAYBACK(2), PREVIEW(3) }
internal enum class NarrationStatus { QUEUED, GENERATING, READY, FAILED, CANCELLED, INTERRUPTED }

/** Immutable RuVoice request: raw text, voice and external engine/settings versions. */
internal data class NarrationSpec private constructor(
    val source: String,
    val voiceName: String,
    val engineVersion: Long,
    val settingsRevision: Long,
    val key: String,
) {
    companion object {
        const val ENGINE_ID = "android-tts:ru.kost.ruvoice:external-engine-wav:v1"

        fun create(
            source: String,
            voiceName: String = "",
            engineVersion: Long = 0L,
            settingsRevision: Long = 0L,
        ): NarrationSpec {
            val text = NarrationInput.prepare(source)
            // RuVoice does its own pronunciation, punctuation and phonetic normalization.
            val identity = "$ENGINE_ID:$engineVersion:$voiceName:$settingsRevision:$text"
            val key = MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return NarrationSpec(text, voiceName, engineVersion, settingsRevision, key)
        }
    }
}

internal data class NarrationTaskState(
    val key: String,
    val status: NarrationStatus,
    val priority: NarrationPriority,
    val message: String? = null,
)

internal data class NarrationAudio(val file: File, val wav: PreviewWav, val cacheHit: Boolean)

/** Retain ticket until playback/export finishes; release unpins a completed cached WAV. */
internal class NarrationTicket internal constructor(
    internal val id: String,
    internal val mutableState: MutableStateFlow<NarrationTaskState>,
    internal val result: CompletableDeferred<NarrationAudio>,
    private val onRelease: (String) -> Unit,
) {
    val state = mutableState.asStateFlow()
    suspend fun await(): NarrationAudio = result.await()
    fun release() = onRelease(id)
}
