package com.example.data.narration

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

internal class TextNarrationReadException(message: String, cause: Throwable) : IOException(message, cause)

/** Blocks only Media3's loader thread until a complete fragment is available; seeking uses FileDataSource. */
@OptIn(UnstableApi::class)
internal class TextNarrationDataSource(
    private val narration: TextNarrationPlayback,
    private val fallback: DataSource,
) : DataSource {
    private val gate = Any()
    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null
    private var ticket: NarrationTicket? = null
    private var opening: Job? = null
    private var epoch = 0L
    private var uri: Uri? = null

    override fun addTransferListener(listener: TransferListener) { listeners += listener }

    override fun open(dataSpec: DataSpec): Long {
        val job = Job()
        val request = synchronized(gate) { epoch++; opening = job; epoch }
        var lease: NarrationTicket? = null
        var source: DataSource? = null
        try {
            val actual = if (dataSpec.uri.scheme == TEXT_NARRATION_SCHEME) {
                val result = runBlocking(job) {
                    val queued = narration.acquire(dataSpec.uri, NarrationPriority.PLAYBACK)
                    lease = queued
                    queued.await()
                }
                dataSpec.withUri(Uri.fromFile(result.file))
            } else dataSpec
            source = if (dataSpec.uri.scheme == TEXT_NARRATION_SCHEME) FileDataSource() else fallback
            listeners.forEach(source::addTransferListener)
            val length = source.open(actual)
            synchronized(gate) {
                check(epoch == request && !job.isCancelled) { "Запрос аудио отменён." }
                delegate = source; ticket = lease; opening = null; uri = dataSpec.uri
            }
            return length
        } catch (error: Exception) {
            runCatching { source?.close() }
            lease?.release()
            if (dataSpec.uri.scheme != TEXT_NARRATION_SCHEME) throw error
            throw TextNarrationReadException(error.message ?: "Не удалось подготовить аудиофрагмент.", error)
        } finally {
            job.complete()
            synchronized(gate) { if (epoch == request) opening = null }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (synchronized(gate) { delegate } ?: throw IOException("Поток закрыт.")).read(buffer, offset, length)
    override fun getUri(): Uri? = synchronized(gate) { if (delegate === fallback) fallback.uri else uri }
    override fun getResponseHeaders(): Map<String, List<String>> = synchronized(gate) { delegate }?.responseHeaders.orEmpty()
    override fun close() {
        val resources = synchronized(gate) {
            epoch++
            Triple(opening, delegate, ticket).also { opening = null; delegate = null; ticket = null; uri = null }
        }
        resources.first?.cancel()
        try { resources.second?.close() } finally { resources.third?.release() }
    }
}
