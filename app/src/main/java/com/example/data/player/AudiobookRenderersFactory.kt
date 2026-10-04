package com.example.data.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

/**
 * Audio renderer tuned for spoken-word recordings.
 *
 * Media3's default silence detector uses a relatively low PCM threshold. Real audiobook
 * recordings often keep a small noise floor during pauses, so a pause can sound silent to a
 * listener while remaining above the default detector threshold. This profile raises that
 * threshold while only shortening pauses that are already long enough to be intentional gaps.
 */
@OptIn(UnstableApi::class)
internal class AudiobookRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val silenceProcessor = SilenceSkippingAudioProcessor(
            MINIMUM_SILENCE_DURATION_US,
            SILENCE_RETENTION_RATIO,
            MAX_SILENCE_TO_KEEP_DURATION_US,
            MIN_VOLUME_TO_KEEP_PERCENTAGE,
            SILENCE_THRESHOLD_LEVEL,
        )
        val processorChain = DefaultAudioSink.DefaultAudioProcessorChain(
            emptyArray<AudioProcessor>(),
            silenceProcessor,
            SonicAudioProcessor(),
        )

        return DefaultAudioSink.Builder(context)
            // The silence processor operates on PCM. Keep float output disabled so spoken-word
            // playback consistently goes through the processor chain when decoding normal files.
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessorChain(processorChain)
            .build()
    }

    internal companion object {
        // Ignore normal conversational pauses; only shorten clearly long gaps.
        const val MINIMUM_SILENCE_DURATION_US = 700_000L
        const val SILENCE_RETENTION_RATIO = 0.20f
        const val MAX_SILENCE_TO_KEEP_DURATION_US = 600_000L
        const val MIN_VOLUME_TO_KEEP_PERCENTAGE = 10

        // Media3 default is 1024. A higher threshold also recognizes low-level room/noise-floor
        // content as silence without being aggressive enough to classify normal speech peaks.
        const val SILENCE_THRESHOLD_LEVEL: Short = 2048
    }
}
