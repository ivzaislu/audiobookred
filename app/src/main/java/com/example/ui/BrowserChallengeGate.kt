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
