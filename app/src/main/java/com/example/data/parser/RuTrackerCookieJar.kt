package com.example.data.parser

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * RFC-aware cookie storage dedicated to RuTracker.
 *
 * OkHttp parses Set-Cookie into [Cookie] instances, including Domain, Path,
 * Expires/Max-Age, Secure and HttpOnly. This jar keeps session cookies in memory
 * and persists only cookies that the server explicitly made persistent.
 */
internal class RuTrackerCookieJar(
    persistentRecords: Set<String> = emptySet(),
    private val persistRecords: (Set<String>) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) : CookieJar {
    private data class Key(
        val name: String,
        val domain: String,
        val path: String,
    )

    private val lock = Any()
    private val cookies = LinkedHashMap<Key, Cookie>()
    private var revision = 0L

    init {
        restorePersistentCookies(persistentRecords)
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!isTrustedRuTrackerUrl(url)) return
        synchronized(lock) {
            var changed = purgeExpiredLocked()
            cookies.forEach { cookie ->
                if (!isRuTrackerCookie(cookie)) return@forEach
                val key = cookie.key()
                if (cookie.expiresAt <= nowMs()) {
                    changed = this.cookies.remove(key) != null || changed
                } else {
                    val previous = this.cookies.put(key, cookie)
                    changed = previous != cookie || changed
                }
            }
            if (changed) {
                revision += 1L
                persistLocked()
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!isTrustedRuTrackerUrl(url)) return emptyList()
        synchronized(lock) {
            val changed = purgeExpiredLocked()
            if (changed) {
                revision += 1L
                persistLocked()
            }
            return cookies.values.filter { it.matches(url) }
        }
    }

    /**
     * CookieManager exposes the browser result as a Cookie request header rather
     * than Set-Cookie attributes. Import those values conservatively as
     * host-only, HTTPS-only session cookies. They remain scoped to the challenged
     * RuTracker host and are never persisted across app restarts.
     */
    fun importBrowserCookieHeader(targetUrl: String, cookieHeader: String) {
        val url = targetUrl.toHttpUrlOrNull()?.takeIf(::isTrustedRuTrackerUrl) ?: return
        val parsed = cookieHeader
            .split(';')
            .map(String::trim)
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val name = pair.substring(0, separator).trim()
                val value = pair.substring(separator + 1).trim()
                if (name.isBlank()) return@mapNotNull null
                runCatching {
                    Cookie.Builder()
                        .name(name)
                        .value(value)
                        .hostOnlyDomain(url.host)
                        .path("/")
                        .secure()
                        .build()
                }.getOrNull()
            }

        if (parsed.isEmpty()) return
        synchronized(lock) {
            purgeExpiredLocked()
            parsed.forEach { cookie ->
                cookies[cookie.key()] = cookie
            }
            // A completed browser challenge is a new cookie generation even when
            // Cloudflare happens to return byte-for-byte identical values. Older
            // in-flight HTTP responses must still become stale at this point.
            revision += 1L
            // Browser-imported cookies are session cookies, so persistLocked()
            // only rewrites the existing persistent subset.
            persistLocked()
        }
    }

    fun hasCookiesFor(targetUrl: String): Boolean {
        val url = targetUrl.toHttpUrlOrNull() ?: return false
        return loadForRequest(url).isNotEmpty()
    }

    fun cookieHeaderFor(targetUrl: String): String {
        val url = targetUrl.toHttpUrlOrNull() ?: return ""
        return loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun revision(): Long = synchronized(lock) { revision }

    /**
     * Clear stale RuTracker cookies only if the jar is still exactly the version
     * used by the challenged HTTP request. A concurrent successful browser
     * challenge imports fresh cookies and advances [revision], so an older
     * response cannot erase the newer authenticated/Cloudflare session.
     *
     * Returns false when the expected revision is stale and the caller should
     * retry the HTTP request with the newer cookies instead of opening another
     * challenge.
     */
    fun clearForChallengeIfUnchanged(
        targetUrl: String,
        expectedRevision: Long,
    ): Boolean {
        val url = targetUrl.toHttpUrlOrNull()?.takeIf(::isTrustedRuTrackerUrl) ?: return false
        synchronized(lock) {
            val expired = purgeExpiredLocked()
            if (expired) {
                revision += 1L
                persistLocked()
            }
            if (revision != expectedRevision) return false

            val hasMatchingCookies = cookies.values.any { it.matches(url) }
            if (hasMatchingCookies) {
                cookies.clear()
                revision += 1L
                persistRecords(emptySet())
            }
            return true
        }
    }

    fun clear() {
        synchronized(lock) {
            cookies.clear()
            revision += 1L
            persistRecords(emptySet())
        }
    }

    private fun restorePersistentCookies(records: Set<String>) {
        if (records.isEmpty()) return
        synchronized(lock) {
            records.mapNotNull(::decodeCookieRecord)
                .filter(::isRuTrackerCookie)
                .filter { it.persistent && it.expiresAt > nowMs() }
                .forEach { cookies[it.key()] = it }
            persistLocked()
        }
    }

    private fun purgeExpiredLocked(): Boolean {
        val now = nowMs()
        var changed = false
        val iterator = cookies.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAt <= now) {
                iterator.remove()
                changed = true
            }
        }
        return changed
    }

    private fun persistLocked() {
        val records = cookies.values
            .asSequence()
            .filter { it.persistent && it.expiresAt > nowMs() }
            .map(::encodeCookieRecord)
            .toSet()
        persistRecords(records)
    }

    private fun isRuTrackerCookie(cookie: Cookie): Boolean =
        isRuTrackerHost(cookie.domain)

    private fun Cookie.key(): Key = Key(
        name = name,
        domain = domain.lowercase(),
        path = path,
    )
}

