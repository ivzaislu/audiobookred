package com.example.data.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.example.data.api.ApiClient
import java.io.IOException
import java.util.LinkedHashMap
import kotlin.math.min

/**
 * Streams redirectto.cc MP3s using bounded byte ranges instead of one full-body GET.
 *
 * Captured browser sessions from Baza-Knig and MY-AUDIOBOOKS show the rotating
 * *.redirectto.cc CDN answering open-ended Range requests in 1 MiB 206 chunks.
 * Some Android devices become silent while Media3 keeps advancing when the same
 * remote MP3 is delivered as one large HTTP 200 response. This source keeps the
 * normal Media3 contract but stitches the CDN's observed byte-range protocol
 * into one continuous stream for the extractor.
 *
 * Provider-specific Referer hints are injected from the MediaItem cache key before
 * OkHttp opens the request, so the shared CDN can apply the correct hotlink policy.
 */
@OptIn(UnstableApi::class)
internal class RedirectToChunkedDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val rutrackerUpstreamFactory: DataSource.Factory? = null,
    private val extraHeadersForSource: (String) -> Map<String, String> = { emptyMap() },
) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()

    private var upstream: DataSource? = null
    private var originalSpec: DataSpec? = null
    private var currentPosition = 0L
    private var requestedEndExclusive = LENGTH_UNKNOWN
    private var resourceLength = LENGTH_UNKNOWN
    private var currentChunkStart = 0L
    private var chunked = false
    private var opened = false
    private var lastUri: Uri? = null
    private var lastHeaders: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        upstream?.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        close()
        val effectiveSpec = withStandaloneProviderHeaders(
            dataSpec = dataSpec,
            extraHeadersForSource = extraHeadersForSource,
        )
        originalSpec = effectiveSpec
        currentPosition = effectiveSpec.position.coerceAtLeast(0L)
        requestedEndExclusive = requestedEndExclusive(effectiveSpec.position, effectiveSpec.length)
        chunked = shouldChunk(effectiveSpec.uri)

        if (!chunked) {
            val sourceCode = effectiveSpec.key?.let(::playbackProviderSourceFromCacheKey)
            val maxAttempts = if (sourceCode.equals("rutracker", ignoreCase = true)) {
                RUTRACKER_OPEN_ATTEMPTS
            } else {
                1
            }

            var lastError: IOException? = null
            repeat(maxAttempts) { attemptIndex ->
                val source = newUpstream()
                upstream = source
                try {
                    val length = source.open(effectiveSpec)
                    opened = true
                    captureResponse(source)
                    return length
                } catch (error: IOException) {
                    lastError = error
                    runCatching { source.close() }
                    upstream = null

                    val statusCode = httpStatusCode(error)
                    val retryable = sourceCode.equals("rutracker", ignoreCase = true) &&
                        attemptIndex < maxAttempts - 1 &&
                        statusCode?.let(::isTransientRuTrackerStreamStatus) == true
                    if (!retryable) throw error

                    Log.w(
                        TAG,
                        "RuTracker/TorrServe stream not ready (HTTP ${statusCode}), " +
                            "retry ${attemptIndex + 2}/$maxAttempts",
                    )
                    Thread.sleep(RUTRACKER_OPEN_RETRY_DELAY_MS)
                }
            }

            throw lastError ?: IOException("RuTracker/TorrServe stream open failed")
        }

        if (!openNextChunk()) {
            opened = true
            return 0L
        }
        opened = true

        return when {
            effectiveSpec.length != C.LENGTH_UNSET.toLong() -> effectiveSpec.length
            resourceLength >= 0L -> (resourceLength - effectiveSpec.position).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) return C.RESULT_END_OF_INPUT
        if (!chunked) return upstream?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        while (true) {
            val source = upstream ?: return C.RESULT_END_OF_INPUT
            val read = source.read(buffer, offset, length)
            if (read != C.RESULT_END_OF_INPUT) {
                currentPosition += read.toLong()
                return read
            }

            closeCurrentUpstream()
            if (atRequestedEnd() || atResourceEnd()) return C.RESULT_END_OF_INPUT
            if (currentPosition <= currentChunkStart) {
                throw IOException("redirectto.cc CDN returned an empty MP3 range at byte $currentPosition")
            }
            if (!openNextChunk()) return C.RESULT_END_OF_INPUT
        }
    }

    override fun getUri(): Uri? = upstream?.uri ?: lastUri ?: originalSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = lastHeaders

    @Throws(IOException::class)
    override fun close() {
        closeCurrentUpstream()
        originalSpec = null
        currentPosition = 0L
        requestedEndExclusive = LENGTH_UNKNOWN
        resourceLength = LENGTH_UNKNOWN
        currentChunkStart = 0L
        chunked = false
        opened = false
        lastUri = null
        lastHeaders = emptyMap()
    }

    @Throws(IOException::class)
    private fun openNextChunk(): Boolean {
        val base = originalSpec ?: return false
        if (atRequestedEnd() || atResourceEnd()) return false

        var chunkLength = REDIRECTTO_CDN_CHUNK_BYTES
        if (requestedEndExclusive >= 0L) {
            chunkLength = min(chunkLength, requestedEndExclusive - currentPosition)
        }
        if (resourceLength >= 0L) {
            chunkLength = min(chunkLength, resourceLength - currentPosition)
        }
        if (chunkLength <= 0L) return false

        currentChunkStart = currentPosition
        val chunkSpec = base.buildUpon()
            .setPosition(currentPosition)
            .setLength(chunkLength)
            .build()
        val source = newUpstream()
        upstream = source
        try {
            Log.d(TAG, "open range start=$currentPosition length=$chunkLength total=$resourceLength")
            source.open(chunkSpec)
            captureResponse(source)
            parseRedirectToContentRangeTotal(lastHeaders)?.let { total ->
                if (total >= currentPosition) resourceLength = total
            }
            Log.d(TAG, "range ready start=$currentChunkStart length=$chunkLength total=$resourceLength")
            return true
        } catch (error: IOException) {
            runCatching { source.close() }
            upstream = null
            throw error
        }
    }

    private fun newUpstream(): DataSource {
        val sourceCode = originalSpec?.key?.let(::playbackProviderSourceFromCacheKey)
        val factory = if (
            sourceCode.equals("rutracker", ignoreCase = true) &&
            rutrackerUpstreamFactory != null
        ) {
            rutrackerUpstreamFactory
        } else {
            upstreamFactory
        }
        return factory.createDataSource().also { source ->
            transferListeners.forEach(source::addTransferListener)
        }
    }

    private fun captureResponse(source: DataSource) {
        lastUri = source.uri ?: lastUri
        lastHeaders = source.responseHeaders
    }

    private fun closeCurrentUpstream() {
        val source = upstream ?: return
        upstream = null
        source.close()
    }

    private fun atRequestedEnd(): Boolean =
        requestedEndExclusive >= 0L && currentPosition >= requestedEndExclusive

    private fun atResourceEnd(): Boolean =
        resourceLength >= 0L && currentPosition >= resourceLength

    private fun shouldChunk(uri: Uri): Boolean {
        val scheme = uri.scheme.orEmpty()
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty()
        return (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)) &&
            ApiClient.isRedirectToCdnHost(host) &&
            path.endsWith(".mp3", ignoreCase = true)
    }

    internal class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val rutrackerUpstreamFactory: DataSource.Factory? = null,
        private val extraHeadersForSource: (String) -> Map<String, String> = { emptyMap() },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RedirectToChunkedDataSource(
            upstreamFactory = upstreamFactory,
            rutrackerUpstreamFactory = rutrackerUpstreamFactory,
            extraHeadersForSource = extraHeadersForSource,
        )
    }

    private companion object {
        const val TAG = "RedirectToDataSource"
        const val LENGTH_UNKNOWN = -1L
        const val RUTRACKER_OPEN_ATTEMPTS = 4
        const val RUTRACKER_OPEN_RETRY_DELAY_MS = 400L
    }
}

