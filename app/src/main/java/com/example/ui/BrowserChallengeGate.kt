package com.example.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.parser.BrowserChallengeRegistry
import com.example.data.parser.BrowserChallengeSession
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import kotlinx.coroutines.delay

/**
 * Shared interactive browser challenge surface.
 *
 * Every provider uses the same lifecycle and mobile WebView behavior. A provider
 * contributes only its trusted navigation policy, success probe and cookie
 * persistence through [BrowserChallengeSession].
 */
@Composable
internal fun BrowserChallengeGate(
    session: BrowserChallengeSession,
    hostedRequestId: Long,
    inline: Boolean = false,
) {
    val state by session.uiState.collectAsStateWithLifecycle()
    if (
        !state.required ||
        state.requestId != hostedRequestId
    ) {
        return
    }

    BrowserChallengeWebView(
        session = session,
        requestId = hostedRequestId,
        targetUrl = state.targetUrl,
        inline = inline,
        onCancel = { session.cancelChallenge(hostedRequestId) },
    )
}

@Composable
internal fun rememberInlineBrowserChallengeSession(): BrowserChallengeSession? {
    val sessions = remember { BrowserChallengeRegistry.sessions }

    DisposableEffect(sessions) {
        sessions.forEach(BrowserChallengeSession::attachInlineHost)
        onDispose {
            sessions.forEach(BrowserChallengeSession::detachInlineHost)
        }
    }

    var activeSession: BrowserChallengeSession? = null
    sessions.forEach { session ->
        val state by session.uiState.collectAsStateWithLifecycle()
        if (activeSession == null && state.required) {
            activeSession = session
        }
    }
    return activeSession
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserChallengeWebView(
    session: BrowserChallengeSession,
    requestId: Long,
    targetUrl: String,
    inline: Boolean,
    onCancel: () -> Unit,
) {
    var loading by remember(requestId) { mutableStateOf(true) }
    var webView: WebView? by remember(requestId) { mutableStateOf(null) }
    val cookieManager = remember(requestId) {
        CookieManager.getInstance().apply { setAcceptCookie(true) }
    }
    var browserUserAgent by remember(requestId) { mutableStateOf("") }
    var pageError by remember(requestId) { mutableStateOf<String?>(null) }

    fun probe(view: WebView) {
        val currentUrl = view.url.orEmpty()
        if (!session.isTrustedBrowserNavigation(currentUrl)) return

        view.evaluateJavascript(session.successProbeScript) { result ->
            if (result != "true") return@evaluateJavascript

            val cookieUrl = session.browserCookieLookupUrl(
                currentUrl = currentUrl,
                targetUrl = targetUrl,
            )
            val cookies = cookieManager.getCookie(cookieUrl).orEmpty()
            if (session.challengeSpec.requiresCookie && cookies.isBlank()) {
                return@evaluateJavascript
            }

            cookieManager.flush()
            session.completeChallenge(
                requestId = requestId,
                targetUrl = currentUrl,
                cookieHeader = cookies,
                userAgent = browserUserAgent.ifBlank {
                    view.settings.userAgentString.orEmpty()
                },
            )
        }
    }

    fun enforceMobileViewport(view: WebView) {
        view.evaluateJavascript(MOBILE_VIEWPORT_SCRIPT, null)
    }

    BackHandler(enabled = true) {
        val current = webView
        if (current?.canGoBack() == true) {
            current.goBack()
        } else {
            onCancel()
        }
    }

    DisposableEffect(requestId) {
        onDispose {
            webView?.apply {
                stopLoading()
                clearHistory()
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }

    LaunchedEffect(requestId, webView) {
        while (
            session.uiState.value.let {
                it.required && it.requestId == requestId
            }
        ) {
            delay(AUTO_VERIFY_POLL_MS)
            webView?.let(::probe)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (inline) {
                        Modifier
                    } else {
                        Modifier.systemBarsPadding()
                    }
                ),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = AbredSpacing.Sm,
                            vertical = AbredSpacing.Xs,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Проверка ${session.challengeDisplayName}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Страница открыта в мобильном режиме",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = AbredSpacing.Xs)
                                .size(AbredSizes.Icon),
                            strokeWidth = 2.dp,
                        )
                    }
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            webView = this
                            browserUserAgent = chooseBrowserChallengeUserAgent(
                                currentWebViewUserAgent = settings.userAgentString,
                                defaultWebViewUserAgent = runCatching {
                                    WebSettings.getDefaultUserAgent(context)
                                }.getOrDefault(""),
                            )

                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE

                            // Preserve the real System WebView identity. Mobile
                            // rendering is controlled by viewport/layout instead.
                            settings.useWideViewPort = false
                            settings.loadWithOverviewMode = false
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.textZoom = 100

                            cookieManager.setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    if (
                                        !shouldBlockBrowserChallengeNavigation(
                                            session = session,
                                            url = request.url.toString(),
                                            isForMainFrame = request.isForMainFrame,
                                        )
                                    ) {
                                        return false
                                    }
                                    onCancel()
                                    return true
                                }

                                override fun onPageStarted(
                                    view: WebView,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    loading = true
                                    pageError = null
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (
                                        shouldShowBrowserChallengeLoadError(
                                            session = session,
                                            url = request.url.toString(),
                                            isForMainFrame = request.isForMainFrame,
                                        )
                                    ) {
                                        loading = false
                                        pageError = error.description
                                            ?.toString()
                                            ?.takeIf { it.isNotBlank() }
                                            ?: "Не удалось открыть страницу проверки"
                                    }
                                }

                                override fun onPageCommitVisible(
                                    view: WebView,
                                    url: String?,
                                ) {
                                    if (
                                        session.isTrustedBrowserNavigation(
                                            url.orEmpty()
                                        )
                                    ) {
                                        enforceMobileViewport(view)
                                    }
                                }

                                override fun onPageFinished(
                                    view: WebView,
                                    url: String?,
                                ) {
                                    loading = false
                                    val currentUrl = url.orEmpty()
                                    if (!session.isTrustedBrowserNavigation(currentUrl)) {
                                        return
                                    }
                                    enforceMobileViewport(view)
                                    probe(view)
                                }
                            }

                            loadUrl(
                                targetUrl
                                    .takeIf(session::isTrustedBrowserNavigation)
                                    ?: session.defaultChallengeUrl
                            )
                        }
                    },
                )

                pageError?.let { message ->
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(AbredSpacing.Lg),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "Не удалось открыть страницу проверки",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                message,
                                modifier = Modifier.padding(top = AbredSpacing.Xs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier
                                    .padding(top = AbredSpacing.Md)
                                    .fillMaxWidth(),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                                    AbredSpacing.Sm,
                                    Alignment.CenterHorizontally,
                                ),
                            ) {
                                Button(
                                    onClick = {
                                        pageError = null
                                        loading = true
                                        webView?.loadUrl(
                                            targetUrl
                                                .takeIf(session::isTrustedBrowserNavigation)
                                                ?: session.defaultChallengeUrl
                                        )
                                    },
                                ) {
                                    Text("Повторить")
                                }
                                OutlinedButton(onClick = onCancel) {
                                    Text("Закрыть")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun chooseBrowserChallengeUserAgent(
    currentWebViewUserAgent: String?,
    defaultWebViewUserAgent: String?,
): String =
    currentWebViewUserAgent.orEmpty().trim()
        .ifBlank { defaultWebViewUserAgent.orEmpty().trim() }

internal fun shouldBlockBrowserChallengeNavigation(
    session: BrowserChallengeSession,
    url: String,
    isForMainFrame: Boolean,
): Boolean =
    isForMainFrame && !session.isTrustedBrowserNavigation(url)

internal fun shouldShowBrowserChallengeLoadError(
    session: BrowserChallengeSession,
    url: String,
    isForMainFrame: Boolean,
): Boolean =
    isForMainFrame && session.isTrustedBrowserNavigation(url)

private const val AUTO_VERIFY_POLL_MS = 400L

private const val MOBILE_VIEWPORT_SCRIPT = """
(function() {
  var head = document.head || document.getElementsByTagName('head')[0];
  if (!head) return false;
  var viewport = document.querySelector('meta[name="viewport"]');
  if (!viewport) {
    viewport = document.createElement('meta');
    viewport.setAttribute('name', 'viewport');
    head.appendChild(viewport);
  }
  viewport.setAttribute(
    'content',
    'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes'
  );
  return true;
})()
"""
