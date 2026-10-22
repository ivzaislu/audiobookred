package com.example.data.narration

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewWavTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun wave(extraChunk: Boolean = false): File {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun tag(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
            fun u16(value: Int) = output.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
            fun u32(value: Int) = output.writeInt(Integer.reverseBytes(value))
            tag("RIFF"); u32(36 + 48_000 + if (extraChunk) 12 else 0); tag("WAVE")
            tag("fmt "); u32(16); u16(1); u16(1); u32(24_000); u32(48_000); u16(2); u16(16)
            if (extraChunk) { tag("JUNK"); u32(3); output.write(byteArrayOf(1, 2, 3, 0)) }
            tag("data"); u32(48_000); output.write(ByteArray(48_000))
        }
        return temporary.newFile().apply { writeBytes(bytes.toByteArray()) }
    }

    @Test fun readsActualAudioDurationRatherThanTotalFileLength() {
        val file = wave(extraChunk = true)
        val info = PreviewWav.read(file)
        assertEquals(1_000L, info.durationMs)
        assertEquals(24_000, info.sampleRate)
        assertEquals(file.length(), info.bytes)
    }

    @Test fun refusesInterruptedSynthesisWithAnUnfinishedHeader() {
        val file = wave()
        val bytes = file.readBytes()
        bytes.fill(0, 4, 8)
        file.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { PreviewWav.read(file) }
    }

    @Test fun refusesTruncatedAudioAndMalformedFormats() {
        val truncated = wave().apply { writeBytes(readBytes().dropLast(2).toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { PreviewWav.read(truncated) }
        val badFormat = wave().apply {
            val bytes = readBytes(); bytes[32] = 0; writeBytes(bytes)
        }
        assertThrows(IllegalArgumentException::class.java) { PreviewWav.read(badFormat) }
    }

    @Test fun exportedChapterMayExceedTheFragmentLimitWithoutLoadingPcmIntoMemory() {
        val longWave = temporary.newFile("long-chapter.wav")
        // Sparse/extended 64+ MB WAV: only RIFF headers are actually read during inspection.
        val total = PreviewWav.MAX_BYTES + 1_024L
        java.io.RandomAccessFile(longWave, "rw").use { out ->
            fun u32(value: Long) = out.writeInt(Integer.reverseBytes(value.toInt()))
            fun u16(value: Int) = out.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
            out.setLength(total)
            out.seek(0)
            out.writeBytes("RIFF"); u32(total - 8); out.writeBytes("WAVEfmt ")
            u32(16); u16(1); u16(1); u32(24_000); u32(48_000); u16(2); u16(16)
            out.writeBytes("data"); u32(total - 44)
        }
        assertThrows(IllegalArgumentException::class.java) { PreviewWav.read(longWave) }
        val exported = PreviewWav.readExport(longWave)
        assertEquals(total, exported.bytes)
        assertEquals(24_000, exported.sampleRate)
        assertTrue(exported.durationMs > 1_000_000L)
    }

    @Test fun refusesAnEmptyFile() {
        assertThrows(IllegalArgumentException::class.java) { PreviewWav.read(temporary.newFile()) }
    }
}
