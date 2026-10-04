package com.example.data.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookSilenceProcessorTest {
    @Test
    fun audiobookProfileShortensLongLowLevelNoiseFloor() {
        val sampleRate = 48_000
        val seconds = 3
        val inputFrames = sampleRate * seconds
        val processor = SilenceSkippingAudioProcessor(
            AudiobookRenderersFactory.MINIMUM_SILENCE_DURATION_US,
            AudiobookRenderersFactory.SILENCE_RETENTION_RATIO,
            AudiobookRenderersFactory.MAX_SILENCE_TO_KEEP_DURATION_US,
            AudiobookRenderersFactory.MIN_VOLUME_TO_KEEP_PERCENTAGE,
            AudiobookRenderersFactory.SILENCE_THRESHOLD_LEVEL,
        )
        processor.setEnabled(true)
        processor.configure(
            AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_16BIT)
        )
        processor.flush()

        // 1500 is deliberately above Media3's default silence threshold (1024), but below our
        // spoken-word threshold. This models an audiobook pause with a persistent noise floor.
        val input = ByteBuffer.allocateDirect(inputFrames * 2).order(ByteOrder.nativeOrder())
        repeat(inputFrames) { input.putShort(1500) }
        input.flip()

        var outputBytes = 0
        while (input.hasRemaining()) {
            processor.queueInput(input)
            outputBytes += drainOutput(processor)
        }
        processor.queueEndOfStream()
        while (!processor.isEnded) {
            outputBytes += drainOutput(processor)
        }

        val outputFrames = outputBytes / 2
        // Three seconds of noisy "silence" should be reduced well below one second by our
        // retention/max-silence profile.
        assertTrue(
            "Expected long noisy pause to be shortened, got $outputFrames of $inputFrames frames",
            outputFrames < sampleRate,
        )
    }

    private fun drainOutput(processor: AudioProcessor): Int {
        var bytes = 0
        while (true) {
            val output = processor.output
            if (!output.hasRemaining()) return bytes
            bytes += output.remaining()
            output.position(output.limit())
        }
    }
}
