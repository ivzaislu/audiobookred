package com.example.data.image

import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.data.api.ApiClient
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient

private const val FASTPIC_SIGNED_EXPIRY_SKEW_SECONDS = 60L

/**
 * Shared image pipeline for book covers.
 *
 * Covers are a convenience cache only. In the standalone APK they must never
 * grow into a large offline collection: keep a small LRU disk cache for recently
 * viewed covers and a deliberately small decoded-bitmap memory cache. Background
 * cover prefetch is intentionally absent, so storage is spent only on images the
 * UI actually requests.
 */
object PosterImageCache {
    // v3 drops FastPic responses cached before signed-URL resolution was added.
    // Older entries may contain a viewer HTML body under a direct image key.
    private const val CACHE_DIRECTORY = "poster_images_v3"
    private const val MAX_DISK_CACHE_BYTES = 32L * 1024L * 1024L
    private const val MEMORY_CACHE_PERCENT = 0.08

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    private const val IMAGE_ACCEPT =
        "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
    private const val ACCEPT_LANGUAGE = "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
    private const val RUTRACKER_REFERER = "https://rutracker.org/forum/"
    private const val FASTPIC_REFERER = "https://fastpic.org/"
    private const val FASTPIC_NEGATIVE_CACHE_MS = 30_000L
    private const val FASTPIC_SIGNED_CACHE_MAX = 256

    @Volatile
    private var loader: ImageLoader? = null

    private data class SignedCoverCacheEntry(
        val url: String,
        val validUntilMs: Long,
    )