private fun isTrustedRuTrackerUrl(url: HttpUrl): Boolean =
    url.isHttps &&
        url.port == 443 &&
        isRuTrackerHost(url.host)

private fun isRuTrackerHost(host: String): Boolean =
    host.equals("rutracker.org", ignoreCase = true) ||
        host.endsWith(".rutracker.org", ignoreCase = true)

private fun encodeCookieRecord(cookie: Cookie): String = listOf(
    COOKIE_RECORD_VERSION,
    encodeCookieField(cookie.name),
    encodeCookieField(cookie.value),
    cookie.expiresAt.toString(),
    encodeCookieField(cookie.domain),
    encodeCookieField(cookie.path),
    if (cookie.secure) "1" else "0",
    if (cookie.httpOnly) "1" else "0",
    if (cookie.hostOnly) "1" else "0",
).joinToString("|")

private fun decodeCookieRecord(record: String): Cookie? {
    val parts = record.split('|')
    if (parts.size != 9 || parts[0] != COOKIE_RECORD_VERSION) return null

    val name = decodeCookieField(parts[1]) ?: return null
    val value = decodeCookieField(parts[2]) ?: return null
    val expiresAt = parts[3].toLongOrNull() ?: return null
    val domain = decodeCookieField(parts[4]) ?: return null
    val path = decodeCookieField(parts[5]) ?: return null
    val secure = parts[6] == "1"
    val httpOnly = parts[7] == "1"
    val hostOnly = parts[8] == "1"

    return runCatching {
        Cookie.Builder()
            .name(name)
            .value(value)
            .expiresAt(expiresAt)
            .apply {
                if (hostOnly) hostOnlyDomain(domain) else domain(domain)
                path(path)
                if (secure) secure()
                if (httpOnly) httpOnly()
            }
            .build()
    }.getOrNull()
}

private fun encodeCookieField(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

private fun decodeCookieField(value: String): String? =
    runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()

private const val COOKIE_RECORD_VERSION = "v1"
