package com.example.data.narration

import java.io.File
import java.io.RandomAccessFile

internal data class PreviewWav(val durationMs: Long, val bytes: Long, val sampleRate: Int) {
    companion object {
        /** Per-utterance cache limit. Whole audiobook chapters can be much larger. */
        const val MAX_BYTES = 64L * 1024 * 1024
        /** Standard RIFF WAV's 32-bit chunk-length limit, including its 8-byte prefix. */
        const val MAX_EXPORT_BYTES = 0xffffffffL + 8L

        /** Check a synthesized fragment, bounded separately from completed chapters. */
        fun read(file: File): PreviewWav = readWithLimit(file, MAX_BYTES)

        /** Validate a complete chapter by streaming its headers, without loading PCM into RAM. */
        fun readExport(file: File): PreviewWav = readWithLimit(file, MAX_EXPORT_BYTES)

        private fun readWithLimit(file: File, maxBytes: Long): PreviewWav =
            RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            require(size in 44..maxBytes) {
                "Некорректный размер WAV-файла (${size / (1024 * 1024)} МБ)."
            }
            fun tag(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            fun u16(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
            fun u32(): Long = java.lang.Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            require(tag() == "RIFF") { "Неподдерживаемый аудиоформат." }
            val riffEnd = u32() + 8
            require(riffEnd == size && tag() == "WAVE") { "WAV-файл не завершён." }
            var rate = 0
            var bytesPerFrame = 0
            var dataBytes = 0L
            while (input.filePointer + 8 <= riffEnd) {
                val name = tag()
                val length = u32()
                val start = input.filePointer
                require(length <= riffEnd - start) { "WAV-файл повреждён." }
                when (name) {
                    "fmt " -> {
                        require(length >= 16)
                        val format = u16()
                        val channels = u16()
                        rate = u32().toInt()
                        val byteRate = u32()
                        bytesPerFrame = u16()
                        val bits = u16()
                        require((format == 1 && bits in listOf(8, 16, 24, 32)) || (format == 3 && bits == 32))
                        require(channels in 1..2 && rate in 8_000..96_000)
                        require(bytesPerFrame == channels * bits / 8 && byteRate == rate.toLong() * bytesPerFrame)
                    }
                    "data" -> dataBytes += length
                }
                input.seek(start + length + length % 2)
            }
            require(rate > 0 && bytesPerFrame > 0 && dataBytes > 0 && dataBytes % bytesPerFrame == 0L) {
                "WAV-файл не содержит звука."
            }
            PreviewWav(dataBytes * 1_000 / (rate.toLong() * bytesPerFrame), size, rate)
        }
    }
}