    private val fastPicSignedCache = object :
        LinkedHashMap<String, SignedCoverCacheEntry>(FASTPIC_SIGNED_CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, SignedCoverCacheEntry>?
        ): Boolean = size > FASTPIC_SIGNED_CACHE_MAX
    }
    private val fastPicNegativeCache = LinkedHashMap<String, Long>()

    fun imageLoader(context: Context): ImageLoader {
        loader?.let { return it }
        return synchronized(this) {
            loader ?: build(context.applicationContext).also { loader = it }
        }
    }

    fun requestUrl(raw: String?): String? = ApiClient.coverImageUrl(raw)

    /**
     * Initialize Coil's lazy disk cache on a background thread before a visible
     * shelf can fan out several concurrent image requests. Callers are expected
     * to invoke this away from the main thread after the first UI frame.
     */
    @OptIn(ExperimentalCoilApi::class)
    fun warmUp(context: Context) {
        imageLoader(context).diskCache?.size
    }

    @OptIn(ExperimentalCoilApi::class)
    fun diskSizeBytes(context: Context): Long =
        imageLoader(context).diskCache?.size?.coerceAtLeast(0L) ?: 0L

    @OptIn(ExperimentalCoilApi::class)
    fun clear(context: Context) {
        val imageLoader = imageLoader(context)
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }

    private fun build(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .okHttpClient { buildImageHttpClient() }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve(CACHE_DIRECTORY))
                    .maxSizeBytes(MAX_DISK_CACHE_BYTES)
                    .build()
            }
            // Cover URLs are effectively immutable for our catalog. Ignore
            // provider no-cache/very-short cache headers so recently viewed
            // posters can be reused until the small LRU cache evicts them.
            .respectCacheHeaders(false)
            .build()

    private fun buildImageHttpClient(): OkHttpClient =
        // Derive from the shared standalone transport so cover traffic gets the
        // same DNS-rebinding/private-address protection as parsers, downloads and
        // Media3. Keep the image-specific shorter timeouts and browser headers.
        ApiClient.createHttpClient()
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request()
                if (ApiClient.isDisallowedStandaloneTarget(request.url.toString())) {
                    throw IOException("Недопустимый адрес обложки для standalone APK")
                }

                // Baza-Knig serves covers from rotating *.redirectto.cc hosts and
                // rejects ordinary hotlink requests. Use the same provider headers
                // as Media3/download traffic before applying any host-specific
                // browser profile below.
                val providerRequest = ApiClient.applyStandaloneProviderHeaders(request)
                if (!isFastPicHost(providerRequest.url.host)) {
                    return@addInterceptor chain.proceed(providerRequest)
                }

                val normalizedUrl = ApiClient.coverImageUrl(providerRequest.url.toString())
                    ?: throw IOException("Некорректный FastPic URL")
                val normalizedRequest = providerRequest.newBuilder()
                    .url(normalizedUrl)
                    .build()

                // FastPic /big/... assets are not uniformly hotlinkable across
                // shards. Some nodes require the short-lived signed URL exposed
                // by the lightweight /fullview page. Resolve that URL first and
                // keep a small in-memory cache until shortly before it expires.
                val signedUrl = resolveFastPicSignedUrl(chain, normalizedRequest.url.toString())
                val imageRequest = normalizedRequest.newBuilder()
                    .apply {
                        if (signedUrl != null) url(signedUrl)
                    }
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", IMAGE_ACCEPT)
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .header("Referer", if (signedUrl != null) FASTPIC_REFERER else RUTRACKER_REFERER)
                    .header("Sec-Fetch-Dest", "image")
                    .header("Sec-Fetch-Mode", "no-cors")
                    .header("Sec-Fetch-Site", if (signedUrl != null) "same-site" else "cross-site")
                    .removeHeader("Upgrade-Insecure-Requests")
                    .removeHeader("Sec-Fetch-User")
                    .build()

                val first = chain.proceed(imageRequest)
                if (
                    !isFastPicViewerResponse(
                        first.request.url.host,
                        first.request.url.encodedPath,
                        first.header("Content-Type"),
                    )
                ) {
                    return@addInterceptor first
                }

                val viewerUrl = first.request.url.toString()
                first.close()

                // Last fallback for FastPic nodes that redirect even an image
                // request to a viewer page. Retry the canonical direct asset with
                // the viewer as Referer.
                val viewerBackedRequest = normalizedRequest.newBuilder()
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", IMAGE_ACCEPT)
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .header("Referer", viewerUrl)
                    .header("Sec-Fetch-Dest", "image")
                    .header("Sec-Fetch-Mode", "no-cors")
                    .header("Sec-Fetch-Site", "same-site")
                    .build()
                chain.proceed(viewerBackedRequest)
            }
            // Redirect follow-ups are separate network exchanges. Validate each
            // one and reapply provider headers on rotating Baza-Knig CDN hosts.
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                if (ApiClient.isDisallowedStandaloneTarget(request.url.toString())) {
                    throw IOException("Недопустимый редирект обложки для standalone APK")
                }
                chain.proceed(ApiClient.applyStandaloneProviderHeaders(request))
            }
            .build()

    private fun resolveFastPicSignedUrl(
        chain: Interceptor.Chain,
        rawUrl: String,
    ): String? {
        val source = fastPicCanonicalImageUrl(rawUrl) ?: return null
        fastPicSignedExpirySeconds(rawUrl)?.let { expiry ->
            if (expiry > currentEpochSeconds() + FASTPIC_SIGNED_EXPIRY_SKEW_SECONDS) {
                return rawUrl
            }
        }

        val nowMs = System.currentTimeMillis()
        synchronized(fastPicSignedCache) {
            fastPicSignedCache[source]?.let { cached ->
                if (cached.validUntilMs > nowMs) return cached.url
                fastPicSignedCache.remove(source)
            }
            val failedUntil = fastPicNegativeCache[source] ?: 0L
            if (failedUntil > nowMs) return null
            fastPicNegativeCache.remove(source)
        }

        val resolved = fastPicViewerUrls(source)
            .firstNotNullOfOrNull { viewerUrl ->
                val viewerRequest = chain.request().newBuilder()
                    .url(viewerUrl)
                    .get()
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .header("Referer", FASTPIC_REFERER)
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "same-origin")
                    .removeHeader("Sec-Fetch-User")
                    .build()

                try {
                    chain.proceed(viewerRequest).use { response ->
                        if (!response.isSuccessful) return@use null
                        val contentType = response.header("Content-Type").orEmpty()
                        if (
                            contentType.isNotBlank() &&
                            !contentType.contains("html", ignoreCase = true)
                        ) {
                            return@use null
                        }
                        fastPicSignedUrlFromHtml(
                            sourceUrl = source,
                            html = response.body?.string().orEmpty(),
                        )
                    }
                } catch (_: Exception) {
                    null
                }
            }

        synchronized(fastPicSignedCache) {
            if (resolved == null) {
                fastPicNegativeCache[source] = nowMs + FASTPIC_NEGATIVE_CACHE_MS
                Log.d(POSTER_LOG_TAG, "FastPic signed URL unavailable, direct fallback: $source")
            } else {
                val expiry = fastPicSignedExpirySeconds(resolved)
                if (expiry != null) {
                    val validUntilMs = (
                        expiry - FASTPIC_SIGNED_EXPIRY_SKEW_SECONDS
                    ).coerceAtLeast(currentEpochSeconds()) * 1000L
                    fastPicSignedCache[source] = SignedCoverCacheEntry(
                        url = resolved,
                        validUntilMs = validUntilMs,
                    )
                    fastPicNegativeCache.remove(source)
                    Log.d(POSTER_LOG_TAG, "FastPic signed URL resolved: $source")
                }
            }
        }
        return resolved
    }

    private fun isFastPicViewerResponse(
        host: String,
        encodedPath: String,
        contentType: String?,
    ): Boolean {
        val normalizedHost = host.lowercase()
        val isViewerHost =
            normalizedHost == "fastpic.org" ||
                normalizedHost == "www.fastpic.org" ||
                normalizedHost == "fastpic.ru" ||
                normalizedHost == "www.fastpic.ru"
        return isViewerHost && (
            encodedPath.startsWith("/view/") ||
                contentType.orEmpty().startsWith("text/html", ignoreCase = true)
            )
    }

    private fun isFastPicHost(host: String): Boolean =
        host.equals("fastpic.org", ignoreCase = true) ||
            host.endsWith(".fastpic.org", ignoreCase = true) ||
            host.equals("fastpic.ru", ignoreCase = true) ||
            host.endsWith(".fastpic.ru", ignoreCase = true)
}

