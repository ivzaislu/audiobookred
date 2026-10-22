package com.example.data.narration

import java.io.File
import java.io.RandomAccessFile

/** Locate PCM16 mono samples in an ordinary RIFF WAV, even with metadata chunks. */
internal data class WavPcm16Payload(val sampleRate: Int, val offset: Long, val byteCount: Long)

internal object WavPcm16Reader {
    fun inspect(input: RandomAccessFile): WavPcm16Payload {
        val size = input.length()
        require(size in 44..PreviewWav.MAX_BYTES) { "Фрагмент RuVoice слишком большой или пустой." }
        input.seek(0)
        fun tag(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        fun u16() = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
        fun u32() = java.lang.Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        require(tag() == "RIFF" && u32() + 8L == size && tag() == "WAVE") {
            "RuVoice создал повреждённый WAV."
        }

        var rate = 0
        var payloadStart = -1L
        var payloadBytes = 0L
        var validFormat = false
        while (input.filePointer + 8 <= size) {
            val chunkName = tag()
            val length = u32()
            val begin = input.filePointer
            require(length <= size - begin) { "Повреждён размер блока WAV RuVoice." }
            when (chunkName) {
                "fmt " -> {
                    require(!validFormat && length >= 16) { "Дублирующий или неполный формат WAV." }
                    val format = u16()
                    val channels = u16()
                    val sampleRate = u32()
                    val byteRate = u32()
                    val align = u16()
                    val bits = u16()
                    require(format == 1 && channels == 1 && bits == 16 &&
                        sampleRate in 8_000L..96_000L && align == 2 &&
                        byteRate == sampleRate * 2) {
                        "Для сохранения книги требуется WAV PCM16 mono (RuVoice)."
                    }
                    rate = sampleRate.toInt()
                    validFormat = true
                }
                "data" -> {
                    require(payloadStart == -1L && length > 0 && length % 2L == 0L) {
                        "Некорректные аудиоданные WAV RuVoice."
                    }
                    payloadStart = begin
                    payloadBytes = length
                }
            }
            input.seek(begin + length + (length and 1L))
        }
        require(input.filePointer == size && validFormat && payloadStart >= 0L) {
            "RuVoice вернул WAV без корректного аудио."
        }
        return WavPcm16Payload(rate, payloadStart, payloadBytes)
    }
}

/** Stream-concatenate RuVoice PCM16 mono fragments, respecting sample rate and checkpoints. */
internal class ChapterWavWriter(private val file: File, committedBytes: Long) : AutoCloseable {
    private val output: RandomAccessFile
    var dataBytes: Long = committedBytes
        private set
    var sampleRate: Int = 24_000
        private set

    init {
        require(committedBytes >= 0 && committedBytes % 2L == 0L && committedBytes <= MAX_DATA)
        file.parentFile!!.mkdirs()
        require(committedBytes == 0L || (file.isFile && file.length() >= committedBytes + 44)) {
            "Незавершённая глава повреждена. Удалите её подготовку и запустите заново."
        }
        output = RandomAccessFile(file, "rw")
        if (committedBytes > 0L) {
            val header = ByteArray(44).also {
                output.seek(0)
                output.readFully(it)
            }
            fun tag(offset: Int, value: String) =
                header.copyOfRange(offset, offset + value.length).toString(Charsets.US_ASCII) == value
            fun u16(offset: Int) = (header[offset].toInt() and 255) or
                ((header[offset + 1].toInt() and 255) shl 8)
            fun u32(offset: Int) =
                (0..3).fold(0L) { acc, index ->
                    acc or ((header[offset + index].toLong() and 255L) shl (index * 8))
                }
            require(tag(0, "RIFF") && tag(8, "WAVEfmt ") && tag(36, "data") &&
                u16(20) == 1 && u16(22) == 1 && u16(32) == 2 && u16(34) == 16) {
                "Формат незавершённой главы не совпадает с WAV RuVoice."
            }
            sampleRate = u32(24).toInt()
            require(sampleRate in 8_000..96_000) { "Частота WAV не поддерживается." }
        }
        output.setLength(committedBytes + 44)
        checkpoint()
    }

    fun append(fragment: File) {
        RandomAccessFile(fragment, "r").use { input ->
            val pcm = WavPcm16Reader.inspect(input)
            if (dataBytes == 0L) sampleRate = pcm.sampleRate
            require(pcm.sampleRate == sampleRate) {
                "RuVoice изменил частоту звука между фрагментами. Запустите сохранение заново."
            }
            require(pcm.byteCount <= MAX_DATA - dataBytes) {
                "Глава слишком длинная для формата WAV. Сохраните её по частям."
            }
            check(file.parentFile!!.usableSpace > pcm.byteCount + 64L * 1024 * 1024) {
                "Недостаточно места для аудиоглавы."
            }

            output.seek(44 + dataBytes)
            input.seek(pcm.offset)
            val buffer = ByteArray(64 * 1024)
            var remaining = pcm.byteCount
            while (remaining > 0L) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(count > 0) { "Не удалось прочитать WAV-фрагмент RuVoice." }
                output.write(buffer, 0, count)
                remaining -= count
            }
            dataBytes += pcm.byteCount
            checkpoint()
        }
    }

    private fun checkpoint() {
        fun i32(value: Long) = output.writeInt(Integer.reverseBytes(value.toInt()))
        fun i16(value: Int) = output.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
        output.seek(0)
        output.writeBytes("RIFF"); i32(dataBytes + 36); output.writeBytes("WAVEfmt ")
        i32(16); i16(1); i16(1); i32(sampleRate.toLong()); i32(sampleRate * 2L); i16(2); i16(16)
        output.writeBytes("data"); i32(dataBytes)
        output.fd.sync()
    }

    override fun close() = output.close()

    companion object { const val MAX_DATA = 0xffff_ffffL - 36 }
}
