package com.example.data.narration

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TextAudioCheckpointRecoveryTest {
    private fun wav(file: File, bytes: Int) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(bytes + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1.toShort()).putShort(1.toShort()).putInt(24_000).putInt(48_000)
            .putShort(2.toShort()).putShort(16.toShort()).put("data".toByteArray()).putInt(bytes)
        file.outputStream().use { it.write(header.array()); it.write(ByteArray(bytes)) }
    }

    @Test fun crashAfterAppendRestoresOnlyCommittedPcmAndMatchingTimingRows() {
        val root = Files.createTempDirectory("sync-checkpoint").toFile()
        try {
            val first = File(root, "first.wav").also { wav(it, 48_000) }
            val second = File(root, "second.wav").also { wav(it, 96_000) }
            val output = File(root, "chapter.wav.part")
            var sync = TextAudioSyncMap("manifest", "chapter", 24_000, emptyList())
            var checkpoint = ""
            ChapterWavWriter(output, 0).use { writer ->
                writer.append(first)
                sync = sync.copy(entries = listOf(TextAudioSyncEntry("first", 0, writer.dataBytes)))
                checkpoint = JSONObject().put("next", 1).put("bytes", writer.dataBytes).put("syncMap", sync.json()).toString()
                // PCM append is durable but the second checkpoint never committed.
                writer.append(second)
            }
            val saved = JSONObject(checkpoint)
            val committed = saved.getLong("bytes")
            sync = TextAudioSyncMap.parse(saved.getJSONObject("syncMap")).validate(committed, listOf("first"))
            ChapterWavWriter(output, committed).use { writer ->
                assertEquals(48_044L, output.length())
                val start = writer.dataBytes
                writer.append(second)
                sync = sync.copy(entries = sync.entries + TextAudioSyncEntry("second", start, writer.dataBytes))
                sync.validate(writer.dataBytes, listOf("first", "second"))
            }
            assertEquals(144_044L, output.length())
            assertEquals(3_000L, PreviewWav.readExport(output).durationMs)
            assertEquals(1_000L, sync.timeAt("second", 0.0))
            assertEquals("second" to 0.5, sync.textAt(2_000))
        } finally { root.deleteRecursively() }
    }
}
