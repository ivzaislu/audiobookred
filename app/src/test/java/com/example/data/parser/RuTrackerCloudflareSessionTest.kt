package com.example.data.parser

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuTrackerCloudflareSessionTest {
    private class TestBrowserChallengeSession(
        private val coordinator: BrowserChallengeCoordinator,
    ) : BrowserChallengeSession {
        override val challengeSpec = BrowserChallengeSpec(
            sourceId = "test",
            displayName = "Test",
            defaultUrl = RUTRACKER_FORUM_URL,
            trustedHosts = setOf("rutracker.org"),
            successProbeScript = "true",
            timeoutMs = 1_000L,
        )
        override val uiState = coordinator.uiState

        override fun initialize(context: android.content.Context) = Unit

        override fun requireChallenge(targetUrl: String): Long =
            coordinator.requireChallenge(targetUrl)

        override suspend fun awaitResolution(
            requestId: Long,
            timeoutMs: Long,
        ): Boolean =
            coordinator.awaitResolution(
                requestId = requestId,
                customTimeoutMs = timeoutMs,
            )

        override fun completeChallenge(
            requestId: Long,
            targetUrl: String,
            cookieHeader: String,
            userAgent: String,
        ) {
            coordinator.completeChallenge(requestId)
        }

        override fun cancelChallenge(requestId: Long) {
            coordinator.cancelChallenge(requestId)
        }

        override fun attachInlineHost() = Unit

        override fun detachInlineHost() = Unit
    }

    private fun coordinator() = BrowserChallengeCoordinator(
        defaultTargetUrl = RUTRACKER_FORUM_URL,
    )

    @Test
    fun duplicateChallengeRequestReusesSingleActiveBrowserFlow() {
        val coordinator = coordinator()

        val first = coordinator.requireChallenge(
            "https://rutracker.org/forum/viewforum.php?f=2388",
        )
        val second = coordinator.requireChallenge(
            "https://rutracker.org/forum/viewtopic.php?t=123",
        )

        assertEquals(first, second)
        assertTrue(coordinator.uiState.value.required)
        assertEquals(
            "https://rutracker.org/forum/viewforum.php?f=2388",
            coordinator.uiState.value.targetUrl,
        )
    }

    @Test
    fun successfulChallengeResumesWaitingParserGateAutomatically() = runBlocking {
        val coordinator = coordinator()
        val target = "https://rutracker.org/forum/viewforum.php?f=2388"

        val gate = async(start = CoroutineStart.UNDISPATCHED) {
            TestBrowserChallengeSession(coordinator).awaitChallengeForRetry(
                targetUrl = target,
                timeoutMs = 1_000L,
            )
        }

        val active = coordinator.uiState.value
        assertTrue(active.required)
        assertEquals(target, active.targetUrl)

        assertTrue(coordinator.completeChallenge(active.requestId))
        assertTrue(gate.await())
        assertFalse(coordinator.uiState.value.required)
    }

    @Test
    fun canceledChallengeStopsParserRetryGate() = runBlocking {
        val coordinator = coordinator()

        val gate = async(start = CoroutineStart.UNDISPATCHED) {
            TestBrowserChallengeSession(coordinator).awaitChallengeForRetry(
                targetUrl = RUTRACKER_FORUM_URL,
                timeoutMs = 1_000L,
            )
        }

        val requestId = coordinator.uiState.value.requestId
        assertTrue(coordinator.cancelChallenge(requestId))
        assertFalse(gate.await())
        assertFalse(coordinator.uiState.value.required)
    }

    @Test
    fun coroutineCancellationClearsActiveChallenge() = runBlocking {
        val coordinator = coordinator()
        val requestId = coordinator.requireChallenge(RUTRACKER_FORUM_URL)

        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.awaitResolution(
                requestId = requestId,
                customTimeoutMs = 10_000L,
            )
        }

        waiter.cancelAndJoin()

        assertFalse(coordinator.uiState.value.required)
    }

    @Test
    fun staleCompletionCannotResolveNewerChallenge() {
        val coordinator = coordinator()
        val first = coordinator.requireChallenge(
            "https://rutracker.org/forum/viewtopic.php?t=1",
        )
        assertTrue(coordinator.cancelChallenge(first))

        val second = coordinator.requireChallenge(
            "https://rutracker.org/forum/viewtopic.php?t=2",
        )

        assertFalse(coordinator.completeChallenge(first))
        assertTrue(coordinator.uiState.value.required)
        assertEquals(second, coordinator.uiState.value.requestId)
    }

    @Test
    fun challengeTimeoutCancelsOnlyActiveRequest() = runBlocking {
        val coordinator = coordinator()
        val requestId = coordinator.requireChallenge(RUTRACKER_FORUM_URL)

        assertFalse(
            coordinator.awaitResolution(
                requestId = requestId,
                customTimeoutMs = 10L,
            )
        )
        assertFalse(coordinator.uiState.value.required)
    }

    @Test
    fun registryExposesBothCurrentChallengeProviders() {
        assertTrue(
            BrowserChallengeRegistry.find(AUDIOBOO_SOURCE) ===
                AudiobooCloudflareSession
        )
        assertTrue(
            BrowserChallengeRegistry.find(RUTRACKER_SOURCE) ===
                RuTrackerCloudflareSession
        )
        assertEquals(null, BrowserChallengeRegistry.find("unknown"))
    }
}
