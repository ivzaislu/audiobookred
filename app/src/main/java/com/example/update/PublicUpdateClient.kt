package com.example.update

import com.example.BuildConfig
import com.example.data.api.ApiClient
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

internal data class PublicUpdateRepository(
    val owner: String,
    val name: String,
)

/**
 * Public-repository update transport.
 *
 * The APK contains no GitHub credential. Update metadata is read from a public
 * raw.githubusercontent.com `latest.json`, while the APK itself is fetched from
 * the matching public repository's GitHub Release.
 */
internal class PublicUpdateClient(
    manifestUrl: String = BuildConfig.UPDATE_MANIFEST_URL,
) {
    private val manifestUrl = normalizedManifestUrl(manifestUrl)
        ?: error("Public update manifest URL is not configured")
    private val repository = repositoryFromManifestUrl(this.manifestUrl)
        ?: error("Public update repository cannot be derived from manifest URL")
    private val http = createUpdateHttpClient()

    suspend fun fetchManifest(): String {
        val request = Request.Builder()
            .url(manifestUrl)
            .get()
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()
        return executeCancellableUpdateCall(http.newCall(request)) { response ->
            if (!response.isSuccessful) {
                throw IOException("Update manifest request failed: HTTP ${response.code}")
            }
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Update manifest is empty")
        }
    }

    suspend fun <T> consumeDownload(
        downloadUrl: String,
        resumeFromBytes: Long = 0L,
        consume: (Response) -> T,
    ): T {
        if (!isReleaseDownloadForRepository(downloadUrl, repository)) {
            throw IOException("Update APK URL does not belong to the configured public repository")
        }
        val request = buildUpdateDownloadRequest(
            downloadUrl = downloadUrl,
            resumeFromBytes = resumeFromBytes,
        )
        return executeCancellableUpdateCall(http.newCall(request), consume)
    }

    fun acceptsDownloadUrl(downloadUrl: String): Boolean =
        isReleaseDownloadForRepository(downloadUrl, repository)

    companion object {
        internal const val UPDATE_READ_TIMEOUT_SECONDS = 60L
        internal const val UPDATE_CALL_TIMEOUT_MINUTES = 15L

        internal fun createUpdateHttpClient() =
            ApiClient.createHttpClient(readTimeoutSeconds = UPDATE_READ_TIMEOUT_SECONDS)
                .newBuilder()
                .callTimeout(UPDATE_CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .build()

        internal fun buildUpdateDownloadRequest(
            downloadUrl: String,
            resumeFromBytes: Long,
        ): Request =
            Request.Builder()
                .url(downloadUrl)
                .get()
                .header("Accept", "application/vnd.android.package-archive")
                .apply {
                    if (resumeFromBytes > 0L) {
                        header("Range", "bytes=$resumeFromBytes-")
                    }
                }
                .build()

        internal fun normalizedManifestUrl(raw: String?): String? {
            val clean = raw?.trim().orEmpty()
            if (clean.isBlank()) return null
            return try {
                val uri = URI(clean)
                val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
                if (!uri.scheme.equals("https", ignoreCase = true)) null
                else if (!uri.host.equals("raw.githubusercontent.com", ignoreCase = true)) null
                else if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) null
                else if (segments.size < 4 || !segments.last().equals("latest.json", ignoreCase = true)) null
                else clean
            } catch (_: Exception) {
                null
            }
        }

        internal fun repositoryFromManifestUrl(raw: String?): PublicUpdateRepository? {
            val normalized = normalizedManifestUrl(raw) ?: return null
            return try {
                val segments = URI(normalized).path.split('/').filter(String::isNotBlank)
                val owner = segments.getOrNull(0)?.trim().orEmpty()
                val repo = segments.getOrNull(1)?.trim().orEmpty()
                if (owner.isBlank() || repo.isBlank()) null else PublicUpdateRepository(owner, repo)
            } catch (_: Exception) {
                null
            }
        }

        internal fun isReleaseDownloadForRepository(
            raw: String,
            repository: PublicUpdateRepository,
        ): Boolean = try {
            val uri = URI(raw.trim())
            val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("github.com", ignoreCase = true) &&
                uri.userInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                segments.size >= 6 &&
                segments[0].equals(repository.owner, ignoreCase = true) &&
                segments[1].equals(repository.name, ignoreCase = true) &&
                segments[2] == "releases" &&
                segments[3] == "download" &&
                segments[4].isNotBlank() &&
                segments.drop(5).joinToString("/").endsWith(".apk", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }
}


internal suspend fun <T> executeCancellableUpdateCall(
    call: Call,
    consume: (Response) -> T,
): T = suspendCancellableCoroutine { continuation ->
    // Response consumption writes the update file from OkHttp's callback thread.
    // On cancellation, first abort the socket and then wait for any active
    // consumer to leave this critical section before the coroutine is released.
    // This prevents a canceled download from continuing to mutate its .part file
    // while a new attempt is already trying to resume it.
    val consumeLock = Any()

    continuation.invokeOnCancellation {
        call.cancel()
        synchronized(consumeLock) {
            // Barrier only. The active consumer, if any, has now fully exited.
        }
    }

    call.enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            synchronized(consumeLock) {
                if (!continuation.isActive) return
                continuation.resumeWithException(error)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                synchronized(consumeLock) {
                    if (!continuation.isActive) return
                    try {
                        continuation.resume(consume(response))
                    } catch (error: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                    }
                }
            }
        }
    })
}
