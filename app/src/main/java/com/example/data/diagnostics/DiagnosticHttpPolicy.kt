package com.example.data.diagnostics

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Classifies only known destinations/routes. No path, query or arbitrary host is exported. */
internal object DiagnosticHttpPolicy {
    private val salt = UUID.randomUUID().toString()
    private val sites = mapOf(
        "audiopolka.club" to "audiopolka", "uknig.com" to "uknig",
        "audioboo.org" to "audioboo", "knigavuhe.org" to "knigavuhe",
        "baza-knig.info" to "bazaknig", "my-audiobooks.com" to "myaudiobooks",
        "audiokniga.life" to "audioknigalife", "rutracker.org" to "rutracker",
    )

    fun source(host: String): String = sites.entries.firstOrNull { matches(host, it.key) }?.value ?: "other"

    fun sourceFromOrigin(origin: String?): String = runCatching {
        source(URI(origin.orEmpty()).host.orEmpty())
    }.getOrDefault("other")

    fun target(host: String, torrServe: Boolean): String = when {
        torrServe -> "torrserve"
        source(host) != "other" -> "provider_site"
        matches(host, "redirectto.cc") -> "redirectto_cdn"
        matches(host, "fastpic.org") || matches(host, "fastpic.ru") -> "fastpic_image"
        else -> "external"
    }

    fun route(path: String, torrServe: Boolean): String {
        val p = path.lowercase(Locale.ROOT)
        return when {
            torrServe && p.contains("/stream/") -> "audio"
            torrServe -> "torrserve_api"
            p.endsWith(".pl.txt") || p.endsWith("playlist.txt") || p.endsWith(".m3u8") -> "playlist"
            listOf(".mp3", ".m4b", ".m4a", ".ogg", ".aac", ".flac", ".wav").any(p::endsWith) -> "audio"
            listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif").any(p::endsWith) -> "image"
            p.contains("/cdn-cgi/") -> "challenge"
            p.endsWith("login.php") || p.contains("/login/") -> "login"
            p.contains("/search") -> "search"
            p.contains("/book/") || p.contains("/books/") || p.endsWith("viewtopic.php") -> "book_page"
            else -> "page"
        }
    }

    fun contentType(value: String?): String {
        val mime = value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return "missing"
        return when {
            mime == "text/html" || mime == "application/xhtml+xml" -> "html"
            mime == "application/json" || mime.endsWith("+json") -> "json"
            mime.startsWith("audio/") -> "audio"
            mime.startsWith("image/") -> "image"
            mime.contains("mpegurl") -> "playlist"
            mime.startsWith("text/") -> "text"
            else -> "other"
        }
    }

    // OkHttp 4 restores the user request on the public response. networkResponse
    // retains the request after CookieJar and network interceptors. A cache-only
    // or synthetic response has no observed wire headers; absence is not false.
    fun responseRequestPresence(response: Response): Map<String, String> {
        val request = response.networkResponse?.request
            ?: return mapOf("requestHeadersObserved" to "false")
        return mapOf(
            "requestHeadersObserved" to "true",
            "sentCookie" to (!request.header("Cookie").isNullOrBlank()).toString(),
            "sentAuthorization" to (!request.header("Authorization").isNullOrBlank()).toString(),
            "sentReferer" to (!request.header("Referer").isNullOrBlank()).toString(),
            "rangeRequest" to (!request.header("Range").isNullOrBlank()).toString(),
        )
    }

    /** Opaque process-local key distinguishes unknown CDN hosts without disclosing server addresses. */
    fun hostKey(host: String): String = fingerprint("host:" + host.lowercase(Locale.ROOT))

    /** A one-process pseudonym lets Media3's failed DataSpec match the HTTP request without its URL. */
    fun resourceKey(url: String): String = fingerprint("resource:" + (url.toHttpUrlOrNull()?.toString() ?: url))

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest((salt + ":" + value).toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun matches(host: String, root: String): Boolean =
        host.equals(root, ignoreCase = true) || host.lowercase(Locale.ROOT).endsWith(".$root")
}