@OptIn(UnstableApi::class)
internal fun withStandaloneProviderHeaders(
    dataSpec: DataSpec,
    extraHeadersForSource: (String) -> Map<String, String> = { emptyMap() },
): DataSpec {
    val sourceCode = playbackProviderSourceFromCacheKey(dataSpec.key) ?: return dataSpec
    val providerHeaders = ApiClient.standaloneProviderRequestHeaders(sourceCode)
    val extraHeaders = extraHeadersForSource(sourceCode)
    if (providerHeaders.isEmpty() && extraHeaders.isEmpty()) return dataSpec
    val merged = LinkedHashMap<String, String>(dataSpec.httpRequestHeaders)
    merged.putAll(providerHeaders)
    merged.putAll(extraHeaders)
    return dataSpec.buildUpon()
        .setHttpRequestHeaders(merged)
        .build()
}

internal fun isTransientRuTrackerStreamStatus(statusCode: Int): Boolean =
    statusCode == 404 ||
        statusCode == 409 ||
        statusCode == 425 ||
        statusCode == 429 ||
        statusCode in 500..599

private fun httpStatusCode(error: Throwable): Int? {
    var current: Throwable? = error
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) {
            return current.responseCode
        }
        current = current.cause
    }
    return null
}

internal const val REDIRECTTO_CDN_CHUNK_BYTES = 1_048_576L

internal fun parseRedirectToContentRangeTotal(
    headers: Map<String, List<String>>,
): Long? {
    val value = headers.entries
        .firstOrNull { (name, _) -> name.equals("Content-Range", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        .orEmpty()
    val total = Regex(
        """^\s*bytes\s+(?:\d+-\d+|\*)/(\d+|\*)\s*$""",
        RegexOption.IGNORE_CASE,
    ).matchEntire(value)?.groupValues?.getOrNull(1).orEmpty()
    return total.takeUnless { it.isBlank() || it == "*" }?.toLongOrNull()
}

internal fun requestedEndExclusive(position: Long, length: Long): Long {
    if (length == C.LENGTH_UNSET.toLong()) return -1L
    val safePosition = position.coerceAtLeast(0L)
    val safeLength = length.coerceAtLeast(0L)
    return if (safePosition > Long.MAX_VALUE - safeLength) Long.MAX_VALUE else safePosition + safeLength
}
