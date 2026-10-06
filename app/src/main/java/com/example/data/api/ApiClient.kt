package com.example.data.api

import com.example.BuildConfig
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor

/** Legacy backend headers are always stripped from public-provider traffic. */
internal object BackendHeaderPolicy {
    private val names = listOf(
        "X-Client-Id",
        "X-Device-Id",
        "X-Device-Name",
        "X-App-Version",
        "X-Profile-Id",
        "X-Device-Token",
        "X-Profile-Key",
    )

    fun strip(request: Request): Request {
        val builder = request.newBuilder()
        names.forEach(builder::removeHeader)
        return builder.build()
    }
}

/**
 * Reject DNS answers that would make a public-looking provider URL connect to a
 * local/private address. Reject the complete answer when even one address is
 * unsafe so DNS rebinding/mixed A/AAAA responses cannot fall back to it.
 */
internal class StandalonePublicDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any(ApiClient::isDisallowedResolvedAddress)) {
            throw UnknownHostException("DNS-адрес недоступен для standalone APK: $hostname")
        }
        return addresses
    }
}

/** Shared HTTP/URL policy for the standalone APK. There is no backend API client. */
object ApiClient {
    private const val BLOCKED_LEGACY_ORIGIN = "https://unused.invalid/"
    private const val BAZAKNIG_ORIGIN = "https://baza-knig.info"
    private const val BAZAKNIG_REFERER = "$BAZAKNIG_ORIGIN/"
    private const val BAZAKNIG_CDN_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/136 Safari/537.36 AbredAndroid"
    private const val MYAUDIOBOOKS_ORIGIN = "https://my-audiobooks.com"
    private const val MYAUDIOBOOKS_REFERER = "$MYAUDIOBOOKS_ORIGIN/"
    private const val MYAUDIOBOOKS_CDN_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/152 Safari/537.36 AbredAndroid"

    // Parser construction happens during application startup. Most providers use
    // the same transport policy and timeout, so rebuilding identical immutable
    // OkHttp clients for every parser only adds cold-start work. Keep one shared
    // client per effective read timeout; callers that need extra interceptors can
    // still derive an independent client with newBuilder().
    private val httpClientsByReadTimeout = mutableMapOf<Long, OkHttpClient>()

    /** Shared standalone transport for parsers, media, covers and downloads. */
    fun createHttpClient(readTimeoutSeconds: Long = 30L): OkHttpClient {
        val effectiveReadTimeout = readTimeoutSeconds.coerceAtLeast(30L)
        return synchronized(httpClientsByReadTimeout) {
            httpClientsByReadTimeout.getOrPut(effectiveReadTimeout) {
                buildHttpClient(effectiveReadTimeout)
            }
        }
    }

    private fun buildHttpClient(readTimeoutSeconds: Long): OkHttpClient {
        val logger = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }

        // Public provider/CDN URLs must use DNS hostnames and normal HTTP(S)
        // ports. Redirect follow-ups are checked by the network interceptor too.
        val standaloneRequestPolicy = Interceptor { chain ->
            val request = chain.request()
            if (isDisallowedStandaloneTarget(request.url.toString())) {
                throw IOException("Недопустимый сетевой адрес для standalone APK")
            }
            val sanitized = BackendHeaderPolicy.strip(request)
            chain.proceed(applyStandaloneProviderHeaders(sanitized))
        }
        val standaloneRedirectPolicy = Interceptor { chain ->
            val request = chain.request()
            if (isDisallowedStandaloneTarget(request.url.toString())) {
                throw IOException("Недопустимый сетевой редирект для standalone APK")
            }
            // Network interceptors run for redirect follow-ups as well. Reapply
            // provider headers so a CDN redirect cannot silently lose Referer.
            chain.proceed(applyStandaloneProviderHeaders(request))
        }