internal fun fastPicCanonicalImageUrl(raw: String): String? {
    val url = ApiClient.coverImageUrl(raw)?.toHttpUrlOrNull() ?: return null
    val hostMatch = Regex("""^i(\d+)\.fastpic\.org$""", RegexOption.IGNORE_CASE)
        .matchEntire(url.host)
        ?: return null
    val segments = url.pathSegments
    if (segments.size != 5) return null
    if (segments[0] != "big" && segments[0] != "thumb") return null
    if (!segments[1].matches(Regex("""\d{4}"""))) return null
    if (!segments[2].matches(Regex("""\d{4}"""))) return null
    if (!segments[3].matches(Regex("""[0-9a-fA-F]{2}"""))) return null
    if (segments[4].isBlank()) return null

    return url.newBuilder()
        .scheme("https")
        .host("i" + hostMatch.groupValues[1] + ".fastpic.org")
        .query(null)
        .fragment(null)
        .build()
        .toString()
}

internal fun fastPicViewerUrls(raw: String): List<String> {
    val source = fastPicCanonicalImageUrl(raw)?.toHttpUrlOrNull() ?: return emptyList()
    val shard = Regex("""^i(\d+)\.fastpic\.org$""", RegexOption.IGNORE_CASE)
        .matchEntire(source.host)
        ?.groupValues
        ?.getOrNull(1)
        ?: return emptyList()
    val segments = source.pathSegments
    val tail = shard + "/" + segments[1] + "/" + segments[2] + "/" + segments[4]
    return listOf(
        "https://fastpic.org/fullview/" + tail,
        "https://fastpic.org/view/" + tail + ".html",
    )
}

internal fun fastPicFullviewUrl(raw: String): String? =
    fastPicViewerUrls(raw).firstOrNull()

internal fun fastPicSignedUrlFromHtml(
    sourceUrl: String,
    html: String,
): String? {
    val source = fastPicCanonicalImageUrl(sourceUrl)?.toHttpUrlOrNull() ?: return null
    val candidates = buildList {
        FASTPIC_LOADING_IMG_REGEX.findAll(html).forEach { match ->
            match.groupValues.getOrNull(1)?.let(::add)
        }
        FASTPIC_IMAGE_SRC_REGEX.findAll(html).forEach { match ->
            match.groupValues.getOrNull(1)?.let(::add)
        }
    }

    for (raw in candidates) {
        val normalized = raw.trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .let { value -> if (value.startsWith("//")) "https:" + value else value }
        val candidate = normalized.toHttpUrlOrNull() ?: continue
        if (candidate.scheme != "https" || candidate.port != 443) continue
        if (candidate.username.isNotBlank() || candidate.password.isNotBlank()) continue
        if (candidate.fragment != null) continue
        if (!candidate.host.equals(source.host, ignoreCase = true)) continue
        if (candidate.encodedPath != source.encodedPath) continue
        val expiry = fastPicSignedExpirySeconds(candidate.toString()) ?: continue
        if (expiry <= currentEpochSeconds() + FASTPIC_SIGNED_EXPIRY_SKEW_SECONDS) continue
        return candidate.toString()
    }
    return null
}

internal fun fastPicSignedExpirySeconds(raw: String): Long? {
    val url = raw.toHttpUrlOrNull() ?: return null
    val md5 = url.queryParameter("md5").orEmpty()
    val expires = url.queryParameter("expires")?.toLongOrNull() ?: return null
    return expires.takeIf { md5.isNotBlank() }
}

private fun currentEpochSeconds(): Long = System.currentTimeMillis() / 1000L

private val FASTPIC_LOADING_IMG_REGEX = Regex(
    """loading_img\s*=\s*['"]([^'"]+)['"]""",
    RegexOption.IGNORE_CASE,
)

private val FASTPIC_IMAGE_SRC_REGEX = Regex(
    """<img\b[^>]*\bclass=['"][^'"]*\bimage\b[^'"]*['"][^>]*\bsrc=['"]([^'"]+)['"]""",
    RegexOption.IGNORE_CASE,
)

private const val POSTER_LOG_TAG = "AbredPoster"

