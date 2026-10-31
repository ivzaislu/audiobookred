package com.example.data.narration

import com.example.data.settings.NARRATION_BITRATES_KBPS
import java.security.MessageDigest

internal const val TEXT_AUDIO_SOURCE = "text_audio"
internal fun isTextAudioExport(sourceId: String) = sourceId.startsWith("$TEXT_AUDIO_SOURCE:")
internal fun textAudioSourceId(bookId: String, chapterId: String?) = "$TEXT_AUDIO_SOURCE:$bookId:${chapterId ?: "all"}"

/** Null keeps the original WAV contract, including manifests from older versions. */
internal data class TextAudioExportEncoding(val bitrateKbps: Int? = null) {
    init { require(bitrateKbps == null || bitrateKbps in NARRATION_BITRATES_KBPS) }
    val compressed: Boolean get() = bitrateKbps != null
    val extension: String get() = if (compressed) "m4a" else "wav"
    val mimeType: String get() = if (compressed) "audio/mp4" else "audio/wav"
}

internal fun ruVoiceExportManifestId(
    sourceId: String,
    voiceName: String,
    engineVersion: Long,
    settingsRevision: Long,
    encoding: TextAudioExportEncoding = TextAudioExportEncoding(),
): String {
    require(isTextAudioExport(sourceId)) { "Некорректная текстовая аудиокнига." }
    require(engineVersion > 0L && settingsRevision >= 0L) { "RuVoice отсутствует или настройки повреждены." }
    val identity = "${NarrationSpec.ENGINE_ID}:ruvoice:$sourceId:$voiceName:$engineVersion:$settingsRevision" +
        (encoding.bitrateKbps?.let { ":aac-lc-m4a:v1:$it" } ?: "")
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