        val httpBuilder = OkHttpClient.Builder()
            .dns(StandalonePublicDns())
            .addInterceptor(standaloneRequestPolicy)
            .addNetworkInterceptor(standaloneRedirectPolicy)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(true)
        if (BuildConfig.DEBUG) httpBuilder.addInterceptor(logger)
        return httpBuilder.build()
    }

    /**
     * Referer hints that let the shared redirectto.cc transport distinguish
     * providers before the request reaches OkHttp's common interceptor chain.
     * User-Agent and Origin remain centralized in [applyStandaloneProviderHeaders].
     */
    internal fun standaloneProviderRequestHeaders(sourceCode: String?): Map<String, String> = when (
        sourceCode?.trim()?.lowercase()
    ) {
        "bazaknig" -> mapOf("Referer" to BAZAKNIG_REFERER)
        "myaudiobooks" -> mapOf("Referer" to MYAUDIOBOOKS_REFERER)
        else -> emptyMap()
    }

    /**
     * redirectto.cc is shared by Baza-Knig and MY-AUDIOBOOKS. Select the browser
     * profile from the request Referer instead of treating every CDN request as
     * Baza-Knig. Existing headers such as Range are deliberately preserved.
     *
     * Captured MY-AUDIOBOOKS playlist requests carry Origin, while captured MP3
     * range requests carry Referer without Origin; keep that distinction here.
     */
    internal fun applyStandaloneProviderHeaders(request: Request): Request {
        if (!isRedirectToCdnHost(request.url.host)) return request
        val referer = request.header("Referer").orEmpty()
        val origin = request.header("Origin").orEmpty()
        val isMyAudiobooks = sameOrigin(MYAUDIOBOOKS_REFERER, referer) ||
            sameOrigin(MYAUDIOBOOKS_REFERER, origin)
        if (isMyAudiobooks) {
            val builder = request.newBuilder()
                .header("User-Agent", MYAUDIOBOOKS_CDN_USER_AGENT)
                .header("Referer", MYAUDIOBOOKS_REFERER)
            if (origin.isNotBlank() || request.url.encodedPath.endsWith(".pl.txt", ignoreCase = true)) {
                builder.header("Origin", MYAUDIOBOOKS_ORIGIN)
            } else {
                builder.removeHeader("Origin")
            }
            return builder.build()
        }
        return request.newBuilder()
            .header("User-Agent", BAZAKNIG_CDN_USER_AGENT)
            .header("Origin", BAZAKNIG_ORIGIN)
            .header("Referer", BAZAKNIG_REFERER)
            .build()
    }

    internal fun isRedirectToCdnHost(host: String): Boolean =
        host.equals("redirectto.cc", ignoreCase = true) ||
            host.endsWith(".redirectto.cc", ignoreCase = true)

    /**
     * Accept only direct public-style HTTP(S) URLs in standalone runtime paths.
     * Relative URLs, the blocked legacy placeholder origin, bare IP literals and
     * unusual ports are rejected.
     */
    fun externalHttpUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (!isSupportedHttpUrl(value)) return null
        if (isDisallowedStandaloneTarget(value)) return null
        return value
    }

    fun coverImageUrl(raw: String?): String? {
        val direct = externalHttpUrl(raw) ?: return null
        return normalizeFastPicCoverUrl(direct)
    }

    private fun normalizeFastPicCoverUrl(raw: String): String {
        return try {
            val uri = URI(raw)
            val host = uri.host.orEmpty()
            val normalizedHost = when {
                host.equals("fastpic.ru", ignoreCase = true) -> "fastpic.org"
                host.endsWith(".fastpic.ru", ignoreCase = true) ->
                    host.dropLast(".fastpic.ru".length) + ".fastpic.org"
                else -> host
            }
            val isFastPic = normalizedHost.equals("fastpic.org", ignoreCase = true) ||
                normalizedHost.endsWith(".fastpic.org", ignoreCase = true)
            if (!isFastPic) return raw

            if (
                uri.scheme.equals("https", ignoreCase = true) &&
                normalizedHost.equals(host, ignoreCase = true)
            ) {
                return raw
            }

            URI(
                "https",
                uri.userInfo,
                normalizedHost,
                -1,
                uri.path,
                uri.query,
                uri.fragment,
            ).toString()
        } catch (_: Exception) {
            raw
        }
    }

    internal fun isDisallowedStandaloneTarget(targetUrl: String): Boolean {
        if (!isSupportedHttpUrl(targetUrl)) return true
        if (sameOrigin(BLOCKED_LEGACY_ORIGIN, targetUrl)) return true
        return try {
            val uri = URI(targetUrl)
            val host = uri.host.orEmpty()
            isIpLiteralHost(host) || hasNonDefaultPort(uri)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * True for local, private, link-local, multicast and other non-public
     * addresses that a remote provider URL must never reach after DNS lookup.
     */
    internal fun isDisallowedResolvedAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }

        val bytes = address.address
        return when (bytes.size) {
            4 -> isDisallowedIpv4(bytes)
            16 -> isDisallowedIpv6(bytes)
            else -> true
        }
    }

    internal fun sameOrigin(base: String, target: String): Boolean {
        if (!isSupportedHttpUrl(base) || !isSupportedHttpUrl(target)) return false
        return try {
            val baseUri = URI(base)
            val targetUri = URI(target)
            baseUri.scheme.equals(targetUri.scheme, ignoreCase = true) &&
                baseUri.host.equals(targetUri.host, ignoreCase = true) &&
                effectivePort(baseUri) == effectivePort(targetUri)
        } catch (_: Exception) {
            false
        }
    }

    internal fun isSupportedHttpUrl(url: String): Boolean = try {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase()
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }

    private fun isDisallowedIpv4(bytes: ByteArray): Boolean {
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        val c = bytes[2].toInt() and 0xff
        return when {
            a == 0 -> true // current network / unspecified
            a == 10 -> true
            a == 100 && b in 64..127 -> true // carrier-grade NAT
            a == 127 -> true
            a == 169 && b == 254 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 0 && c == 0 -> true // IETF protocol assignments
            a == 192 && b == 0 && c == 2 -> true // TEST-NET-1
            a == 192 && b == 168 -> true
            a == 198 && b in 18..19 -> true // benchmark networks
            a == 198 && b == 51 && c == 100 -> true // TEST-NET-2
            a == 203 && b == 0 && c == 113 -> true // TEST-NET-3
            a >= 224 -> true // multicast + reserved/broadcast
            else -> false
        }
    }

    private fun isDisallowedIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff

        // IPv4-mapped IPv6 (::ffff:a.b.c.d) must obey the IPv4 policy too.
        val mappedIpv4 = (0 until 10).all { bytes[it].toInt() == 0 } &&
            (bytes[10].toInt() and 0xff) == 0xff &&
            (bytes[11].toInt() and 0xff) == 0xff
        if (mappedIpv4) return isDisallowedIpv4(bytes.copyOfRange(12, 16))

        if ((first and 0xfe) == 0xfc) return true // fc00::/7 unique-local
        if (first == 0xfe && (second and 0xc0) == 0x80) return true // fe80::/10 link-local
        if (first == 0xff) return true // multicast

        // 2001:db8::/32 documentation prefix; never a public runtime target.
        if (
            first == 0x20 && second == 0x01 &&
            (bytes[2].toInt() and 0xff) == 0x0d &&
            (bytes[3].toInt() and 0xff) == 0xb8
        ) {
            return true
        }
        return false
    }

    private fun isIpLiteralHost(host: String): Boolean {
        val normalized = host.removePrefix("[").removeSuffix("]")
        if (normalized.contains(':')) return true
        val parts = normalized.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) {
                false
            } else {
                val value = part.toIntOrNull()
                value != null && value in 0..255
            }
        }
    }

    private fun hasNonDefaultPort(uri: URI): Boolean {
        if (uri.port < 0) return false
        return uri.port != effectivePortForScheme(uri.scheme)
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        else -> effectivePortForScheme(uri.scheme)
    }

    private fun effectivePortForScheme(scheme: String?): Int = when (scheme?.lowercase()) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }
}
