package com.example.data.narration

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChapterAacEncoderInstrumentedTest {
    @Test fun selectedBitratesProduceReadableM4aWithoutReplacingThePcmCheckpoint() = runBlocking {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "aac-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val wav = File(folder, "chapter.wav")
            val source = toneWav()
            wav.writeBytes(source)
            val sizes = mutableListOf<Long>()
            for (bitrate in listOf(32, 128)) {
                val result = File(folder, "chapter-$bitrate.m4a")
                val info = ChapterAacEncoder.encode(wav, result, bitrate) {}
                assertTrue(info.durationMs in 1_900L..2_500L)
                assertTrue(info.audioOffsetUs in 0L..500_000L)
                assertTrue(result.length() in 1 until wav.length())
                sizes += result.length()
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(result.absolutePath)
                    assertEquals(1, extractor.trackCount)
                    val format = extractor.getTrackFormat(0)
                    assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
                    assertEquals(24_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    extractor.selectTrack(0)
                    assertTrue(extractor.readSampleData(ByteBuffer.allocate(32 * 1024), 0) > 0)
                } finally { extractor.release() }
                assertArrayEquals(source, wav.readBytes())
            }
            assertTrue("Higher bitrate must produce a larger file", sizes[1] > sizes[0])
        } finally { folder.deleteRecursively() }
    }

    @Test fun cancellationKeepsSourceAndDeletesEncodedOutput() = runBlocking {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "aac-cancel-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val wav = File(folder, "chapter.wav")
            val result = File(folder, "chapter.m4a")
            val source = toneWav()
            wav.writeBytes(source)
            var calls = 0
            try {
                ChapterAacEncoder.encode(wav, result, 64) {
                    if (++calls >= 2) throw CancellationException("Pause")
                }
                fail("Compression must be cancelled")
            } catch (_: CancellationException) { }
            assertArrayEquals(source, wav.readBytes())
            assertFalse(result.exists())
        } finally { folder.deleteRecursively() }
    }

    private fun toneWav(): ByteArray {
        val samples = 48_000
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(24_000); putInt(48_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { index ->
                val value = if (index < 12_000) 0 else (kotlin.math.sin(index * 2.0 * Math.PI * 440 / 24_000) * 12_000).toInt()
                putShort(value.toShort())
            }
        }.array()
    }
}
