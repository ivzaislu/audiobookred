package com.example.data.parser

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import okhttp3.CookieJar

internal const val RUTRACKER_SOURCE = "rutracker"
internal const val RUTRACKER_SOURCE_NAME = "RuTracker"
internal const val RUTRACKER_BASE_URL = "https://rutracker.org"
internal const val RUTRACKER_FORUM_URL = "$RUTRACKER_BASE_URL/forum"
internal const val RUTRACKER_CHALLENGE_RESOLUTION_TIMEOUT_MS =
    BROWSER_CHALLENGE_DEFAULT_TIMEOUT_MS

internal class RuTrackerCloudflareRequiredException(
    val targetUrl: String,
    message: String = "RuTracker требует проверку Cloudflare",
) : IOException(message)

/**
 * RuTracker contributes its cookie jar plus provider-specific challenge config.
 * Challenge lifecycle/WebView/retry behavior is shared by every provider.
 */
internal object RuTrackerCloudflareSession : BaseBrowserChallengeSession(
    challengeSpec = BrowserChallengeSpec(
        sourceId = RUTRACKER_SOURCE,
        displayName = RUTRACKER_SOURCE_NAME,
        defaultUrl = RUTRACKER_FORUM_URL,
        trustedHosts = setOf("rutracker.org"),
        successProbeScript = RUTRACKER_SUCCESS_PROBE,
        timeoutMs = RUTRACKER_CHALLENGE_RESOLUTION_TIMEOUT_MS,
    ),
) {
    private const val PREFS = "rutracker_cloudflare"
    private const val LEGACY_COOKIE_KEY = "cookie_header"
    private const val COOKIE_RECORDS_KEY = "cookies_v2"
    private const val USER_AGENT_KEY = "user_agent"

    private val lock = Any()

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var scopedCookieJar: RuTrackerCookieJar? = null

    override fun initializeProvider(context: Context) {
        if (prefs != null && scopedCookieJar != null) return

        synchronized(lock) {
            val preferences = prefs
                ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
            if (scopedCookieJar == null) {
                val stored = preferences
                    .getStringSet(COOKIE_RECORDS_KEY, emptySet())
                    ?.toSet()
                    .orEmpty()
                scopedCookieJar = RuTrackerCookieJar(
                    persistentRecords = stored,
                    persistRecords = { records ->
                        preferences.edit()
                            .putStringSet(COOKIE_RECORDS_KEY, records)
                            .apply()
                    },
                )

                val legacy = preferences.getString(LEGACY_COOKIE_KEY, "").orEmpty().trim()
                if (legacy.isNotBlank()) {
                    scopedCookieJar?.importBrowserCookieHeader(RUTRACKER_BASE_URL, legacy)
                    preferences.edit().remove(LEGACY_COOKIE_KEY).apply()
                }
            }
        }
    }

    fun cookieJar(context: Context): CookieJar {
        initialize(context)
        return checkNotNull(scopedCookieJar)
    }

    fun cookieHeader(targetUrl: String = RUTRACKER_BASE_URL): String =
        scopedCookieJar?.cookieHeaderFor(targetUrl).orEmpty()

    fun hasCookiesFor(targetUrl: String): Boolean =
        scopedCookieJar?.hasCookiesFor(targetUrl) == true

    fun cookieRevision(): Long =
        scopedCookieJar?.revision() ?: 0L

    fun clearCookiesForChallengeIfUnchanged(
        targetUrl: String,
        expectedRevision: Long,
    ): Boolean =
        scopedCookieJar?.clearForChallengeIfUnchanged(
            targetUrl = targetUrl,
            expectedRevision = expectedRevision,
        ) == true

    override fun persistedUserAgent(): String =
        prefs?.getString(USER_AGENT_KEY, "").orEmpty()

    override fun persistChallengeResult(
        targetUrl: String,
        cookieHeader: String,
        userAgent: String,
    ) {
        val cookie = cookieHeader.trim()
        val ua = userAgent.trim()
        if (cookie.isNotBlank()) {
            scopedCookieJar?.importBrowserCookieHeader(targetUrl, cookie)
        }
        if (ua.isNotBlank()) {
            prefs?.edit()?.putString(USER_AGENT_KEY, ua)?.apply()
        }
    }

    fun clearCookies() {
        synchronized(lock) {
            scopedCookieJar?.clear()
            prefs?.edit()
                ?.remove(LEGACY_COOKIE_KEY)
                ?.remove(COOKIE_RECORDS_KEY)
                ?.apply()
        }
    }
}

/**
 * STPlayer's generic browser-fetcher considers these status/header/body markers
 * a challenge. Keep the detector independent from WebView so transport tests can
 * validate it without Android UI.
 */
internal fun isRuTrackerBrowserChallenge(
    statusCode: Int,
    cfMitigated: String?,
    rawHtml: String,
): Boolean {
    if (cfMitigated.equals("challenge", ignoreCase = true)) return true
    if (rawHtml.isBlank()) {
        return statusCode == 403 || statusCode == 503
    }

    val lower = rawHtml.lowercase()
    if (RUTRACKER_SUCCESS_MARKERS.any(lower::contains)) return false
    if (RUTRACKER_CHALLENGE_MARKERS.any(lower::contains)) return true

    return statusCode == 403 || statusCode == 503
}

private val RUTRACKER_SUCCESS_MARKERS = listOf(
    "window.bb",
    "class=\"vf-table",
    "id=\"tor-tbl\"",
    "id=\"trs-tr-",
    "class=\"forumline",
    "data-topic_id=",
    "id=\"topic_main\"",
    "class=\"post_body",
    "class=\"magnet-link",
)

private val RUTRACKER_CHALLENGE_MARKERS = listOf(
    "verify you are human",
    "performing security verification",
    "just a moment",
    "challenges.cloudflare.com",
    "cf_chl",
    "cf-turnstile",
    "/cdn-cgi/challenge-platform",
    "ddos-guard",
)

private const val RUTRACKER_SUCCESS_PROBE = """
(function() {
  var title = (document.title || '').toLowerCase();
  var path = (location.pathname || '').toLowerCase();
  var host = (location.hostname || '').toLowerCase();
  var protocol = (location.protocol || '').toLowerCase();
  var port = location.port || '';
  var text = (document.body && document.body.innerText || '').toLowerCase();

  if (protocol !== 'https:') return false;
  if (port && port !== '443') return false;
  if (!(host === 'rutracker.org' || host.endsWith('.rutracker.org'))) return false;
  if (path.indexOf('/cdn-cgi/') === 0) return false;

  var markers = [
    'verify you are human',
    'performing security verification',
    'just a moment',
    'challenges.cloudflare.com',
    'cf_chl',
    'cf-turnstile',
    '/cdn-cgi/challenge-platform',
    'ddos-guard'
  ];
  for (var i = 0; i < markers.length; i++) {
    if (title.indexOf(markers[i]) >= 0 || text.indexOf(markers[i]) >= 0) return false;
  }

  return !!document.querySelector(
    '#page_content, #main_content, .vf-table, #tor-tbl, tr[id^="trs-tr-"], .forumline, #topic-title, #topic_main, .post_body'
  );
})()
"""
