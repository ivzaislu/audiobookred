package com.example.data.narration

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.data.settings.NARRATION_BITRATES_KBPS
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class EncodedChapterInfo(val durationMs: Long, val audioOffsetUs: Long)

/** PCM checkpoints remain WAV; only a finished chapter is encoded and published as M4A. */
internal object ChapterAacEncoder {
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val TIMEOUT_US = 10_000L
    private const val STALL_NS = 30_000_000_000L

    suspend fun encode(
        source: File,
        destination: File,
        bitrateKbps: Int,
        checkpoint: suspend () -> Unit,
    ): EncodedChapterInfo {
        require(source.canonicalFile != destination.canonicalFile)
        require(bitrateKbps in NARRATION_BITRATES_KBPS)
        checkpoint()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        var muxerStopped = false
        var success = false
        var offsetUs = 0L
        try {
            check(!destination.exists() || destination.delete()) { "Не удалось очистить незавершённый M4A." }
            RandomAccessFile(source, "r").use { input ->
                val pcm = WavPcm16Reader.inspect(input, PreviewWav.MAX_EXPORT_BYTES)
                input.seek(pcm.offset)
                val format = MediaFormat.createAudioFormat(MIME, pcm.sampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
                }
                val encoder = MediaCodec.createEncoderByType(MIME).also { codec = it }
                val capabilities = encoder.codecInfo.getCapabilitiesForType(MIME)
                require(capabilities.isFormatSupported(format)) {
                    "Устройство не поддерживает AAC с выбранной частотой и битрейтом. Выключите сжатие или выберите другой битрейт."
                }
                if (capabilities.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                    format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
                codecStarted = true
                val writer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
                val info = MediaCodec.BufferInfo()
                val buffer = ByteArray(32 * 1024)
                var consumed = 0L
                var inputEnded = false
                var outputEnded = false
                var track = -1
                var firstPts: Long? = null
                var declaredDelayUs = 0L
                var written = 0L
                var lastProgress = System.nanoTime()
                var lastCheckpoint = lastProgress
                var lastPts = -1L
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    val now = System.nanoTime()
                    check(now - lastProgress < STALL_NS) { "Кодировщик AAC перестал отвечать. Повторите сохранение." }
                    if (now - lastCheckpoint >= 500_000_000L) {
                        checkpoint()
                        lastCheckpoint = now
                    }
                    if (!inputEnded) {
                        val index = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val target = checkNotNull(encoder.getInputBuffer(index))
                            target.clear()
                            val pts = consumed / 2L * 1_000_000L / pcm.sampleRate
                            if (consumed == pcm.byteCount) {
                                encoder.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                val count = minOf(buffer.size.toLong(), target.remaining().toLong(), pcm.byteCount - consumed).toInt() and -2
                                check(count > 0) { "Недостаточный входной буфер AAC." }
                                input.readFully(buffer, 0, count)
                                target.put(buffer, 0, count)
                                encoder.queueInputBuffer(index, 0, count, pts, 0)
                                consumed += count
                            }
                            lastProgress = System.nanoTime()
                        }
                    }
                    when (val index = encoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted)
                            val output = encoder.outputFormat
                            if (output.containsKey("encoder-delay")) {
                                declaredDelayUs = output.getInteger("encoder-delay").toLong().coerceAtLeast(0L) * 1_000_000L / pcm.sampleRate
                            }
                            // Keep priming audible and account for it in the sidecar. Do not
                            // also pass gapless trim metadata that a player could apply twice.
                            val audioTrack = MediaFormat.createAudioFormat(MIME, pcm.sampleRate, 1).apply {
                                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                                setByteBuffer("csd-0", checkNotNull(output.getByteBuffer("csd-0")).duplicate())
                            }
                            track = writer.addTrack(audioTrack)
                            writer.start()
                            muxerStarted = true
                            lastProgress = System.nanoTime()
                        }
                        else -> if (index >= 0) {
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    check(muxerStarted)
                                    val encoded = checkNotNull(encoder.getOutputBuffer(index))
                                    val first = firstPts ?: info.presentationTimeUs.also {
                                        firstPts = it
                                        offsetUs = maxOf(0L, -it, declaredDelayUs)
                                    }
                                    info.presentationTimeUs -= first
                                    check(info.presentationTimeUs >= lastPts) { "Некорректные временные метки AAC." }
                                    lastPts = info.presentationTimeUs
                                    encoded.position(info.offset)
                                    encoded.limit(info.offset + info.size)
                                    writer.writeSampleData(track, encoded, info)
                                    written += info.size
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally { encoder.releaseOutputBuffer(index, false) }
                            lastProgress = System.nanoTime()
                        }
                    }
                }
                check(consumed == pcm.byteCount && written > 0 && muxerStarted)
                writer.stop()
                muxerStopped = true
                writer.release()
                muxer = null
                checkpoint()
                val durationMs = durationMs(destination)
                val expectedMs = pcm.byteCount / 2L * 1_000L / pcm.sampleRate + offsetUs / 1_000L
                check(durationMs > 0 && kotlin.math.abs(durationMs - expectedMs) <= 1_500L) {
                    "Сжатая глава имеет неверную длительность. Исходная озвучка сохранена для повторной попытки."
                }
                success = true
                return EncodedChapterInfo(durationMs, offsetUs)
            }
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted && !muxerStopped) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!success) destination.delete()
        }
    }

    fun durationMs(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME) == MIME }
                ?: error("В M4A отсутствует дорожка AAC.")
            return format.getLong(MediaFormat.KEY_DURATION) / 1_000L
        } finally { extractor.release() }
    }
}
