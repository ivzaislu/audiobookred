package com.example.data.narration

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChapterWavWriterTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun joinsPcmWithoutEmbeddingFragmentHeaders() {
        val first = fragment("first.wav", byteArrayOf(1, 0, 2, 0))
        val second = fragment("second.wav", byteArrayOf(3, 0, 4, 0))
        val chapter = folder.newFile("chapter.wav")
        ChapterWavWriter(chapter, 0).use { writer ->
            writer.append(first); writer.append(second)
            assertEquals(8L, writer.dataBytes)
        }
        assertEquals(52L, chapter.length())
        assertArrayEquals(byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0), chapter.readBytes().drop(44).toByteArray())
        assertEquals(24_000, PreviewWav.read(chapter).sampleRate)
    }

    @Test fun metadataChunkBeforeWavDataIsNotAppendedAsAudio() {
        val withMetadata = fragment(
            "ruvoice-metadata.wav",
            byteArrayOf(1, 0, 2, 0),
            extraChunk = true,
        )
        val ordinary = fragment("ordinary.wav", byteArrayOf(3, 0, 4, 0))
        RandomAccessFile(withMetadata, "r").use { input ->
            assertEquals(56L, WavPcm16Reader.inspect(input).offset)
        }
        val output = folder.newFile("clean-chapter.wav")
        ChapterWavWriter(output, 0).use { writer ->
            writer.append(withMetadata)
            writer.append(ordinary)
        }
        assertEquals(24_000, PreviewWav.read(output).sampleRate)
        assertArrayEquals(
            byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0),
            output.readBytes().drop(44).toByteArray(),
        )
        assertEquals(52L, output.length())
    }

    @Test fun ruVoice48kHzIsPreservedAndResumedCorrectly() {
        val first = fragment("ruvoice1.wav", byteArrayOf(1, 0, 2, 0), sampleRate = 48_000)
        val second = fragment("ruvoice2.wav", byteArrayOf(3, 0, 4, 0), sampleRate = 48_000)
        val output = folder.newFile("ruvoice-chapter.wav")
        ChapterWavWriter(output, 0).use { writer ->
            writer.append(first)
            assertEquals(48_000, writer.sampleRate)
            assertEquals(4L, writer.dataBytes)
        }
        ChapterWavWriter(output, 4).use { writer ->
            assertEquals(48_000, writer.sampleRate)
            writer.append(second)
        }
        assertEquals(48_000, PreviewWav.read(output).sampleRate)
        assertArrayEquals(byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0), output.readBytes().drop(44).toByteArray())
    }

    @Test fun differentRuVoiceFragmentRatesCannotBeSilentlyConcatenated() {
        val first = fragment("24k.wav", byteArrayOf(1, 0), sampleRate = 24_000)
        val second = fragment("48k.wav", byteArrayOf(2, 0), sampleRate = 48_000)
        val output = folder.newFile("mixed.wav")
        ChapterWavWriter(output, 0).use { writer ->
            writer.append(first)
            assertThrows(IllegalArgumentException::class.java) { writer.append(second) }
        }
        assertEquals(24_000, PreviewWav.read(output).sampleRate)
    }

    @Test fun resumeDiscardsBytesBeyondLastDurableCheckpoint() {
        val first = fragment("first.wav", byteArrayOf(1, 0, 2, 0))
        val second = fragment("second.wav", byteArrayOf(3, 0))
        val chapter = folder.newFile("chapter.wav")
        ChapterWavWriter(chapter, 0).use { it.append(first); it.append(second) }
        ChapterWavWriter(chapter, 4).use { it.append(second) }
        assertEquals(50L, chapter.length())
        assertArrayEquals(byteArrayOf(1, 0, 2, 0, 3, 0), chapter.readBytes().drop(44).toByteArray())
        assertThrows(IllegalArgumentException::class.java) { ChapterWavWriter(chapter, 100) }
    }

    private fun fragment(
        name: String,
        pcm: ByteArray,
        sampleRate: Int = 24_000,
        extraChunk: Boolean = false,
    ): File = folder.newFile(name).also { file ->
        RandomAccessFile(file, "rw").use { out ->
            fun i32(value: Int) = out.writeInt(Integer.reverseBytes(value))
            fun i16(value: Int) = out.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
            out.writeBytes("RIFF"); i32(36 + pcm.size + if (extraChunk) 12 else 0); out.writeBytes("WAVEfmt ")
            i32(16); i16(1); i16(1); i32(sampleRate); i32(sampleRate * 2); i16(2); i16(16)
            if (extraChunk) { out.writeBytes("JUNK"); i32(3); out.write(byteArrayOf(1, 2, 3, 0)) }
            out.writeBytes("data"); i32(pcm.size); out.write(pcm)
        }
    }
}
