package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuTrackerCloudflareActivityPolicyTest {
    @Test
    fun challengeStateUsesProviderDefaultForBlankTarget() {
        val coordinator = BrowserChallengeCoordinator(
            defaultTargetUrl = RUTRACKER_FORUM_URL,
        )

        val requestId = coordinator.requireChallenge("")

        assertTrue(coordinator.uiState.value.required)
        assertEquals(requestId, coordinator.uiState.value.requestId)
        assertEquals(RUTRACKER_FORUM_URL, coordinator.uiState.value.targetUrl)
    }

    @Test
    fun finishingActivityCancelsOnlyItsOwnActiveChallenge() {
        assertTrue(
            shouldCancelHostedBrowserChallenge(
                hostedRequestId = 12L,
                current = BrowserChallengeUiState(
                    requestId = 12L,
                    targetUrl = RUTRACKER_FORUM_URL,
                    required = true,
                ),
                isFinishing = true,
                isChangingConfigurations = false,
            )
        )
    }

    @Test
    fun staleActivityCannotCancelNewerChallenge() {
        assertFalse(
            shouldCancelHostedBrowserChallenge(
                hostedRequestId = 12L,
                current = BrowserChallengeUiState(
                    requestId = 13L,
                    targetUrl = RUTRACKER_FORUM_URL,
                    required = true,
                ),
                isFinishing = true,
                isChangingConfigurations = false,
            )
        )
    }

    @Test
    fun configurationChangeDoesNotCancelChallenge() {
        assertFalse(
            shouldCancelHostedBrowserChallenge(
                hostedRequestId = 12L,
                current = BrowserChallengeUiState(
                    requestId = 12L,
                    targetUrl = RUTRACKER_FORUM_URL,
                    required = true,
                ),
                isFinishing = true,
                isChangingConfigurations = true,
            )
        )
    }

    @Test
    fun nonFinishingActivityDoesNotCancelChallenge() {
        assertFalse(
            shouldCancelHostedBrowserChallenge(
                hostedRequestId = 12L,
                current = BrowserChallengeUiState(
                    requestId = 12L,
                    targetUrl = RUTRACKER_FORUM_URL,
                    required = true,
                ),
                isFinishing = false,
                isChangingConfigurations = false,
            )
        )
    }
}
