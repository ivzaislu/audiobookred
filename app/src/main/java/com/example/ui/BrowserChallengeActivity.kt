package com.example.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.parser.BROWSER_CHALLENGE_REQUEST_ID_EXTRA
import com.example.data.parser.BROWSER_CHALLENGE_SOURCE_EXTRA
import com.example.data.parser.BrowserChallengeRegistry
import com.example.data.parser.BrowserChallengeSession
import com.example.data.parser.shouldCancelHostedBrowserChallenge
import com.example.ui.theme.AbredTheme

/**
 * Single Activity host for all browser-based anti-bot challenges.
 *
 * Providers are resolved by source id through [BrowserChallengeRegistry], so
 * adding a future provider does not require another Activity or WebView flow.
 */
class BrowserChallengeActivity : ComponentActivity() {
    private var hostedRequestId: Long = 0L
    private var hostedSession: BrowserChallengeSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sourceId = intent.challengeSourceId()
        hostedRequestId = intent.challengeRequestId()
        hostedSession = BrowserChallengeRegistry.find(sourceId)

        val session = hostedSession
        if (session == null || hostedRequestId <= 0L) {
            finish()
            return
        }

        enableEdgeToEdge()

        setContent {
            val state by session.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(state.required, state.requestId) {
                if (
                    !state.required ||
                    state.requestId != hostedRequestId
                ) {
                    finish()
                }
            }

            AbredTheme {
                BrowserChallengeGate(
                    session = session,
                    hostedRequestId = hostedRequestId,
                )
            }
        }
    }

    override fun onDestroy() {
        val session = hostedSession
        if (session != null) {
            val current = session.uiState.value
            if (
                shouldCancelHostedBrowserChallenge(
                    hostedRequestId = hostedRequestId,
                    current = current,
                    isFinishing = isFinishing,
                    isChangingConfigurations = isChangingConfigurations,
                )
            ) {
                session.cancelChallenge(hostedRequestId)
            }
        }
        super.onDestroy()
    }
}

private fun Intent.challengeSourceId(): String =
    getStringExtra(BROWSER_CHALLENGE_SOURCE_EXTRA).orEmpty()

private fun Intent.challengeRequestId(): Long =
    getLongExtra(BROWSER_CHALLENGE_REQUEST_ID_EXTRA, 0L)
