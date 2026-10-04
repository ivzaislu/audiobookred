package com.example.data.parser

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException

internal class AudiobooCloudflareRequiredException(
    val targetUrl: String,
    message: String = "Audioboo требует проверку Cloudflare",
) : IOException(message)

/**
 * Audioboo contributes only provider-specific challenge configuration and
 * persistence. Lifecycle/WebView/retry behavior is shared by every provider.
 */
internal object AudiobooCloudflareSession : BaseBrowserChallengeSession(
    challengeSpec = BrowserChallengeSpec(
        sourceId = AUDIOBOO_SOURCE,
        displayName = "Audioboo",
        defaultUrl = AUDIOBOO_BASE_URL,
        trustedHosts = setOf("audioboo.org"),
        successProbeScript = AUDIOBOO_SUCCESS_PROBE,
    ),
) {
    private const val PREFS = "audioboo_cloudflare"
    private const val COOKIE_KEY = "cookie_header"
    private const val USER_AGENT_KEY = "user_agent"

    private val lock = Any()

    @Volatile
    private var prefs: SharedPreferences? = null

    override fun initializeProvider(context: Context) {
        if (prefs != null) return
        synchronized(lock) {
            if (prefs == null) {
                prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            }
        }
    }

    fun cookieHeader(): String =
        prefs?.getString(COOKIE_KEY, "").orEmpty().trim()

    override fun persistedUserAgent(): String =
        prefs?.getString(USER_AGENT_KEY, "").orEmpty()

    override fun persistChallengeResult(
        targetUrl: String,
        cookieHeader: String,
        userAgent: String,
    ) {
        val cookie = cookieHeader.trim()
        val ua = userAgent.trim()
        prefs?.edit()?.apply {
            if (cookie.isNotBlank()) putString(COOKIE_KEY, cookie)
            if (ua.isNotBlank()) putString(USER_AGENT_KEY, ua)
        }?.apply()
    }

    fun clearCookies() {
        prefs?.edit()?.remove(COOKIE_KEY)?.apply()
    }

    override fun browserCookieLookupUrl(
        currentUrl: String,
        targetUrl: String,
    ): String = AUDIOBOO_BASE_URL
}

private const val AUDIOBOO_SUCCESS_PROBE = """
(function() {
  var title = (document.title || '').toLowerCase();
  var path = (location.pathname || '').toLowerCase();
  var host = (location.hostname || '').toLowerCase();
  var protocol = (location.protocol || '').toLowerCase();
  var port = location.port || '';

  if (protocol !== 'https:') return false;
  if (port && port !== '443') return false;
  if (!(host === 'audioboo.org' || host.endsWith('.audioboo.org'))) return false;
  if (title.indexOf('just a moment') >= 0) return false;
  if (path.indexOf('/cdn-cgi/') === 0) return false;

  return !!document.querySelector(
    '#dle-content, article.card, body#pmovie, .pmovie__player, .card__title'
  );
})()
"""
