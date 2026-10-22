package com.example.data.narration

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Pins the current fragment and prepares at most two following fragments, in playback order. */
@OptIn(UnstableApi::class)
internal class TextNarrationPrefetch(
    private val player: Player,
    private val narration: TextNarrationPlayback,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var identity: String? = null

    fun update() {
        val extras = player.currentMediaItem?.mediaMetadata?.extras
        val contract = extras?.getString(EXTRA_TEXT_CONTRACT)
        narration.activeContract = contract
        val key = if (contract != null && player.playWhenReady && player.playerError == null) player.currentMediaItem?.mediaId else null
        if (key == identity) return
        identity = key
        job?.cancel()
        if (key == null) return
        val index = player.currentMediaItemIndex
        val uris = (index..minOf(index + 2, player.mediaItemCount - 1)).mapNotNull {
            player.getMediaItemAt(it).localConfiguration?.uri
        }
        job = scope.launch {
            val leases = mutableListOf<NarrationTicket>()
            try {
                uris.forEachIndexed { position, uri ->
                    val lease = narration.acquire(uri, if (position == 0) NarrationPriority.PLAYBACK else NarrationPriority.PREFETCH)
                    leases += lease
                }
                leases.forEach { it.await() }
                // Keep output pinned even after Media3 has buffered and closed its file reader.
                kotlinx.coroutines.awaitCancellation()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                // The foreground DataSource reports errors and allows retry; speculative work stays silent.
            } finally { leases.forEach(NarrationTicket::release) }
        }
    }

    fun close() { job?.cancel(); narration.activeContract = null }
}
